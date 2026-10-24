package dev.myair

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
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

    private fun user(content: String, extra: String = "") =
        """{"parentUuid":null,"isSidechain":false,$extra"type":"user","message":{"role":"user","content":$content},$cwdLine,"sessionId":"s1"}"""

    private fun write(name: String, vararg lines: String): Path =
        dir.resolve("E--dev-projects-rtm-cloud").createDirectories().resolve("$name.jsonl").apply { writeText(lines.joinToString("\n")) }

    private fun parse(file: Path) = ClaudeSessionScanner.parse(file, FileTime.fromMillis(0))

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
            """{"parentUuid":null,"isSidechain":false,"type":"system","subtype":"local_command","content":"/plugin",$cwdLine}""",
            """{"type":"custom-title","customTitle":"T","sessionId":"s1"}""",
        ))!!
        assertEquals("T", s.title)
        assertEquals(Path.of("E:\\dev\\projects\\rtm-cloud"), s.cwd)
    }

    @Test
    fun `titled transcript without any cwd fails loudly`() {
        assertFailsWith<IllegalStateException> { parse(write("s1", """{"type":"ai-title","aiTitle":"T","sessionId":"s1"}""")) }
    }

    @Test
    fun `filter matches every word against title or project path, ignoring case`() {
        val s = ClaudeSession("s1", Path.of("E:\\dev\\projects\\rtm-cloud"), "Rebase na branch release", FileTime.fromMillis(0))
        assertTrue(s.matches(""))
        assertTrue(s.matches("REBASE"))
        assertTrue(s.matches("rtm-cloud"))
        assertTrue(s.matches("rebase  rtm"))
        assertFalse(s.matches("rebase testflo"))
        assertFalse(s.matches("merge"))
    }

    @Test
    fun `scan lists top-level transcripts only, newest first, and rereads changed files`() {
        val a = write("a", user("\"A\""))
        val b = write("b", user("\"B\""))
        dir.resolve("E--dev-projects-rtm-cloud/a/subagents").createDirectories().resolve("agent-x.jsonl").writeText(user("\"sub\""))
        Files.setLastModifiedTime(a, FileTime.fromMillis(1000))
        Files.setLastModifiedTime(b, FileTime.fromMillis(2000))
        val scanner = ClaudeSessionScanner(dir)
        assertEquals(listOf("B", "A"), scanner.scan().map { it.title })

        a.writeText(user("\"A2\""))
        Files.setLastModifiedTime(a, FileTime.fromMillis(3000))
        assertEquals(listOf("A2", "B"), scanner.scan().map { it.title })
    }
}
