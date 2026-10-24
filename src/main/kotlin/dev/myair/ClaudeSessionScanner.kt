package dev.myair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

data class ClaudeSession(val id: String, val cwd: Path, val title: String, val lastModified: FileTime) {
    /** Case-insensitive substring match on the title or the project path; every word of [query] must match. */
    fun matches(query: String): Boolean =
        query.split(' ').filter { it.isNotEmpty() }.all { title.contains(it, true) || cwd.toString().contains(it, true) }
}

/**
 * Reads `<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`. Subagent transcripts live one level deeper and are not listed.
 * The directory name encodes the cwd lossily, so the cwd comes from the transcript itself.
 */
class ClaudeSessionScanner(private val projectsDir: Path) {
    private data class FileKey(val size: Long, val lastModified: FileTime)

    private val cache = HashMap<Path, Pair<FileKey, ClaudeSession?>>()

    @Synchronized
    fun scan(): List<ClaudeSession> {
        val files = projectsDir.listDirectoryEntries().filter { it.isDirectory() }
            .flatMap { dir -> dir.listDirectoryEntries().filter { it.extension == "jsonl" } }
        cache.keys.retainAll(files.toSet())
        return files.mapNotNull { file ->
            val key = FileKey(Files.size(file), Files.getLastModifiedTime(file))
            val cached = cache[file]
            if (cached != null && cached.first == key) cached.second
            else parse(file, key.lastModified).also { cache[file] = key to it }
        }.sortedByDescending { it.lastModified }
    }

    companion object {
        private const val CUSTOM_TITLE = "{\"type\":\"custom-title\""
        private const val AI_TITLE = "{\"type\":\"ai-title\""
        private const val CWD = "\"cwd\":"

        /** Returns null for a transcript with neither a title nor a human prompt: there is nothing to resume. */
        fun parse(file: Path, lastModified: FileTime): ClaudeSession? {
            var customTitle: String? = null
            var aiTitle: String? = null
            var cwd: String? = null
            var firstPrompt: String? = null
            Files.newBufferedReader(file).useLines { lines ->
                for (line in lines) when {
                    line.startsWith(CUSTOM_TITLE) -> customTitle = json(line)["customTitle"].asString
                    line.startsWith(AI_TITLE) -> aiTitle = json(line)["aiTitle"].asString
                    (cwd == null || firstPrompt == null) && CWD in line -> {
                        val o = json(line)
                        if (o["isSidechain"]?.asBoolean == true) continue
                        cwd = cwd ?: o["cwd"]?.asString
                        if (firstPrompt == null && o["type"]?.asString == "user" && o["isMeta"]?.asBoolean != true) firstPrompt = promptText(o)
                    }
                }
            }
            val title = customTitle ?: aiTitle ?: firstPrompt?.lineSequence()?.first()?.take(80) ?: return null
            return ClaudeSession(file.nameWithoutExtension, Path.of(checkNotNull(cwd) { "titled session without cwd: $file" }), title, lastModified)
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
