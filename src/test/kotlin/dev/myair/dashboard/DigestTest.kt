package dev.myair.dashboard

import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.TaskStatus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigestTest {
    private fun user(text: String, sidechain: Boolean = false, meta: Boolean = false) =
        """{"isSidechain":$sidechain,${if (meta) "\"isMeta\":true," else ""}"type":"user","message":{"role":"user","content":"$text"}}"""

    private fun assistant(vararg items: String, sidechain: Boolean = false) =
        """{"isSidechain":$sidechain,"type":"assistant","message":{"role":"assistant","content":[${items.joinToString(",")}]}}"""

    private fun text(t: String) = """{"type":"text","text":"$t"}"""

    private val toolUse = """{"type":"tool_use","id":"t","name":"Read","input":{}}"""

    private val session = ClaudeSession(
        "s", Path.of("/work/proj"), "Websocket authz", Instant.parse("2026-11-13T10:00:00Z"),
        listOf(Path.of("/work/proj/A.kt")), emptyList(),
        brief = Brief("Secure the handshake", listOf(BriefTask("write the check", TaskStatus.COMPLETED), BriefTask("add tests", TaskStatus.PENDING))),
    )

    @Test
    fun `excerpt keeps human prompts and the last five agent texts, leaving out sidechains, meta lines and tool-only replies`() {
        val lines = listOf(user("first"), user("aside", sidechain = true), user("injected", meta = true)) +
            (1..7).map { assistant(text("reply $it")) } +
            listOf(assistant(toolUse), assistant(text("side reply"), sidechain = true), user("second"))
        val excerpt = excerptOf(lines.asSequence())
        assertEquals(listOf("first", "second"), excerpt.prompts)
        assertEquals((3..7).map { "reply $it" }, excerpt.lastTexts)
    }

    @Test
    fun `digest names title, brief, signals, files, prompts and agent messages in order`() {
        val digest = digestOf(session, listOf(Chip.BriefTasks(1, 2), Chip.Uncommitted), "T12 in progress", Excerpt(listOf("do it"), listOf("done half")))
        val headers = listOf("Title:", "Goal:", "Brief tasks:", "Plan task:", "Signals:", "Files written:", "Human prompts:", "Last agent messages:")
        val positions = headers.map { digest.indexOf(it) }
        assertTrue(positions.all { it >= 0 } && positions == positions.sorted(), digest)
        assertTrue("brief 1/2, uncommitted" in digest, digest)
        assertTrue("[pending] add tests" in digest, digest)
    }

    @Test
    fun `long prompts are cut and the oldest dropped to fit forty thousand characters`() {
        val prompts = (1..30).map { "$it:" + "x".repeat(3000) }
        val digest = digestOf(session, emptyList(), null, Excerpt(prompts, emptyList()))
        assertTrue(digest.length <= 40_000, "length ${digest.length}")
        assertTrue("earlier prompts left out" in digest)
        assertTrue("30:" + "x".repeat(1996) + "…" in digest)
        assertTrue("x".repeat(2001) !in digest)
    }

    @Test
    fun `a session without agent messages still gets a digest`() {
        val digest = digestOf(session.copy(brief = null), emptyList(), null, Excerpt(listOf("hi"), emptyList()))
        assertTrue("Last agent messages:\n(none)" in digest, digest)
        assertTrue("Signals: none" in digest, digest)
        assertTrue("Brief tasks:" !in digest && "Plan task:" !in digest, digest)
    }
}
