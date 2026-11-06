package dev.myair.editor

import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BriefReminderTest {
    @TempDir
    lateinit var dir: Path

    /** Runs the hook as claude does: the hook input as JSON on stdin, the context it adds on stdout. */
    private fun hook(transcript: Path): String {
        val process = ProcessBuilder(briefReminderCommand().split(' ')).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.use { it.write("""{"session_id":"s","transcript_path":${com.google.gson.Gson().toJson(transcript.toString())},"hook_event_name":"UserPromptSubmit","prompt":"zażółć"}""".toByteArray()) }
        val out = process.inputStream.readAllBytes().decodeToString().trim()
        assertEquals(0, process.waitFor())
        return out
    }

    private val toolList = """{"type":"attachment","attachment":{"type":"deferred_tools_record","entries":[{"name":"mcp__idea__my_air_set_brief","description":"Desc"}]}}"""
    private val briefCall = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_01Cef","name":"mcp__idea__my_air_set_brief","input":{"goal":"G"}}]}}"""

    @Test
    fun `a session without a transcript yet is reminded`() {
        assertEquals(BRIEF_REMINDER, hook(dir.resolve("none.jsonl")))
    }

    @Test
    fun `a transcript that only lists the tool is reminded`() {
        assertEquals(BRIEF_REMINDER, hook(dir.resolve("t.jsonl").apply { writeText("$toolList\n") }))
    }

    @Test
    fun `a transcript with a brief call is not reminded`() {
        assertEquals("", hook(dir.resolve("t.jsonl").apply { writeText("$toolList\n$briefCall\n") }))
    }
}
