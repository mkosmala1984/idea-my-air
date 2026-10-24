package dev.myair

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
        assertEquals(listOf("s2", "s4", "s3", "s1"), boardSessions(sessions, live).map { it.id })
    }

    @Test
    fun `a process whose transcript is not scanned yet is shown as a new session without a project, last in its group`() {
        val board = boardSessions(sessions, mapOf("fresh" to live(SessionStatus.NEEDS_INPUT, "dialog open"), "s3" to live(SessionStatus.NEEDS_INPUT)))
        assertEquals(
            listOf(
                BoardSession("s3", "title s3", "p3", SessionStatus.NEEDS_INPUT, null),
                BoardSession("fresh", ClaudeSessionsService.NEW_SESSION_TITLE, null, SessionStatus.NEEDS_INPUT, "dialog open"),
            ),
            board,
        )
    }

    /** The same text is parsed by BoardTest in taskbar/tests, which keeps both sides of the file format in step. */
    @Test
    fun `json names statuses by enum constant and leaves null fields out`() {
        val board = listOf(
            BoardSession("a", "Fix tray", "my-air", SessionStatus.NEEDS_INPUT, "permission"),
            BoardSession("b", "New session", null, SessionStatus.WORKING, null),
        )
        assertEquals(
            """{"sessions":[{"id":"a","title":"Fix tray","project":"my-air","status":"NEEDS_INPUT","waitingFor":"permission"},""" +
                """{"id":"b","title":"New session","status":"WORKING"}]}""",
            boardJson(board),
        )
    }
}
