package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttentionQueueTest {
    /** s1 has the newest message, s5 the oldest. */
    private val sessions = (1..5).map { ClaudeSession("s$it", Path.of("/p"), "t$it", Instant.ofEpochMilli(100L - it), emptyList(), emptyList()) }

    private fun live(status: SessionStatus, since: Long = 0) = LiveSession(1, "cli", status, null, Instant.ofEpochMilli(since))

    @Test
    fun `sessions waiting for input come first, longest waiting first, then unread replies, oldest first`() {
        val live = mapOf(
            "s1" to live(SessionStatus.NEEDS_INPUT, since = 20),
            "s2" to live(SessionStatus.IDLE),
            "s3" to live(SessionStatus.NEEDS_INPUT, since = 10),
            "s4" to live(SessionStatus.IDLE),
            "s5" to live(SessionStatus.WORKING),
        )
        // s2 and s4 are unread; a session not in live (gone) never is.
        assertEquals(listOf("s3", "s1", "s4", "s2"), attentionQueue(sessions, live, setOf("s2", "s4", "gone")).map { it.id })
    }

    @Test
    fun `the next session is the one after the current, wrapping, else the first`() {
        val queue = listOf("a", "b", "c")
        assertEquals("b", nextInQueue(queue, shown = "a", last = null))
        assertEquals("a", nextInQueue(queue, shown = "c", last = null))
        assertEquals("a", nextInQueue(queue, shown = "x", last = null))
        assertEquals("a", nextInQueue(queue, shown = null, last = null))
        assertNull(nextInQueue(emptyList(), shown = "a", last = "b"))
    }

    @Test
    fun `without a queued session shown, the walk goes on from the one it went to last`() {
        val queue = listOf("a", "b", "c")
        assertEquals("c", nextInQueue(queue, shown = "x", last = "b"))
        assertEquals("b", nextInQueue(queue, shown = "a", last = "b"))
        assertEquals("a", nextInQueue(queue, shown = null, last = "gone"))
    }
}
