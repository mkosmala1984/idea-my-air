package dev.myair.schedule.ui

import dev.myair.schedule.RunRecord
import dev.myair.schedule.RunState
import dev.myair.schedule.Schedule
import dev.myair.schedule.ScheduledTask
import dev.myair.schedule.nextSlot
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WHEN = DateTimeFormatter.ofPattern("EEE dd.MM HH:mm", Locale.ENGLISH)

private fun short(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** "daily 07:30", "Mon–Fri 08:00" for three or more days in a row, else "Mon, Wed 09:00". */
fun scheduleText(schedule: Schedule): String {
    val days = schedule.days.sorted()
    val consecutive = days.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
    val text = when {
        days.size == 7 -> "daily"
        days.size >= 3 && consecutive -> "${short(days.first())}–${short(days.last())}"
        else -> days.joinToString(", ", transform = ::short)
    }
    return "$text ${schedule.time}"
}

/** A task's line in the registry tab: [failed] when its newest run that was not skipped failed. */
data class TaskRow(val name: String, val schedule: String, val next: String, val last: String?, val failed: Boolean)

/** [task]'s row at [now], [records] its runs newest first. */
fun taskRow(task: ScheduledTask, records: List<RunRecord>, now: ZonedDateTime): TaskRow {
    val decisive = records.firstOrNull { it.state != RunState.SKIPPED }
    val last = records.firstOrNull()?.let { runText(it, now) }
    val next = if (task.enabled) WHEN.format(nextSlot(task.schedule, now)) else "disabled"
    return TaskRow(task.name, scheduleText(task.schedule), next, last, decisive?.state == RunState.FAILED)
}

/** A run as the registry shows it: its state and start, or why it failed or was skipped. */
fun runText(record: RunRecord, now: ZonedDateTime): String = when (record.state) {
    RunState.FAILED -> "failed: ${record.error}"
    RunState.SKIPPED -> "skipped: ${record.error}"
    RunState.STARTING -> "starting ${at(record.startedAt, now)}"
    RunState.STARTED -> "started ${at(record.startedAt, now)}"
}

private fun at(instant: Instant, now: ZonedDateTime) = WHEN.format(instant.atZone(now.zone))
