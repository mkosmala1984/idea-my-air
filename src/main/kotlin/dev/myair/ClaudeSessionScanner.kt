package dev.myair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.io.StringReader
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * [lastMessage] is the time of the latest message of the conversation, not of the file: resuming a session appends lines
 * too. [changedFiles] are the files the agent wrote or edited, in the order it first touched them; [prompts] the first human prompts.
 */
data class ClaudeSession(
    val id: String, val cwd: Path, val title: String, val lastMessage: Instant, val changedFiles: List<Path>, val prompts: List<String>,
) {
    /** Case-insensitive substring match on the title or the project path; every word of [query] must match. */
    fun matches(query: String): Boolean =
        query.split(' ').filter { it.isNotEmpty() }.all { title.contains(it, true) || cwd.toString().contains(it, true) }

    /** TreeState matches nodes by toString(), so a session keeps its selection while its title and time change. */
    override fun toString() = id
}

/**
 * Reads `<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`. Subagent transcripts live one level deeper and are not listed.
 * The directory name encodes the cwd lossily, so the cwd comes from the transcript itself.
 * Transcripts are append-only, so a rescan reads only the lines added since the previous one.
 */
class ClaudeSessionScanner(private val projectsDir: Path) {
    private val cache = HashMap<Path, Transcript>()

    @Synchronized
    fun scan(): List<ClaudeSession> {
        val files = projectsDir.listDirectoryEntries().filter { it.isDirectory() }
            .flatMap { dir -> dir.listDirectoryEntries().filter { it.extension == "jsonl" } }
        cache.keys.retainAll(files.toSet())
        return files.mapNotNull { file ->
            val size = Files.size(file)
            // A file shorter than what was already read has been rewritten: start over.
            val known = cache[file]?.takeIf { size >= it.offset }
            val transcript = if (known != null && known.offset == size) known else read(file, known ?: Transcript.EMPTY)
            cache[file] = transcript
            transcript.toSession(file)
        }.sortedByDescending { it.lastMessage }
    }

    /** What a transcript says up to [offset], the byte after its last complete line. */
    data class Transcript(
        val offset: Long, val customTitle: String?, val aiTitle: String?, val cwd: String?, val prompts: List<String>,
        val changedFiles: List<String>, val lastMessage: Instant?,
    ) {
        /** Null for a transcript with neither a title nor a human prompt: there is nothing to resume. */
        fun toSession(file: Path): ClaudeSession? {
            val title = customTitle ?: aiTitle ?: prompts.firstOrNull()?.lineSequence()?.first()?.take(80) ?: return null
            val cwd = Path.of(checkNotNull(cwd) { "titled session without cwd: $file" })
            val lastMessage = checkNotNull(lastMessage) { "titled session without a message: $file" }
            return ClaudeSession(file.nameWithoutExtension, cwd, title, lastMessage, changedFiles.map(Path::of), prompts)
        }

        companion object {
            val EMPTY = Transcript(0, null, null, null, emptyList(), emptyList(), null)
        }
    }

