package dev.myair.editor

import dev.myair.claude.BackgroundTask
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RetirementTest {
    @Test
    fun `an idle session without tasks retires after 60 s detached and not before`() {
        assertFalse(retires(59.seconds, SessionStatus.IDLE, 0))
        assertTrue(retires(60.seconds, SessionStatus.IDLE, 0))
    }

    @Test
    fun `a running task of its own keeps an idle session`() {
        assertFalse(retires(10.minutes, SessionStatus.IDLE, 1))
    }

    @Test
    fun `a working session or one waiting for the user stays`() {
        assertFalse(retires(10.minutes, SessionStatus.WORKING, 0))
        assertFalse(retires(10.minutes, SessionStatus.NEEDS_INPUT, 0))
    }

    @Test
    fun `a process not registered yet stays`() {
        assertFalse(retires(10.minutes, null, 0))
    }

    private val start = Instant.parse("2026-11-07T10:00:00Z")
    private fun task(id: String, started: Instant, ended: Boolean = false) = BackgroundTask(
        id, BackgroundTask.Kind.SHELL, id, started,
        end = if (ended) BackgroundTask.End(started.plusSeconds(5), BackgroundTask.Status.COMPLETED, "done") else null,
    )

    @Test
    fun `only open tasks started by this process count`() {
        val tasks = listOf(
            task("before", start.minusSeconds(1)),
            task("at", start),
            task("after", start.plusSeconds(30)),
            task("ended", start.plusSeconds(10), ended = true),
        )
        assertEquals(2, runningTasks(tasks, start))
    }

    @Test
    fun `a process whose start is unknown runs no task`() {
        assertEquals(0, runningTasks(listOf(task("t", start)), null))
    }

    @Test
    fun `closing a tab detaches a started process when sessions are kept or the tab reopens`() {
        assertEquals(Release.DETACH, releaseOf(owns = true, started = true, ended = false, keep = true, reopening = false, job = false))
        assertEquals(Release.DETACH, releaseOf(owns = true, started = true, ended = false, keep = false, reopening = true, job = false))
        assertEquals(Release.CLOSE, releaseOf(owns = true, started = true, ended = false, keep = false, reopening = false, job = false))
    }

    @Test
    fun `closing a tab ends a process that never started or already ended`() {
        assertEquals(Release.CLOSE, releaseOf(owns = true, started = false, ended = false, keep = true, reopening = true, job = false))
        assertEquals(Release.CLOSE, releaseOf(owns = true, started = true, ended = true, keep = true, reopening = false, job = false))
    }

    @Test
    fun `closing a tab showing a background job ends its view even when sessions are kept, and detaches it only to reopen`() {
        assertEquals(Release.CLOSE, releaseOf(owns = true, started = true, ended = false, keep = true, reopening = false, job = true))
        assertEquals(Release.DETACH, releaseOf(owns = true, started = true, ended = false, keep = true, reopening = true, job = true))
        assertEquals(Release.DETACH, releaseOf(owns = true, started = true, ended = false, keep = false, reopening = true, job = true))
    }

    @Test
    fun `a tab that does not show the process leaves it alone`() {
        assertEquals(Release.IGNORE, releaseOf(owns = false, started = true, ended = false, keep = false, reopening = false, job = false))
    }
}
