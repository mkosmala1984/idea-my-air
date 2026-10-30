package dev.myair.toolwindow

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class StatusDotsTest {
    @Test
    fun `a session without a process keeps the dot's room, so titles line up`() {
        val ended = dotOf(null, unread = true)
        val idle = dotOf(LiveSession(1, "cli", SessionStatus.IDLE, null, Instant.EPOCH), unread = false)
        assertEquals(IDLE_DOT.iconWidth, ended.iconWidth)
        assertNotSame(IDLE_DOT, ended)
        assertEquals(IDLE_DOT, idle)
    }
}
