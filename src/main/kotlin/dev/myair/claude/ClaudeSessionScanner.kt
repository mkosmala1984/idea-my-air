package dev.myair.claude

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.myair.store.writeAtomically
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.io.StringReader
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Reads `<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`. Subagent transcripts live one level deeper and are not listed.
 * The directory name encodes the cwd lossily, so the cwd comes from the transcript itself.
 * Transcripts are append-only, so a rescan reads only the lines added since the previous one; with [saved], so does the
 * first scan after a restart.
 */
class ClaudeSessionScanner(private val projectsDir: Path, private val saved: Saved? = null) {
    /** Loaded by the first scan, off the thread that builds the scanner: the file holds megabytes. */
    private val cache by lazy { saved?.load() ?: HashMap() }
    private var unsaved = false
    private var savedAt = 0L

    /** [desktopProjects]: the folder Claude Desktop started each of its sessions in, by session id ([DesktopSessionReader]). */
    @Synchronized
    fun scan(desktopProjects: Map<String, Path> = emptyMap()): List<ClaudeSession> {
        val files = projectsDir.listDirectoryEntries().filter { it.isDirectory() }
            .flatMap { dir -> dir.listDirectoryEntries().filter { it.extension == "jsonl" } }
        if (cache.keys.retainAll(files.toSet())) unsaved = true
        val sessions = files.mapNotNull { file ->
            val size = Files.size(file)
            // A file shorter than what was already read has been rewritten: start over.
            val known = cache[file]?.takeIf { size >= it.offset }
            val transcript = if (known != null && known.offset == size) known else read(file, known ?: Transcript.EMPTY)
            if (transcript != known) unsaved = true
            cache[file] = transcript
            transcript.toSession(file, desktopProjects[file.nameWithoutExtension])
        }.sortedByDescending { it.lastMessage }
        // Live sessions append every second; after a crash, a minute of lines is cheap to read again.
        if (saved != null && System.currentTimeMillis() - savedAt >= SAVE_INTERVAL_MS) save()
        return sessions
    }

    /** Writes what was read to [Saved.file], if anything changed since the last write. */
    @Synchronized
    fun save() {
        val saved = checkNotNull(saved) { "scanner of $projectsDir has no file to save to" }
        savedAt = System.currentTimeMillis()
        if (!unsaved) return
        saved.write(cache)
        unsaved = false
    }

    /**
     * The scanner's state kept across restarts in [file], valid only for the [build] of the plugin that wrote it: another
     * build may read transcripts differently, so it starts over.
     */
    class Saved(val file: Path, private val build: String) {
        fun load(): HashMap<Path, Transcript> {
            if (!file.exists()) return HashMap()
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root["build"].asString != build) return HashMap()
            return root.getAsJsonObject("transcripts").entrySet().associateTo(HashMap()) { (path, t) -> Path.of(path) to gson.fromJson(t, Transcript::class.java) }
        }

        fun write(transcripts: Map<Path, Transcript>) {
            file.parent.createDirectories()
            writeAtomically(file, gson.toJson(mapOf("build" to build, "transcripts" to transcripts.mapKeys { it.key.toString() })))
        }

