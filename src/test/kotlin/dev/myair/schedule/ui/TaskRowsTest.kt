package dev.myair.schedule.ui

import dev.myair.schedule.RunRecord
import dev.myair.schedule.RunState
import dev.myair.schedule.Schedule
import dev.myair.schedule.ScheduledTask
import dev.myair.store.Preset
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRowsTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 11, 17, 9, 0), zone) // a Tuesday
    private val task = ScheduledTask("t", Preset("Triage", prompt = "p"), Schedule(DayOfWeek.entries.take(5).toSet(), LocalTime.of(8, 0)), true, emptyList(), null, Instant.EPOCH)

    private fun run(at: LocalDateTime, state: RunState, error: String? = null) =
        ZonedDateTime.of(at, zone).toInstant().let { RunRecord("t", it, false, 1, "s", state, error, true, it) }

    @Test
    fun `names a schedule as daily, a range of days or a list`() {
        assertEquals("daily 07:30", scheduleText(Schedule(DayOfWeek.entries.toSet(), LocalTime.of(7, 30))))
        assertEquals("Mon–Fri 08:00", scheduleText(task.schedule))
        assertEquals("Mon, Wed 09:00", scheduleText(Schedule(setOf(WEDNESDAY, MONDAY), LocalTime.of(9, 0))))
        assertEquals("Mon 09:00", scheduleText(Schedule(setOf(MONDAY), LocalTime.of(9, 0))))
    }

    @Test
    fun `a task with no runs shows its next slot and no last run`() {
        val row = taskRow(task, emptyList(), now)
        assertEquals(TaskRow("Triage", "Mon–Fri 08:00", "Wed 18.11 08:00", null, false), row)
    }

    @Test
    fun `a disabled task says so instead of its next slot`() {
        assertEquals("disabled", taskRow(task.copy(enabled = false), emptyList(), now).next)
    }

    @Test
    fun `the newest run that is not skipped decides whether the task shows failed`() {
        val failed = run(LocalDateTime.of(2026, 11, 17, 8, 0), RunState.FAILED, "directory missing: E:/x")
        val row = taskRow(task, listOf(failed), now)
        assertTrue(row.failed)
        assertEquals("failed: directory missing: E:/x", row.last)
        val skipped = run(LocalDateTime.of(2026, 11, 17, 8, 30), RunState.SKIPPED, "previous run still running")
        assertTrue(taskRow(task, listOf(skipped, failed), now).failed)
        val started = run(LocalDateTime.of(2026, 11, 18, 8, 0), RunState.STARTED)
        val later = taskRow(task, listOf(started, failed), now)
        assertFalse(later.failed)
        assertEquals("started Wed 18.11 08:00", later.last)
        assertNull(taskRow(task, emptyList(), now).last)
    }
}
