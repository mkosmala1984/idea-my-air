package dev.myair.editor

import dev.myair.claude.BackgroundTask
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class CloseConfirmationTest {
    @Test
    fun `a session working or waiting for the user is interrupted`() {
        assertTrue(interrupts(SessionStatus.WORKING, 0))
        assertTrue(interrupts(SessionStatus.NEEDS_INPUT, 0))
    }

    @Test
    fun `an idle session is interrupted only while a task of its own runs`() {
        assertFalse(interrupts(SessionStatus.IDLE, 0))
        assertTrue(interrupts(SessionStatus.IDLE, 1))
    }

    @Test
    fun `a process starting or ended is not interrupted, even with a task the transcript shows open`() {
        val open = BackgroundTask("b1", BackgroundTask.Kind.SHELL, "build", Instant.parse("2026-11-07T10:00:00Z"))
        // No pid or no process: no registry entry and no start time.
        assertFalse(interrupts(null, runningTasks(listOf(open), null)))
    }

    @Test
    fun `one session is named in the message`() {
        assertEquals(
            Confirmation(
                "Close Project with Running Claude Session?",
                "'Fix login' is still running. Closing the project ends it.",
                "Close Project", "Keep Open",
            ),
            confirmation(Closing.PROJECT, listOf("Fix login")),
        )
    }

    @Test
    fun `several sessions are listed one per line`() {
        assertEquals(
            Confirmation(
                "Exit IDE with Running Claude Sessions?",
                "2 Claude sessions are still running:\n\nFix login\nAdd export\n\nExiting ends them.",
                "End Sessions and Exit", "Keep IDE Open",
            ),
            confirmation(Closing.EXIT, listOf("Fix login", "Add export")),
        )
    }

    @Test
    fun `a restart says so in the title, the message and the button`() {
        assertEquals(
            Confirmation(
                "Restart IDE with Running Claude Session?",
                "'Fix login' is still running. Restarting ends it.",
                "End Sessions and Restart", "Keep IDE Open",
            ),
            confirmation(Closing.RESTART, listOf("Fix login")),
        )
    }

    @Test
    fun `nothing to ask about is a bug of the caller`() {
        assertThrows<IllegalArgumentException> { confirmation(Closing.EXIT, emptyList()) }
    }
}
