package dev.myair.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `a transcript rewritten shorter is read from the start`() {
        val file = write("s1", user("\"a much longer first prompt\""), """{"type":"ai-title","aiTitle":"Old","sessionId":"s1"}""")
        val scanner = ClaudeSessionScanner(dir)
        assertEquals("Old", scanner.scan().single().title)

        file.writeText(user("\"short\"") + "\n")
        assertEquals("short", scanner.scan().single().title)
    }
}