        companion object {
            /**
             * The cache of the plugin in [jar], in [dir]. Each jar (the installed plugin, a sandbox's) gets a file of its
             * own, so IDEs running different builds do not discard each other's; its build is a hash of the classes of
             * this package, which read the transcripts, so a rebuild that leaves them as they were keeps the cache.
             */
            fun forJar(dir: Path, jar: Path): Saved {
                val classes = ZipFile(jar.toFile()).use { zip ->
                    zip.entries().asSequence().filter { it.name.startsWith(CLASSES) && it.name.endsWith(".class") }
                        .sortedBy { it.name }.map { it.name to zip.getInputStream(it).readBytes() }.toList()
                }
                check(classes.isNotEmpty()) { "no $CLASSES classes in $jar" }
                val build = MessageDigest.getInstance("SHA-256")
                classes.forEach { (name, bytes) -> build.update(name.toByteArray()); build.update(bytes) }
                return Saved(dir.resolve("scan-cache-${hex(sha256(jar.toAbsolutePath().toString().toByteArray())).take(8)}.json"), hex(build.digest()))
            }

            private const val CLASSES = "dev/myair/claude/"

            private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

            private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

            private fun <T> asString(parse: (String) -> T): TypeAdapter<T> = object : TypeAdapter<T>() {
                override fun write(out: JsonWriter, value: T) {
                    out.value(value.toString())
                }

                override fun read(reader: JsonReader): T = parse(reader.nextString())
            }.nullSafe()

            private val gson: Gson = GsonBuilder()
                .registerTypeAdapter(Instant::class.java, asString(Instant::parse))
                .registerTypeHierarchyAdapter(Path::class.java, asString(Path::of))
                .create()
        }
    }

    /** What a transcript says up to [offset], the byte after its last complete line. */
    data class Transcript(
        val offset: Long, val customTitle: String?, val aiTitle: String?, val cwd: String?, val prompts: List<String>,
        val changedFiles: List<String>, val lastMessage: Instant?, val brief: Brief?, val briefCalls: Map<String, BriefCall>,
        val addedDirs: List<String>, val model: String?, val effort: String?, val artifacts: Map<String, Artifact>,
        val shellCalls: Map<String, ShellCall>, val background: Map<String, BackgroundTask>, val skills: List<String>,
        val mcpTools: List<String>,
    ) {
        /** Null for a transcript with neither a title nor a human prompt: there is nothing to resume. */
        fun toSession(file: Path, desktopProject: Path? = null): ClaudeSession? {
            val title = customTitle ?: aiTitle ?: prompts.firstOrNull()?.lineSequence()?.first()?.take(80) ?: return null
            val cwd = Path.of(checkNotNull(cwd) { "titled session without cwd: $file" })
            val lastMessage = checkNotNull(lastMessage) { "titled session without a message: $file" }
            return ClaudeSession(
                file.nameWithoutExtension, cwd, title, lastMessage, changedFiles.map(Path::of), prompts, brief, desktopProject ?: cwd,
                desktop = desktopProject != null, addedDirs = addedDirs.map(Path::of).filter { it != cwd }, model = model, effort = effort,
                artifacts = artifacts.values.toList(), background = background.values.toList(), skills = skills, mcpServers = mcpServers(mcpTools),
            )
        }

        companion object {
            val EMPTY = Transcript(
                0, null, null, null, emptyList(), emptyList(), null, null, emptyMap(), emptyList(), null, null, emptyMap(), emptyMap(), emptyMap(),
                emptyList(), emptyList(),
            )
        }
    }

    /**
     * A brief or plan call whose result is not read yet: it counts only if the tool accepted it. Calls made in one message
     * are all written before their results, so the transcript keeps them by id.
     */
    data class BriefCall(val id: String, val tool: String, val input: JsonObject)

    /** A shell or monitor call whose result has not said yet whether it went to the background. */
    data class ShellCall(val kind: BackgroundTask.Kind, val description: String?)

    companion object {
        private const val CUSTOM_TITLE = "{\"type\":\"custom-title\""
        private const val AI_TITLE = "{\"type\":\"ai-title\""
        private const val CWD = "\"cwd\":"

        /** Human prompts kept per session: enough for an agent to judge what the session was about. */
        const val PROMPTS = 3
        private const val SAVE_INTERVAL_MS = 60_000L
        private const val ASSISTANT = "\"type\":\"assistant\""
        private const val SYNTHETIC = "<synthetic>"

        /** Substrings of every line [messageTime] may accept; they only spare parsing the lines that cannot be messages. */
        private val MESSAGE_HINTS = listOf("\"type\":\"user\"", ASSISTANT, "\"subtype\":\"local_command\"")
        private const val NEWLINE = '\n'.code.toByte()

        /** The tools Air's PostToolUse hook watches for project changes. */
        private val EDIT_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")
        private val EDIT_TOOL_USE = Regex("\"name\":\"(${EDIT_TOOLS.joinToString("|")})\",\"input\"")

        /** The MCP server's name comes from the user's config, so the brief tool is matched by its suffix. */
        private val BRIEF_TOOL = Regex("mcp__[^\"]*__${Brief.SET_BRIEF}|${Brief.UPDATE_PLAN}")
        private val BRIEF_TOOL_USE = Regex("\"name\":\"(${BRIEF_TOOL.pattern})\",\"input\"")
        private const val TOOL_RESULT = "\"type\":\"tool_result\""

        private const val MONITOR = "Monitor"

        /** Calls that may run in the background and name it only in their input; agents and workflows name it in their result. */
        private val SHELL_TOOLS = setOf("Bash", "PowerShell", MONITOR)
        private val SHELL_TOOL_USE = Regex("\"name\":\"(${SHELL_TOOLS.joinToString("|")})\",\"input\"")
        private const val ASYNC_LAUNCHED = "\"status\":\"async_launched\""

        /** The line `claude` writes when a notification is queued for the conversation, before the model reads it. */
        private const val ENQUEUE = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\""
        private const val TASK_NOTIFICATION = "<task-notification>"

        /** How a monitor's event says it timed out; no final notification follows. */
        private const val MONITOR_EXPIRED = "[Monitor expired"

        /** How a background shell's result names its output file. */
        private val SHELL_OUTPUT = Regex("Output is being written to: (.+?\\.output)")

        /** In the `toolUseResult` of an Artifact publish, which names the artifact's url, title and local file. */
        private const val ARTIFACT = "\"artifact_id\":"

        /** The attachment `claude` writes with the session's environment: cwd, worktree, the directories added to it. */
        private const val ENVIRONMENT = "\"attachment\":{\"type\":\"environment\",\"snapshot\":"

        /** The attachment listing the skills: all of them at the start (`isInitial`), the ones added later after it. */
        private const val SKILL_LISTING = "\"attachment\":{\"type\":\"skill_listing\""

        /** The attachment naming the deferred tools added and removed, MCP ones among them, as servers connect and go. */
        private const val DEFERRED_TOOLS = "\"attachment\":{\"type\":\"deferred_tools_delta\""
        private const val MCP_TOOL = "mcp__"

        fun parse(file: Path): ClaudeSession? = read(file, Transcript.EMPTY).toSession(file)

        /** Continues [from] with the complete lines after its offset; a line still being written is left for the next read. */
        fun read(file: Path, from: Transcript): Transcript {
            var customTitle = from.customTitle
            var aiTitle = from.aiTitle
            var cwd = from.cwd
            val prompts = from.prompts.toMutableList()
            val changedFiles = LinkedHashSet(from.changedFiles)
            var lastMessage = from.lastMessage
            var brief = from.brief
            val briefCalls = LinkedHashMap(from.briefCalls)
            var addedDirs = from.addedDirs
            var model = from.model
            var effort = from.effort
            // A republished artifact keeps the position of its first publish.
            val artifacts = LinkedHashMap(from.artifacts)
            // By tool_use id: shell and monitor calls whose result has not told yet whether they went to the background.
            val shellCalls = HashMap(from.shellCalls)
            val background = LinkedHashMap(from.background)
            var skills = from.skills
            val mcpTools = LinkedHashSet(from.mcpTools)
            val offset = forEachCompleteLine(file, from.offset) { line ->
                if (SKILL_LISTING in line) json(line).takeIf { it["isSidechain"]?.asBoolean != true }?.let { o ->
                    val listing = o.getAsJsonObject("attachment")
                    val names = checkNotNull(listing.getAsJsonArray("names")) { "skill listing without names: $file" }.map { it.asString }
                    val initial = checkNotNull(listing["isInitial"]) { "skill listing without isInitial: $file" }.asBoolean
                    skills = if (initial) names else (skills + names).distinct()
                }
                if (DEFERRED_TOOLS in line) json(line).takeIf { it["isSidechain"]?.asBoolean != true }?.let { o ->
                    val delta = o.getAsJsonObject("attachment")
                    fun names(key: String) = checkNotNull(delta.getAsJsonArray(key)) { "deferred tools delta without $key: $file" }
                        .map { it.asString }.filter { it.startsWith(MCP_TOOL) }
                    mcpTools -= names("removedNames").toSet()
                    mcpTools += names("addedNames")
                }
                // Outside the `when` below: the line also carries a cwd.
                if (ENVIRONMENT in line) json(line).takeIf { it["isSidechain"]?.asBoolean != true }?.let { o ->
                    val snapshot = o.getAsJsonObject("attachment").getAsJsonObject("snapshot")
                    addedDirs = checkNotNull(snapshot.getAsJsonArray("additionalWorkingDirectories")) { "environment without additionalWorkingDirectories: $file" }
                        .map { it.asString }
                }
                if (ARTIFACT in line) json(line).takeIf { it["isSidechain"]?.asBoolean != true }?.getAsJsonObject("toolUseResult")?.let { r ->
                    fun field(name: String) = checkNotNull(r[name]?.asString) { "artifact result without $name: $file" }
                    artifacts[field("artifact_id")] = Artifact(field("artifact_id"), field("title"), field("url"), Path.of(field("path")))
                }
                if (ASSISTANT in line && SHELL_TOOL_USE.containsMatchIn(line)) json(line).takeIf { it["isSidechain"]?.asBoolean != true }
                    ?.let { o -> toolUses(o).filter { it["name"].asString in SHELL_TOOLS } }
                    ?.forEach { use ->
                        val input = use.getAsJsonObject("input")
                        val kind = if (use["name"].asString == MONITOR) BackgroundTask.Kind.MONITOR else BackgroundTask.Kind.SHELL
                        // A shell call may come without a description; its command's first line names it then. A call
                        // the tool rejects may have neither, and never runs.
                        shellCalls[use["id"].asString] = ShellCall(kind, input["description"]?.asString ?: input["command"]?.asString?.lineSequence()?.first())
                    }
                if (TOOL_RESULT in line && (shellCalls.keys.any { "\"$it\"" in line } || ASYNC_LAUNCHED in line)) json(line)
                    .takeIf { it["isSidechain"]?.asBoolean != true }?.let { o ->
                        val result = o["toolUseResult"] as? JsonObject
                        val started = time(o)
                        o.getAsJsonObject("message").getAsJsonArray("content").map { it.asJsonObject }
                            .filter { it["type"].asString == "tool_result" }
                            .forEach { item ->
                                val call = shellCalls.remove(item["tool_use_id"].asString)
                                val task = when {
                                    result == null -> null
                                    call != null -> (result["backgroundTaskId"] ?: result["taskId"].takeIf { call.kind == BackgroundTask.Kind.MONITOR })
                                        ?.let {
                                            val description = checkNotNull(call.description) { "background task without a description: $file" }
                                            val output = item["content"].takeIf { c -> c.isJsonPrimitive }?.let { c -> SHELL_OUTPUT.find(c.asString) }
                                            BackgroundTask(it.asString, call.kind, description, started, output?.let { m -> Path.of(m.groupValues[1]) })
                                        }
                                    result["status"]?.asString != "async_launched" -> null
                                    result.has("agentId") -> BackgroundTask(
                                        result["agentId"].asString, BackgroundTask.Kind.AGENT, result["description"].asString, started,
                                        result["outputFile"]?.asString?.let(Path::of), model = result["resolvedModel"]?.asString,
                                    )
                                    else -> BackgroundTask(result["taskId"].asString, BackgroundTask.Kind.WORKFLOW, result["summary"].asString, started)
                                }
                                if (task != null) background[task.id] = task
                            }
                    }
                if (line.startsWith(ENQUEUE) && TASK_NOTIFICATION in line) {
                    val o = json(line)
                    val content = o["content"].asString
                    // A monitor's events carry no status, but for the last ones its stream ended with; the notification
                    // that ends a task does. An agent sent another message notifies again, and its latest end counts.
                    val status = tag(content, "status")
                    val id = tag(content, "task-id")
                    if (status != null) checkNotNull(id) { "final notification without a task id: $file" }
                    if (id != null) background[id]?.let { task ->
                        val event = tag(content, "event")
                        background[id] = when {
                            event != null && event.startsWith(MONITOR_EXPIRED) -> task.copy(end = BackgroundTask.End(time(o), BackgroundTask.Status.EXPIRED, event))
                            else -> {
                                val counted = if (event == null) task else task.copy(events = task.events + 1, lastEvent = time(o))
                                if (status == null) counted else counted.copy(
                                    output = tag(content, "output-file")?.let(Path::of) ?: task.output,
                                    end = BackgroundTask.End(
                                        time(o), BackgroundTask.Status.valueOf(status.uppercase()),
                                        checkNotNull(tag(content, "summary")) { "final notification without a summary: $file" },
                                    ),
                                    // After an agent's result, whose text may hold anything.
                                    usage = content.lastIndexOf("<usage>").takeIf { it >= 0 }?.let { usage(content.substring(it)) } ?: task.usage,
                                )
                            }
                        }
                    }
                }
                if (MESSAGE_HINTS.any { it in line }) message(line)?.let {
                    lastMessage = it.time
                    model = it.model ?: model
                    effort = it.effort ?: effort
                }
                if (ASSISTANT in line && BRIEF_TOOL_USE.containsMatchIn(line)) json(line).takeIf { it["isSidechain"]?.asBoolean != true }
                    ?.let { o -> toolUses(o).filter { BRIEF_TOOL.matches(it["name"].asString) } }
                    ?.forEach { briefCalls[it["id"].asString] = BriefCall(it["id"].asString, it["name"].asString, it.getAsJsonObject("input")) }
                if (TOOL_RESULT in line && briefCalls.keys.any { "\"$it\"" in line }) {
                    json(line).getAsJsonObject("message").getAsJsonArray("content").map { it.asJsonObject }
                        .filter { it["type"].asString == "tool_result" }
                        .forEach { result ->
                            val call = briefCalls.remove(result["tool_use_id"].asString) ?: return@forEach
                            if (result["is_error"]?.asBoolean != true) brief = Brief.next(brief, call.tool, call.input)
                        }
                }
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
            return Transcript(offset, customTitle, aiTitle, cwd, prompts, changedFiles.toList(), lastMessage, brief, briefCalls, addedDirs, model, effort, artifacts, shellCalls, background, skills, mcpTools.toList())
        }

        /** A message of the main conversation: its [time], and for a reply of the model the [model] and [effort] it ran with. */
        private class Message(val time: Instant, val model: String?, val effort: String?)

        /**
         * [line] as a message of the main conversation: a human or assistant one, or a slash command run; null for anything
         * else. Reads only top-level fields and the model inside the body, skipping the rest without building it.
         */
        private fun message(line: String): Message? {
            var type: String? = null
            var subtype: String? = null
            var timestamp: String? = null
            var aside = false
            var model: String? = null
            var effort: String? = null
            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "type" -> type = reader.nextString()
                    "subtype" -> subtype = reader.nextString()
                    "timestamp" -> timestamp = reader.nextString()
                    "isMeta", "isSidechain" -> aside = reader.nextBoolean() || aside
                    "effort" -> effort = reader.nextString()
                    "message" -> if (reader.peek() != JsonToken.BEGIN_OBJECT) reader.skipValue() else {
                        reader.beginObject()
                        while (reader.hasNext()) if (reader.nextName() == "model") model = reader.nextString() else reader.skipValue()
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            val message = type == "user" || type == "assistant" || type == "system" && subtype == "local_command"
            if (!message || aside) return null
            val time = Instant.parse(checkNotNull(timestamp) { "message without timestamp: ${line.take(200)}" })
            // `claude` writes its own error replies as the model `<synthetic>`.
            return if (type == "assistant" && model != SYNTHETIC) Message(time, model, effort) else Message(time, null, null)
        }

        /** Paths from the edit tool calls of one assistant message; a notebook edit names its file `notebook_path`. */
        private fun editedFiles(o: JsonObject): List<String> =
            toolUses(o).filter { it["name"].asString in EDIT_TOOLS }
                .map { val input = it.getAsJsonObject("input"); checkNotNull(input["file_path"] ?: input["notebook_path"]) { "edit without a path: $it" }.asString }

        private fun toolUses(o: JsonObject): List<JsonObject> =
            o.getAsJsonObject("message").getAsJsonArray("content").map { it.asJsonObject }.filter { it["type"].asString == "tool_use" }

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

        private fun time(o: JsonObject): Instant = Instant.parse(checkNotNull(o["timestamp"]) { "line without timestamp: $o" }.asString)

        /** The text of the first `<name>` element of a notification. */
        private fun tag(content: String, name: String) = Regex("<$name>([\\s\\S]*?)</$name>").find(content)?.groupValues?.get(1)

        /** The `<usage>` of an agent's or workflow's final notification; only a workflow's counts agents. */
        private fun usage(xml: String): BackgroundTask.Usage {
            fun number(name: String) = checkNotNull(tag(xml, name)) { "usage without $name: $xml" }.toLong()
            val agents = tag(xml, "agent_count")?.let { BackgroundTask.Agents(it.toInt(), number("agents_done").toInt(), number("agents_error").toInt()) }
            return BackgroundTask.Usage(number("subagent_tokens"), number("tool_uses").toInt(), agents)
        }

        /** How `claude` writes a skill the human ran; its expansion follows as a meta line. Built-in commands carry no message tag. */
        private const val SKILL_COMMAND = "<command-message>"
        private val COMMAND_NAME = Regex("<command-name>([^<]+)</command-name>")
        private val COMMAND_ARGS = Regex("<command-args>([\\s\\S]*?)</command-args>")

        /** Plain text the human typed, or a skill they ran as they typed it; tool results and harness-injected `<...>` blocks are not prompts. */
        private fun promptText(o: JsonObject): String? {
            val content = o.getAsJsonObject("message")["content"]
            val text = if (content.isJsonPrimitive) content.asString
            else content.asJsonArray.map { it.asJsonObject }.firstOrNull { it["type"].asString == "text" }?.get("text")?.asString
            if (text != null && text.startsWith(SKILL_COMMAND)) {
                val name = checkNotNull(COMMAND_NAME.find(text)) { "skill command without a name: ${text.take(200)}" }.groupValues[1]
                return "$name ${COMMAND_ARGS.find(text)?.groupValues?.get(1).orEmpty()}".trim()
            }
            return text?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
        }
    }
}
