package dev.myair.schedule

import dev.myair.store.Preset
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlotsTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val weekdays = Schedule(DayOfWeek.entries.take(5).toSet(), LocalTime.of(8, 0))
    private val task = ScheduledTask("t", Preset("T", prompt = "p"), weekdays, true, emptyList(), null, Instant.EPOCH)
    private val nothingClaimed: (ZonedDateTime) -> Boolean = { false }

    // 2026-11-16 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 11, day, hour, minute), zone)

    @Test
    fun `the latest slot of Tuesday 07-00 is Monday 08-00 and the next is Tuesday 08-00`() {
        assertEquals(at(16, 8), latestSlot(weekdays, at(17, 7)))
        assertEquals(at(17, 8), nextSlot(weekdays, at(17, 7)))
        assertEquals(at(17, 8), latestSlot(weekdays, at(17, 8)))
        assertEquals(at(18, 8), nextSlot(weekdays, at(17, 8)))
    }

    @Test
    fun `a slot runs within the catch-up window`() {
        assertEquals(at(16, 8), dueSlot(task, at(16, 10), nothingClaimed))
        assertEquals(at(16, 8), dueSlot(task, at(16, 20), nothingClaimed))
    }

    @Test
    fun `a slot older than 12 hours does not catch up`() {
        assertNull(dueSlot(task, at(17, 7), nothingClaimed))
        assertNull(dueSlot(task, at(16, 20, 1), nothingClaimed))
        // Monday 07:00 after a weekend: Friday 08:00 is long past.
        assertNull(dueSlot(task, at(23, 7), nothingClaimed))
    }

    @Test
    fun `a disabled task, a claimed slot and a slot before since never run`() {
        assertNull(dueSlot(task.copy(enabled = false), at(16, 9), nothingClaimed))
        assertNull(dueSlot(task, at(16, 9)) { it == at(16, 8) })
        assertNull(dueSlot(task.copy(since = at(16, 8, 1).toInstant()), at(16, 9), nothingClaimed))
    }

    @Test
    fun `editing the time to earlier today does not fire today's passed slot`() {
        val now = at(16, 9)
        val earlier = edited(task.copy(schedule = Schedule(weekdays.days, LocalTime.of(10, 0))), task, now.toInstant())
        assertNull(dueSlot(earlier, now, nothingClaimed))
        assertEquals(at(17, 8), dueSlot(earlier, at(17, 8), nothingClaimed))
    }

    @Test
    fun `a slot in the spring-forward gap runs once at the resolved instant`() {
        val sunday = Schedule(setOf(DayOfWeek.SUNDAY), LocalTime.of(2, 30))
        val gap = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 3, 30), zone)
        assertEquals(gap, latestSlot(sunday, gap.plusMinutes(5)))
        assertEquals(gap, nextSlot(sunday, gap.minusHours(2)))
    }
}
