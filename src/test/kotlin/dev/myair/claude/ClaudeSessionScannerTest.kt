package dev.myair.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeSessionScannerTest {
    @TempDir
    lateinit var dir: Path

    private val cwdLine = """"cwd":"E:\\dev\\projects\\rtm-cloud""""

    private fun user(content: String, extra: String = "", at: String = "2026-10-24T10:00:00Z") =
        """{"parentUuid":null,"isSidechain":false,$extra"type":"user","message":{"role":"user","content":$content},$cwdLine,"sessionId":"s1","timestamp":"$at"}"""

    private fun write(name: String, vararg lines: String): Path =
        dir.resolve("E--dev-projects-rtm-cloud").createDirectories().resolve("$name.jsonl").apply { writeText(lines.joinToString("") { it + "\n" }) }

    private fun parse(file: Path) = ClaudeSessionScanner.parse(file)

    @Test
    fun `custom title wins over ai title and prompt, cwd comes from the transcript`() {
        val s = parse(write("s1",
            """{"type":"ai-title","aiTitle":"AI","sessionId":"s1"}""",
            user("\"first prompt\""),
            """{"type":"custom-title","customTitle":"Mine","sessionId":"s1"}""",
        ))!!
        assertEquals("Mine", s.title)
        assertEquals("s1", s.id)
        assertEquals(Path.of("E:\\dev\\projects\\rtm-cloud"), s.cwd)
    }

    @Test
    fun `last ai title wins when there is no custom title`() {
        val s = parse(write("s1",
            """{"type":"ai-title","aiTitle":"Old","sessionId":"s1"}""",
            user("\"prompt\""),
            """{"type":"ai-title","aiTitle":"New","sessionId":"s1"}""",
        ))!!
        assertEquals("New", s.title)
    }

    @Test
    fun `a session Claude Desktop runs is marked, and the folder Desktop started it in is its project, else its cwd`() {
        write("s1", user("\"prompt\""))
        val scanner = ClaudeSessionScanner(dir)
        val main = Path.of("E:\\dev\\projects\\main")
        assertEquals(main to true, scanner.scan(mapOf("s1" to main)).single().let { it.project to it.desktop })
        assertEquals(Path.of("E:\\dev\\projects\\rtm-cloud") to false, scanner.scan().single().let { it.project to it.desktop })
    }

    private fun environment(vararg dirs: String, sidechain: Boolean = false) =
        """{"parentUuid":"p","isSidechain":$sidechain,"attachment":{"type":"environment","snapshot":{"workingDirectory":"E:\\dev\\projects\\rtm-cloud",""" +
            """"additionalWorkingDirectories":[${dirs.joinToString(",") { "\"${it.replace("\\", "\\\\")}\"" }}]}},"type":"attachment",$cwdLine,"sessionId":"s1"}"""

    @Test
    fun `added directories come from the latest environment snapshot of the main conversation, without the cwd`() {
        val file = write("s1", user("\"prompt\""), environment("E:\\dev\\projects\\rtm-cloud", "E:\\plans"))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(listOf(Path.of("E:\\plans")), scanner.scan().single().addedDirs)
        file.appendText(environment("E:\\other", sidechain = true) + "\n")
        assertEquals(listOf(Path.of("E:\\plans")), scanner.scan().single().addedDirs)
        file.appendText(environment() + "\n")
        assertEquals(emptyList(), scanner.scan().single().addedDirs)
    }

    private fun quoted(names: List<String>) = names.joinToString(",") { "\"$it\"" }

    private fun skillListing(initial: Boolean, vararg names: String) =
        """{"parentUuid":"p","isSidechain":false,"attachment":{"type":"skill_listing","content":"- x: y","skillCount":${names.size},""" +
            """"isInitial":$initial,"names":[${quoted(names.toList())}]},"type":"attachment",$cwdLine,"sessionId":"s1"}"""

    private fun toolsDelta(added: List<String>, removed: List<String> = emptyList()) =
        """{"parentUuid":"p","isSidechain":false,"attachment":{"type":"deferred_tools_delta","addedNames":[${quoted(added)}],"addedLines":[],""" +
            """"removedNames":[${quoted(removed)}],"readdedNames":[],"pendingMcpServers":[]},"type":"attachment",$cwdLine,"sessionId":"s1"}"""

    @Test
    fun `skills are the initial listing plus the later ones, and a new initial listing replaces them`() {
        val file = write("s1", user("\"prompt\""), skillListing(true, "tdd", "superpowers:brainstorming"))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(listOf("tdd", "superpowers:brainstorming"), scanner.scan().single().skills)
        file.appendText(skillListing(false, "tracking-work-in-pw", "tdd") + "\n")
        assertEquals(listOf("tdd", "superpowers:brainstorming", "tracking-work-in-pw"), scanner.scan().single().skills)
        file.appendText(skillListing(true, "init") + "\n")
        assertEquals(listOf("init"), scanner.scan().single().skills)
    }

    @Test
    fun `MCP servers are those whose tools are offered now, without those waiting for a sign-in`() {
        val file = write("s1", user("\"prompt\""), toolsDelta(listOf(
            "CronCreate", "mcp__idea__search_text", "mcp__idea__read_file", "mcp__claude_ai_Figma__get_screenshot",
            "mcp__claude_ai_Slack__authenticate", "mcp__claude_ai_Slack__complete_authentication",
        )))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(listOf("claude_ai_Figma", "idea"), scanner.scan().single().mcpServers)
        file.appendText(toolsDelta(listOf("mcp__claude_ai_Slack__send_message"), removed = listOf("mcp__idea__search_text", "mcp__idea__read_file")) + "\n")
        assertEquals(listOf("claude_ai_Figma", "claude_ai_Slack"), scanner.scan().single().mcpServers)
    }

    @Test
    fun `a skill listing without names fails loudly`() {
        val line = skillListing(true, "tdd").replace(""","names":["tdd"]""", "")
        assertFailsWith<IllegalStateException> { parse(write("s1", user("\"prompt\""), line)) }
    }

    private fun reply(model: String, effort: String?, sidechain: Boolean = false) =
        """{"parentUuid":"p","isSidechain":$sidechain,"message":{"model":"$model","type":"message","role":"assistant","content":[]},""" +
            """"type":"assistant",${effort?.let { "\"effort\":\"$it\"," }.orEmpty()}$cwdLine,"sessionId":"s1","timestamp":"2026-10-24T10:01:00Z"}"""

    @Test
    fun `model and effort are the latest reply's of the main conversation, not a subagent's nor claude's own error replies`() {
        val file = write("s1", user("\"prompt\""))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(null to null, scanner.scan().single().let { it.model to it.effort })
        file.appendText(reply("claude-opus-5-5", "high") + "\n" + reply("claude-haiku-5-5", "low", sidechain = true) + "\n")
        assertEquals("claude-opus-5-5" to "high", scanner.scan().single().let { it.model to it.effort })
        file.appendText(reply("<synthetic>", null) + "\n" + reply("claude-sonnet-5-5", "max") + "\n")
        assertEquals("claude-sonnet-5-5" to "max", scanner.scan().single().let { it.model to it.effort })
    }

    private fun published(id: String, title: String, sidechain: Boolean = false, url: String? = "https://claude.ai/artifact/$id") =
        """{"parentUuid":"p","isSidechain":$sidechain,"type":"user","message":{"role":"user","content":[{"tool_use_id":"t","type":"tool_result","content":"Published"}]},""" +
            """"toolUseResult":{${url?.let { "\"url\":\"$it\"," }.orEmpty()}"path":"C:\\x\\$id.html","artifact_id":"$id","title":"$title","updated":false},$cwdLine,"sessionId":"s1","timestamp":"2026-10-24T10:02:00Z"}"""

    @Test
    fun `artifacts are the published ones of the main conversation, one per id with its latest title`() {
        val file = write("s1", user("\"prompt\""), published("a", "Plan"), published("c", "Aside", sidechain = true))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(listOf("a" to "Plan"), scanner.scan().single().artifacts.map { it.id to it.title })
        file.appendText(published("b", "Board") + "\n" + published("a", "Plan v2") + "\n")
        val artifacts = scanner.scan().single().artifacts
        assertEquals(listOf("a" to "Plan v2", "b" to "Board"), artifacts.map { it.id to it.title })
        assertEquals(Artifact("b", "Board", "https://claude.ai/artifact/b", Path.of("C:\\x\\b.html")), artifacts[1])
    }

    private fun call(id: String, name: String, input: String, sidechain: Boolean = false) =
        """{"parentUuid":"p","isSidechain":$sidechain,"message":{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]},"type":"assistant",$cwdLine,"sessionId":"s1","timestamp":"2026-10-24T10:00:00Z"}"""

    private fun result(id: String, toolUseResult: String, sidechain: Boolean = false, content: String = "ok") =
        """{"parentUuid":"p","isSidechain":$sidechain,"type":"user","message":{"role":"user","content":[{"tool_use_id":"$id","type":"tool_result","content":"$content"}]},""" +
            """"toolUseResult":$toolUseResult,$cwdLine,"sessionId":"s1","timestamp":"2026-10-24T10:02:00Z"}"""

    private fun notification(taskId: String, status: String?, summary: String = "done", output: String? = null, at: String = "2026-10-24T10:03:00Z") =
        """{"type":"queue-operation","operation":"enqueue","timestamp":"$at","sessionId":"s1","content":"<task-notification>\n<task-id>$taskId</task-id>\n<tool-use-id>x</tool-use-id>\n""" +
            """${output?.let { "<output-file>$it</output-file>\\n" }.orEmpty()}${status?.let { "<status>$it</status>\\n" }.orEmpty()}<summary>$summary</summary>\n</task-notification>"}"""

    @Test
    fun `background tasks are the launched ones of the main conversation, ended by their final notification`() {
        val started = Instant.parse("2026-10-24T10:02:00Z")
        val file = write("s1", user("\"prompt\""),
            call("t1", "Bash", """{"command":"gradlew runIde","description":"Run the sandbox","run_in_background":true}"""),
            result("t1", """{"stdout":"","stderr":"","backgroundTaskId":"b1"}""",
                content = """Command running in background with ID: b1. Output is being written to: C:\\Temp\\my dir\\tasks\\b1.output. You will be notified when it completes."""),
            call("t2", "Bash", """{"command":"git status"}"""),
            result("t2", """{"stdout":"clean","stderr":""}"""),
            call("t3", "Agent", """{"description":"Review","prompt":"p"}"""),
            result("t3", """{"isAsync":true,"status":"async_launched","agentId":"a1","description":"Final review","prompt":"p","outputFile":"C:\\Temp\\a1.output"}"""),
            call("t4", "Monitor", """{"command":"tail -f log","description":"errors in log","timeout_ms":1000}"""),
            result("t4", """{"taskId":"m1","timeoutMs":1000,"persistent":false}"""),
            call("t5", "Bash", """{"command":"sleep 9","run_in_background":true}""", sidechain = true),
            result("t5", """{"stdout":"","stderr":"","backgroundTaskId":"b2"}""", sidechain = true),
            call("t6", "Workflow", """{"script":"x"}"""),
            result("t6", """{"status":"async_launched","taskId":"w1","taskType":"local_workflow","summary":"Review all"}"""),
        )
        val scanner = ClaudeSessionScanner(dir)
        val b1 = BackgroundTask("b1", BackgroundTask.Kind.SHELL, "Run the sandbox", started, Path.of("C:\\Temp\\my dir\\tasks\\b1.output"))
        val a1 = BackgroundTask("a1", BackgroundTask.Kind.AGENT, "Final review", started, Path.of("C:\\Temp\\a1.output"))
        val m1 = BackgroundTask("m1", BackgroundTask.Kind.MONITOR, "errors in log", started)
        val w1 = BackgroundTask("w1", BackgroundTask.Kind.WORKFLOW, "Review all", started)
        assertEquals(listOf(b1, a1, m1, w1), scanner.scan().single().background)

        // A monitor's event carries no status; a final notification does, whatever it is, and may name the output file.
        file.appendText(listOf(
            notification("m1", null),
            notification("b1", "failed", "Background command \\\"Run the sandbox\\\" failed with exit code 1"),
            notification("w1", "completed", output = "C:\\\\Temp\\\\w1.output", at = "2026-10-24T10:04:00Z"),
            notification("unknown", "completed"),
        ).joinToString("") { it + "\n" })
        val end = { status: BackgroundTask.Status, summary: String, at: String -> BackgroundTask.End(Instant.parse(at), status, summary) }
        assertEquals(
            listOf(
                b1.copy(end = end(BackgroundTask.Status.FAILED, "Background command \"Run the sandbox\" failed with exit code 1", "2026-10-24T10:03:00Z")),
                a1, m1,
                w1.copy(output = Path.of("C:\\Temp\\w1.output"), end = end(BackgroundTask.Status.COMPLETED, "done", "2026-10-24T10:04:00Z")),
            ),
            scanner.scan().single().background,
        )
        // An agent sent another message notifies again; its latest end counts.
        file.appendText(notification("a1", "killed", at = "2026-10-24T10:05:00Z") + "\n" + notification("a1", "completed", at = "2026-10-24T10:09:00Z") + "\n")
        assertEquals(end(BackgroundTask.Status.COMPLETED, "done", "2026-10-24T10:09:00Z"), scanner.scan().single().background[1].end)
    }

    private fun enqueue(content: String, at: String) =
        """{"type":"queue-operation","operation":"enqueue","timestamp":"$at","sessionId":"s1","content":"${content.replace("\n", "\\n")}"}"""

    @Test
    fun `an agent has its model and usage, a workflow its agents, a monitor its events until it ends or expires`() {
        val file = write("s1", user("\"prompt\""),
            call("t1", "Agent", """{"description":"Review","prompt":"p"}"""),
            result("t1", """{"isAsync":true,"status":"async_launched","agentId":"a1","description":"Review","prompt":"p","resolvedModel":"claude-opus-5-5"}"""),
            call("t2", "Workflow", """{"script":"x"}"""),
            result("t2", """{"status":"async_launched","taskId":"w1","taskType":"local_workflow","summary":"Review all"}"""),
            call("t3", "Monitor", """{"command":"tail -f log","description":"errors","timeout_ms":1000}"""),
            result("t3", """{"taskId":"m1","timeoutMs":1000,"persistent":false}"""),
            call("t4", "Monitor", """{"command":"tail -f log","description":"quiet","timeout_ms":1000}"""),
            result("t4", """{"taskId":"m2","timeoutMs":1000,"persistent":false}"""),
            // The agent's result quotes a usage of its own; the notification's comes after it.
            enqueue("<task-notification>\n<task-id>a1</task-id>\n<status>completed</status>\n<summary>Agent finished</summary>\n" +
                "<result>saw <usage><subagent_tokens>1</subagent_tokens><tool_uses>1</tool_uses></usage></result>\n" +
                "<usage><subagent_tokens>154221</subagent_tokens><tool_uses>23</tool_uses><duration_ms>160228</duration_ms></usage>\n</task-notification>", "2026-10-24T10:05:00Z"),
            enqueue("<task-notification>\n<task-id>w1</task-id>\n<status>completed</status>\n<summary>Workflow completed</summary>\n" +
                "<usage><agent_count>11</agent_count><agents_done>10</agents_done><agents_error>1</agents_error><agents_skipped>0</agents_skipped>" +
                "<subagent_tokens>1377311</subagent_tokens><tool_uses>125</tool_uses><duration_ms>131859</duration_ms></usage>\n</task-notification>", "2026-10-24T10:06:00Z"),
            enqueue("<task-notification>\n<task-id>m1</task-id>\n<summary>Monitor event</summary>\n<event>ERROR one</event>\n</task-notification>", "2026-10-24T10:03:00Z"),
            enqueue("<task-notification>\n<task-id>m1</task-id>\n<summary>Monitor event</summary>\n<event>ERROR two</event>\n</task-notification>", "2026-10-24T10:04:00Z"),
            enqueue("<task-notification>\n<task-id>m2</task-id>\n<summary>Monitor event</summary>\n" +
                "<event>[Monitor expired after 15m with no events delivered.]</event>\n</task-notification>", "2026-10-24T10:17:00Z"),
        )
        val scanner = ClaudeSessionScanner(dir)
        val (a1, w1, m1, m2) = scanner.scan().single().background
        assertEquals("claude-opus-5-5" to BackgroundTask.Usage(154221, 23, null), a1.model to a1.usage)
        assertEquals(BackgroundTask.Usage(1377311, 125, BackgroundTask.Agents(11, 10, 1)), w1.usage)
        assertEquals(Triple(2, Instant.parse("2026-10-24T10:04:00Z"), null), Triple(m1.events, m1.lastEvent, m1.end))
        assertEquals(0 to BackgroundTask.Status.EXPIRED, m2.events to m2.end?.status)

        // A monitor's stream ends with a final notification that may carry its last events.
        file.appendText(enqueue("<task-notification>\n<task-id>m1</task-id>\n<status>completed</status>\n<summary>stream ended</summary>\n" +
            "<event>ERROR three</event>\n</task-notification>", "2026-10-24T10:08:00Z") + "\n")
        val ended = scanner.scan().single().background[2]
        assertEquals(Triple(3, Instant.parse("2026-10-24T10:08:00Z"), BackgroundTask.Status.COMPLETED), Triple(ended.events, ended.lastEvent, ended.end?.status))
    }

    @Test
    fun `a final notification with an unknown status fails loudly`() {
        val file = write("s1", user("\"prompt\""),
            call("t1", "Bash", """{"command":"sleep 9","description":"Sleep","run_in_background":true}"""),
            result("t1", """{"stdout":"","stderr":"","backgroundTaskId":"b1"}"""),
            notification("b1", "vanished"),
        )
        assertFailsWith<IllegalArgumentException> { parse(file) }
    }

    @Test
    fun `a background shell without a description is named by its command`() {
        val file = write("s1", user("\"prompt\""),
            call("t1", "PowerShell", """{"command":"Start-Sleep 60\nGet-Date","run_in_background":true}"""),
            result("t1", """{"stdout":"","stderr":"","backgroundTaskId":"b1"}"""),
        )
        assertEquals(listOf("Start-Sleep 60"), parse(file)!!.background.map { it.description })
    }

    @Test
    fun `an artifact result without a url fails`() {
        val file = write("s1", user("\"prompt\""), published("a", "Plan", url = null))
        assertFailsWith<IllegalStateException> { parse(file) }
    }

    @Test
    fun `first human prompt is the title, skipping meta, harness blocks and tool results`() {
        val s = parse(write("s1",
            user("\"<command-name>/foo</command-name>\""),
            user("\"injected\"", """"isMeta":true,"""),
            user("""[{"type":"tool_result","tool_use_id":"x","content":"out"}]"""),
            user("""[{"type":"text","text":"Real question\nsecond line"}]"""),
        ))!!
        assertEquals("Real question", s.title)
    }

    @Test
    fun `a skill the human ran is a prompt as they typed it, its expansion is not`() {
        val s = parse(write("s1",
            user("\"<command-message>grill-me</command-message>\\n<command-name>/grill-me</command-name>\\n<command-args>the diff</command-args>\""),
            user("""[{"type":"text","text":"Base directory for this skill"}]""", """"isMeta":true,"""),
            user("\"<command-message>show-me</command-message>\\n<command-name>/show-me</command-name>\""),
        ))!!
        assertEquals("/grill-me the diff", s.title)
        assertEquals(listOf("/grill-me the diff", "/show-me"), s.prompts)
    }

    @Test
    fun `the first three human prompts are kept, the same kind the title comes from`() {
        val s = parse(write("s1",
            user("\"one\""),
            user("\"<system-reminder>x</system-reminder>\""),
            user("\"two\""),
            user("""[{"type":"tool_result","tool_use_id":"x","content":"out"}]"""),
            user("\"three\""),
            user("\"four\""),
        ))!!
        assertEquals(listOf("one", "two", "three"), s.prompts)
    }

    @Test
    fun `transcript without title or prompt is skipped`() {
        assertNull(parse(write("s1", """{"type":"mode","mode":"normal","sessionId":"s1"}""", user("\"<local-command-stdout/>\""))))
    }

    @Test
    fun `sidechain messages are not prompts`() {
        assertNull(parse(write("s1", user("\"sub\"", """"isSidechain":true,""").replace(""""isSidechain":false,""", ""))))
    }

    @Test
    fun `titled transcript without user message takes cwd from a system line`() {
        val s = parse(write("s1",
            """{"parentUuid":null,"isSidechain":false,"type":"system","subtype":"local_command","content":"/plugin",$cwdLine,"timestamp":"2026-10-24T09:00:00Z"}""",
            """{"type":"custom-title","customTitle":"T","sessionId":"s1"}""",
        ))!!
        assertEquals("T", s.title)
        assertEquals(Path.of("E:\\dev\\projects\\rtm-cloud"), s.cwd)
        assertEquals(Instant.parse("2026-10-24T09:00:00Z"), s.lastMessage)
    }

    @Test
    fun `titled transcript without any cwd fails loudly`() {
        assertFailsWith<IllegalStateException> { parse(write("s1", """{"type":"ai-title","aiTitle":"T","sessionId":"s1"}""")) }
    }

    @Test
    fun `the date is the last message's, not the lines a resume or the harness appends`() {
        val s = parse(write("s1",
            user("\"prompt\"", at = "2026-10-20T08:00:00Z"),
            """{"parentUuid":"p","isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[]},$cwdLine,"timestamp":"2026-10-20T08:01:00Z"}""",
            """{"type":"attachment","attachment":{"type":"user","timestamp":"2026-10-24T12:00:00Z"},"timestamp":"2026-10-24T12:00:00Z"}""",
            """{"type":"queue-operation","operation":"enqueue","timestamp":"2026-10-24T12:00:01Z"}""",
            user("\"<local-command-caveat>x</local-command-caveat>\"", """"isMeta":true,""", at = "2026-10-24T12:00:02Z"),
            user("\"sub\"", at = "2026-10-24T12:00:03Z").replace(""""isSidechain":false""", """"isSidechain":true"""),
            """{"type":"system","subtype":"turn_duration","durationMs":5,"timestamp":"2026-10-24T12:00:04Z"}""",
            """{"type":"last-prompt","lastPrompt":"prompt","sessionId":"s1"}""",
            """{"type":"custom-title","customTitle":"T","sessionId":"s1"}""",
        ))!!
        assertEquals(Instant.parse("2026-10-20T08:01:00Z"), s.lastMessage)
    }

    @Test
    fun `a message without a timestamp fails loudly`() {
        assertFailsWith<IllegalStateException> { parse(write("s1", user("\"prompt\"").replace(""","timestamp":"2026-10-24T10:00:00Z"""", ""))) }
    }

    @Test
    fun `filter matches every word against title or project path, ignoring case`() {
        val s = ClaudeSession("s1", Path.of("E:\\dev\\projects\\rtm-cloud"), "Rebase na branch release", Instant.EPOCH, emptyList(), emptyList())
        assertTrue(s.matches(""))
        assertTrue(s.matches("REBASE"))
        assertTrue(s.matches("rtm-cloud"))
        assertTrue(s.matches("rebase  rtm"))
        assertFalse(s.matches("rebase testflo"))
        assertFalse(s.matches("merge"))
    }

    @Test
    fun `a session is within some days while its last message is less than that many days old`() {
        val now = Instant.parse("2026-10-25T12:00:00Z")
        fun at(time: String) = ClaudeSession("s1", Path.of("/p"), "t", Instant.parse(time), emptyList(), emptyList())
        assertTrue(at("2026-10-22T12:00:01Z").isWithin(3, now))
        assertFalse(at("2026-10-22T12:00:00Z").isWithin(3, now))
        assertTrue(at("2026-10-22T12:00:00Z").isWithin(7, now))
        assertFalse(at("2026-09-25T12:00:00Z").isWithin(30, now))
    }

    @Test
    fun `scan lists top-level transcripts only, latest message first, and picks up appended lines`() {
        val a = write("a", user("\"A\"", at = "2026-10-24T10:00:00Z"))
        val b = write("b", user("\"B\"", at = "2026-10-24T11:00:00Z"))
        dir.resolve("E--dev-projects-rtm-cloud/a/subagents").createDirectories().resolve("agent-x.jsonl").writeText(user("\"sub\"") + "\n")
        Files.setLastModifiedTime(a, FileTime.fromMillis(2000))
        Files.setLastModifiedTime(b, FileTime.fromMillis(1000))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(listOf("B", "A"), scanner.scan().map { it.title })

        a.appendText("""{"type":"custom-title","customTitle":"A2","sessionId":"a"}""" + "\n")
        assertEquals(listOf("B", "A2"), scanner.scan().map { it.title })
        a.appendText(user("\"again\"", at = "2026-10-24T12:00:00Z") + "\n")
        assertEquals(listOf("A2", "B"), scanner.scan().map { it.title })
    }

    private fun assistant(vararg toolUses: String) =
        """{"parentUuid":"p","isSidechain":false,"message":{"role":"assistant","content":[${toolUses.joinToString(",")}]},"type":"assistant",$cwdLine,"sessionId":"s1","timestamp":"2026-10-24T10:00:00Z"}"""

    private fun toolUse(name: String, input: String) = """{"type":"tool_use","id":"t","name":"$name","input":$input}"""

    @Test
    fun `changed files come from edit tool calls, first touch first, each once, across incremental reads`() {
        val file = write("s1",
            user("\"prompt\""),
            // Tool definitions in request metadata name the same tools and must not count.
            """{"type":"api-request-shape","sessionId":"s1","tools":[{"name":"Edit","input":{}}]}""",
            assistant(toolUse("Read", """{"file_path":"C:\\r.kt"}"""), toolUse("Edit", """{"file_path":"C:\\a.kt","old_string":"x"}""")),
            assistant(toolUse("Write", """{"file_path":"C:\\b.kt","content":"y"}""")),
        )
        val first = ClaudeSessionScanner.read(file, ClaudeSessionScanner.Transcript.EMPTY)
        assertEquals(listOf("C:\\a.kt", "C:\\b.kt"), first.changedFiles)

        file.appendText(assistant(toolUse("MultiEdit", """{"file_path":"C:\\a.kt","edits":[]}"""), toolUse("NotebookEdit", """{"notebook_path":"C:\\n.ipynb"}""")) + "\n")
        val next = ClaudeSessionScanner.read(file, first)
        assertEquals(listOf("C:\\a.kt", "C:\\b.kt", "C:\\n.ipynb"), next.changedFiles)
        assertEquals(listOf(Path.of("C:\\a.kt"), Path.of("C:\\b.kt"), Path.of("C:\\n.ipynb")), next.toSession(file)!!.changedFiles)
    }

    private fun briefCall(id: String, goal: String, tasks: String) =
        assistant("""{"type":"tool_use","id":"$id","name":"mcp__idea__my_air_set_brief","input":{"goal":"$goal","tasks":"${tasks.replace("\"", "\\\"")}","projectPath":"E:\\dev"}}""")

    private fun planCall(id: String, input: String) =
        assistant("""{"type":"tool_use","id":"$id","name":"mcp__ccd_turn__update_plan","input":$input}""")

    private fun result(id: String, error: Boolean = false) =
        user("""[{"tool_use_id":"$id","type":"tool_result","content":"ok"${if (error) ""","is_error":true""" else ""}}]""")

    @Test
    fun `the brief is the last call of the brief tool that succeeded`() {
        val s = parse(write("s1",
            user("\"prompt\""),
            briefCall("t1", "Old goal", """[]"""), result("t1"),
            briefCall("t2", "Migrate AGC", """[{"content":"export","status":"completed"},{"content":"map statuses","status":"in_progress"},{"content":"e2e","status":"pending"}]"""),
            result("t2"),
        ))!!
        assertEquals(
            Brief("Migrate AGC", listOf(BriefTask("export", TaskStatus.COMPLETED), BriefTask("map statuses", TaskStatus.IN_PROGRESS), BriefTask("e2e", TaskStatus.PENDING))),
            s.brief,
        )
    }

    @Test
    fun `a call the tool rejected leaves the previous brief`() {
        val s = parse(write("s1",
            user("\"prompt\""),
            briefCall("t1", "Goal", """[]"""), result("t1"),
            briefCall("t2", "Bad", """[{"content":"x","status":"done"}]"""), result("t2", error = true),
        ))!!
        assertEquals(Brief("Goal", emptyList()), s.brief)
    }

    @Test
    fun `parallel calls written before their results each count by their own result`() {
        val s = parse(write("s1",
            user("\"prompt\""),
            briefCall("t1", "Goal", """[]"""),
            briefCall("t2", "Bad", """[]"""),
            result("t1"), result("t2", error = true),
        ))!!
        assertEquals(Brief("Goal", emptyList()), s.brief)
    }

    @Test
    fun `a call counts once its result is read, also in a later incremental read`() {
        val file = write("s1", user("\"prompt\""), briefCall("t1", "Goal", """[]"""))
        val first = ClaudeSessionScanner.read(file, ClaudeSessionScanner.Transcript.EMPTY)
        assertNull(first.toSession(file)!!.brief)

        file.appendText(result("t1") + "\n")
        assertEquals(Brief("Goal", emptyList()), ClaudeSessionScanner.read(file, first).toSession(file)!!.brief)
    }

    @Test
    fun `a Claude Desktop plan gives tasks without a goal, and the later of plan and brief wins`() {
        val file = write("s1",
            user("\"prompt\""),
            briefCall("t1", "Goal", """[]"""), result("t1"),
            planCall("t2", """{"items":[{"content":"run e2e","status":"in_progress"},{"content":"analyse","status":"pending"}],"scope":"continuation"}"""), result("t2"),
        )
        assertEquals(Brief(null, listOf(BriefTask("run e2e", TaskStatus.IN_PROGRESS), BriefTask("analyse", TaskStatus.PENDING))), parse(file)!!.brief)
    }

    @Test
    fun `a plan moves on by advance and by numbered done and start steps`() {
        val plan = """{"items":[{"content":"a","status":"in_progress"},{"content":"b","status":"pending"},{"content":"c","status":"pending"},{"content":"d","status":"pending"}]}"""
        val advanced = write("s1", user("\"prompt\""), planCall("t1", plan), result("t1"), planCall("t2", """{"advance":true}"""), result("t2"))
        assertEquals(listOf(TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS, TaskStatus.PENDING, TaskStatus.PENDING), parse(advanced)!!.brief!!.tasks.map { it.status })

        val numbered = write("s2", user("\"prompt\""), planCall("t1", plan), result("t1"), planCall("t2", """{"done":[1,2],"start":4}"""), result("t2"))
        assertEquals(listOf(TaskStatus.COMPLETED, TaskStatus.COMPLETED, TaskStatus.PENDING, TaskStatus.IN_PROGRESS), parse(numbered)!!.brief!!.tasks.map { it.status })
    }

    @Test
    fun `a subagent's brief call is not the session's`() {
        val s = parse(write("s1",
            user("\"prompt\""),
            briefCall("t1", "Sub", """[]""").replace(""""isSidechain":false""", """"isSidechain":true"""), result("t1"),
        ))!!
        assertNull(s.brief)
    }

    @Test
    fun `an accepted call with an unknown task status fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            parse(write("s1", user("\"prompt\""), briefCall("t1", "Goal", """[{"content":"x","status":"done"}]"""), result("t1")))
        }
    }

    @Test
    fun `a line still being written is read only once it is complete`() {
        val file = write("s1", user("\"prompt\""))
        val first = ClaudeSessionScanner.read(file, ClaudeSessionScanner.Transcript.EMPTY)
        file.appendText("""{"type":"custom-title","customTi""")
        val partial = ClaudeSessionScanner.read(file, first)
        assertEquals(first, partial)

        file.appendText("""tle":"Done","sessionId":"s1"}""" + "\n")
        val complete = ClaudeSessionScanner.read(file, partial)
        assertEquals("Done", complete.customTitle)
        assertEquals(Files.size(file), complete.offset)
    }

    @Test
    fun `a scanner of the same build continues from the saved scan, another build reads everything again`() {
        val file = write("s1", user("\"prompt\""), """{"type":"ai-title","aiTitle":"Old","sessionId":"s1"}""")
        val saved = dir.resolve("cache/scan-cache.json")
        // The first scan saves at once.
        assertEquals("Old", ClaudeSessionScanner(dir, ClaudeSessionScanner.Saved(saved, "b1")).scan().single().title)

        // The part already read changes in place, which only a scan that does not trust the saved one sees.
        file.writeText(file.readText().replace("Old", "Bad") + user("\"later\"", at = "2026-10-25T10:00:00Z") + "\n")
        val restarted = ClaudeSessionScanner(dir, ClaudeSessionScanner.Saved(saved, "b1")).scan().single()
        assertEquals("Old" to Instant.parse("2026-10-25T10:00:00Z"), restarted.title to restarted.lastMessage)
        assertEquals("Bad", ClaudeSessionScanner(dir, ClaudeSessionScanner.Saved(saved, "b2")).scan().single().title)
    }

    private fun jar(name: String, vararg entries: Pair<String, String>): Path = dir.resolve(name).also { jar ->
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((entry, content) in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray())
            }
        }
    }

    @Test
    fun `a jar's cache keeps its build while the transcript-reading classes stay, and each jar has its own file`() {
        val scanner = "dev/myair/claude/ClaudeSessionScanner.class" to "v1"
        val first = ClaudeSessionScanner.Saved.forJar(dir, jar("a.jar", scanner, "dev/myair/toolwindow/Panel.class" to "x"))
        val rebuilt = ClaudeSessionScanner.Saved.forJar(dir, jar("a.jar", "dev/myair/toolwindow/Panel.class" to "y", scanner))
        val changed = ClaudeSessionScanner.Saved.forJar(dir, jar("a.jar", "dev/myair/claude/ClaudeSessionScanner.class" to "v2"))
        val sandbox = ClaudeSessionScanner.Saved.forJar(dir, jar("b.jar", scanner))
        val file = write("s1", user("\"prompt\""))
        first.write(mapOf(file to ClaudeSessionScanner.read(file, ClaudeSessionScanner.Transcript.EMPTY)))
        assertEquals(setOf(file), rebuilt.load().keys)
        assertEquals(emptySet<Path>(), changed.load().keys)
        assertEquals(emptySet<Path>(), sandbox.load().keys)
        assertEquals(first.file, rebuilt.file)
        assertNotEquals(first.file, sandbox.file)
    }

    @Test
    fun `a jar without transcript-reading classes is a broken build`() {
        assertFailsWith<IllegalStateException> { ClaudeSessionScanner.Saved.forJar(dir, jar("a.jar", "dev/myair/toolwindow/Panel.class" to "x")) }
    }

    @Test
    fun `a saved transcript reads back equal, with calls still waiting for their results`() {
        val file = write("s1", user("\"prompt\""), reply("claude-opus-5-5", "high"), environment("E:\\plans"), published("a", "Plan"),
            assistant(toolUse("Edit", """{"file_path":"C:\\a.kt","old_string":"x"}""")),
            briefCall("t1", "Goal", """[{"content":"x","status":"in_progress"}]"""), result("t1"), briefCall("t2", "Next", """[]"""),
            call("t3", "Bash", """{"command":"gradlew runIde","description":"Run","run_in_background":true}"""),
            result("t3", """{"stdout":"","stderr":"","backgroundTaskId":"b1"}""", content = """Output is being written to: C:\\Temp\\b1.output"""),
            call("t4", "Workflow", """{"script":"x"}"""),
            result("t4", """{"status":"async_launched","taskId":"w1","taskType":"local_workflow","summary":"Review all"}"""),
            enqueue("<task-notification>\n<task-id>w1</task-id>\n<status>completed</status>\n<summary>Workflow completed</summary>\n" +
                "<usage><agent_count>2</agent_count><agents_done>2</agents_done><agents_error>0</agents_error><subagent_tokens>9</subagent_tokens><tool_uses>3</tool_uses></usage>\n</task-notification>", "2026-10-24T10:06:00Z"),
            call("t5", "Monitor", """{"command":"tail -f log","description":"errors","timeout_ms":1000}"""),
        )
        val transcript = ClaudeSessionScanner.read(file, ClaudeSessionScanner.Transcript.EMPTY)
        assertEquals(listOf("t2"), transcript.briefCalls.keys.toList())
        assertEquals(listOf("t5"), transcript.shellCalls.keys.toList())
        assertEquals(listOf("b1", "w1"), transcript.background.keys.toList())
        assertEquals(2, transcript.background.getValue("w1").usage?.agents?.done)

        val saved = ClaudeSessionScanner.Saved(dir.resolve("cache/scan-cache.json"), "b1")
        saved.write(mapOf(file to transcript))
        assertEquals(mapOf(file to transcript), saved.load())
    }

    @Test
    fun `a transcript rewritten shorter is read from the start`() {
        val file = write("s1", user("\"a much longer first prompt\""), """{"type":"ai-title","aiTitle":"Old","sessionId":"s1"}""")
        val scanner = ClaudeSessionScanner(dir)
        assertEquals("Old", scanner.scan().single().title)

        file.writeText(user("\"short\"") + "\n")
        assertEquals("short", scanner.scan().single().title)
    }
}