    companion object {
        private const val CUSTOM_TITLE = "{\"type\":\"custom-title\""
        private const val AI_TITLE = "{\"type\":\"ai-title\""
        private const val CWD = "\"cwd\":"

        /** Human prompts kept per session: enough for an agent to judge what the session was about. */
        const val PROMPTS = 3
        private const val ASSISTANT = "\"type\":\"assistant\""

        /** Substrings of every line [messageTime] may accept; they only spare parsing the lines that cannot be messages. */
        private val MESSAGE_HINTS = listOf("\"type\":\"user\"", ASSISTANT, "\"subtype\":\"local_command\"")
        private const val NEWLINE = '\n'.code.toByte()

        /** The tools Air's PostToolUse hook watches for project changes. */
        private val EDIT_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")
        private val EDIT_TOOL_USE = Regex("\"name\":\"(${EDIT_TOOLS.joinToString("|")})\",\"input\"")

        fun parse(file: Path): ClaudeSession? = read(file, Transcript.EMPTY).toSession(file)

        /** Continues [from] with the complete lines after its offset; a line still being written is left for the next read. */
        fun read(file: Path, from: Transcript): Transcript {
            var customTitle = from.customTitle
            var aiTitle = from.aiTitle
            var cwd = from.cwd
            val prompts = from.prompts.toMutableList()
            val changedFiles = LinkedHashSet(from.changedFiles)
            var lastMessage = from.lastMessage
            val offset = forEachCompleteLine(file, from.offset) { line ->
                if (MESSAGE_HINTS.any { it in line }) messageTime(line)?.let { lastMessage = it }
                when {
                    line.startsWith(CUSTOM_TITLE) -> customTitle = json(line)["customTitle"].asString
                    line.startsWith(AI_TITLE) -> aiTitle = json(line)["aiTitle"].asString
                    ASSISTANT in line && EDIT_TOOL_USE.containsMatchIn(line) -> changedFiles += editedFiles(json(line))
                    (cwd == null || prompts.size < PROMPTS) && CWD in line -> {
                        val o = json(line)
                        if (o["isSidechain"]?.asBoolean != true) {
                            cwd = cwd ?: o["cwd"]?.asString
                            if (prompts.size < PROMPTS && o["type"]?.asString == "user" && o["isMeta"]?.asBoolean != true) promptText(o)?.let(prompts::add)
                        }
                    }
                }
            }
            return Transcript(offset, customTitle, aiTitle, cwd, prompts, changedFiles.toList(), lastMessage)
        }

        /**
         * The time of [line] when it is a message of the main conversation: a human or assistant one, or a slash command
         * run; null for anything else. Reads only top-level fields, skipping message bodies without building them.
         */
        private fun messageTime(line: String): Instant? {
            var type: String? = null
            var subtype: String? = null
            var timestamp: String? = null
            var aside = false
            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "type" -> type = reader.nextString()
                    "subtype" -> subtype = reader.nextString()
                    "timestamp" -> timestamp = reader.nextString()
                    "isMeta", "isSidechain" -> aside = reader.nextBoolean() || aside
                    else -> reader.skipValue()
                }
            }
            val message = type == "user" || type == "assistant" || type == "system" && subtype == "local_command"
            return if (message && !aside) Instant.parse(checkNotNull(timestamp) { "message without timestamp: ${line.take(200)}" }) else null
        }

        /** Paths from the edit tool calls of one assistant message; a notebook edit names its file `notebook_path`. */
        private fun editedFiles(o: JsonObject): List<String> =
            o.getAsJsonObject("message").getAsJsonArray("content").map { it.asJsonObject }
                .filter { it["type"].asString == "tool_use" && it["name"].asString in EDIT_TOOLS }
                .map { val input = it.getAsJsonObject("input"); checkNotNull(input["file_path"] ?: input["notebook_path"]) { "edit without a path: $it" }.asString }

        /** Returns the offset just past the last newline read. */
        private fun forEachCompleteLine(file: Path, from: Long, action: (String) -> Unit): Long {
            FileChannel.open(file).use { channel ->
                val input = Channels.newInputStream(channel.position(from))
                val buffer = ByteArray(1 shl 16)
                val line = ByteArrayOutputStream()
                var position = from
                var end = from
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) return end
                    var start = 0
                    for (i in 0 until n) if (buffer[i] == NEWLINE) {
                        line.write(buffer, start, i - start)
                        action(line.toString(Charsets.UTF_8))
                        line.reset()
                        start = i + 1
                        end = position + i + 1
                    }
                    line.write(buffer, start, n - start)
                    position += n
                }
            }
        }

        private fun json(line: String): JsonObject = JsonParser.parseString(line).asJsonObject

        /** Plain text the human typed; tool results and harness-injected `<...>` blocks are not prompts. */
        private fun promptText(o: JsonObject): String? {
            val content = o.getAsJsonObject("message")["content"]
            val text = if (content.isJsonPrimitive) content.asString
            else content.asJsonArray.map { it.asJsonObject }.firstOrNull { it["type"].asString == "text" }?.get("text")?.asString
            return text?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
        }
    }
}
