package dev.myair.schedule

import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration

/** How long after its slot a run missed while no IDE ran still starts. */
val CATCH_UP: Duration = 12.hours

/** [schedule]'s slots on the days from [now]'s date moved by each of [offsets], in [now]'s zone (a time in a DST gap moves forward). */
private fun slots(schedule: Schedule, now: ZonedDateTime, offsets: IntProgression) = offsets.asSequence()
    .map { now.toLocalDate().plusDays(it.toLong()) }
    .filter { it.dayOfWeek in schedule.days }
    .map { ZonedDateTime.of(it, schedule.time, now.zone) }

/** The latest slot at or before [now]. */
fun latestSlot(schedule: Schedule, now: ZonedDateTime): ZonedDateTime = slots(schedule, now, 0 downTo -7).first { it <= now }

/** The first slot after [now]. */
fun nextSlot(schedule: Schedule, now: ZonedDateTime): ZonedDateTime = slots(schedule, now, 0..7).first { it > now }

/** The slot [task] runs at [now]: its latest, when enabled, not before `since`, at most [CATCH_UP] old and not [claimed]. */
fun dueSlot(task: ScheduledTask, now: ZonedDateTime, claimed: (ZonedDateTime) -> Boolean): ZonedDateTime? {
    if (!task.enabled) return null
    val slot = latestSlot(task.schedule, now)
    return slot.takeIf {
        it.toInstant() >= task.since && java.time.Duration.between(it, now).toKotlinDuration() <= CATCH_UP && !claimed(it)
    }
}
