package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class TaskbarBoardTest {
    /** Newest first, as the scanner returns them. */
    private val sessions = (1..4).map { ClaudeSession("s$it", Path.of("/work/p$it"), "title s$it", Instant.ofEpochMilli(100L - it), emptyList(), emptyList()) }

    private fun live(status: SessionStatus, waitingFor: String? = null) = LiveSession(1, "cli", status, waitingFor)

    @Test
    fun `lists running sessions only, waiting for the user first, then idle, then working, newest first within a group`() {
        val live = mapOf(
            "s1" to live(SessionStatus.WORKING),
            "s2" to live(SessionStatus.NEEDS_INPUT),
            "s3" to live(SessionStatus.IDLE),
            "s4" to live(SessionStatus.NEEDS_INPUT),
        )
        assertEquals(listOf("s2", "s4", "s3", "s1"), boardSessions(sessions, live, emptyMap()).map { it.id })
    }

    @Test
    fun `a process whose transcript is not scanned yet is shown as a new session without a project, last in its group`() {
        val board = boardSessions(sessions, mapOf("fresh" to live(SessionStatus.NEEDS_INPUT, "dialog open"), "s3" to live(SessionStatus.NEEDS_INPUT)), emptyMap())
        assertEquals(
            listOf(
                BoardSession("s3", "title s3", "p3", SessionStatus.NEEDS_INPUT, null, 97, false),
                BoardSession("fresh", NEW_SESSION_TITLE, null, SessionStatus.NEEDS_INPUT, "dialog open", null, false),
            ),
            board,
        )
    }

    @Test
    fun `an idle session is unread while its last message is newer than when the user saw it, never when not seen yet`() {
        // s1, s2, s3 have their last message at 99, 98, 97.
        val live = mapOf("s1" to live(SessionStatus.IDLE), "s2" to live(SessionStatus.IDLE), "s3" to live(SessionStatus.IDLE), "s4" to live(SessionStatus.WORKING))
        val seen = mapOf("s1" to 50L, "s2" to 98L, "s4" to 0L)
        assertEquals(
            mapOf("s1" to true, "s2" to false, "s3" to false, "s4" to false),
            boardSessions(sessions, live, seen).associate { it.id to it.unread },
        )
    }

    /** The same text is parsed by BoardTest in taskbar/tests, which keeps both sides of the file format in step. */
    @Test
    fun `json names statuses by enum constant and leaves null fields out`() {
        val board = listOf(
            BoardSession("a", "Fix tray", "my-air", SessionStatus.IDLE, null, 1700000000000, true),
            BoardSession("b", "New session", null, SessionStatus.NEEDS_INPUT, "permission", null, false),
        )
        assertEquals(
            """{"sessions":[{"id":"a","title":"Fix tray","project":"my-air","status":"IDLE","lastMessage":1700000000000,"unread":true},""" +
                """{"id":"b","title":"New session","status":"NEEDS_INPUT","waitingFor":"permission","unread":false}],""" +
                """"recent":[{"id":"c","title":"Old work","project":"tulip","lastMessage":1690000000000}]}""",
            boardJson(board, listOf(RecentSession("c", "Old work", "tulip", 1690000000000))),
        )
    }

    @Test
    fun `recent sessions are the newest the other groups do not list, settled ones left out`() {
        val more = sessions + (5..8).map { ClaudeSession("s$it", Path.of("/work/p$it"), "title s$it", Instant.ofEpochMilli(100L - it), emptyList(), emptyList()) }
        // s1 needs input and s2 is unread: both have a group. s3 is idle and read, so it is only recent. s4 is settled.
        val live = mapOf("s1" to live(SessionStatus.NEEDS_INPUT), "s2" to live(SessionStatus.IDLE), "s3" to live(SessionStatus.IDLE))
        val board = boardSessions(more, live, mapOf("s2" to 0L, "s3" to 1000L))
        assertEquals(listOf("s3", "s5", "s6", "s7", "s8"), recentSessions(more, board, setOf("s4")).map { it.id })
    }
}
