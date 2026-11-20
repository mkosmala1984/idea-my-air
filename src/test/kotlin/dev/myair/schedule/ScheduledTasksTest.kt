package dev.myair.schedule

import dev.myair.store.Preset
import dev.myair.store.Variant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalTime
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduledTasksTest {
    private val since = Instant.parse("2026-11-19T07:00:00Z")
    private val full = ScheduledTask(
        "t1", Preset("Triage", "opus", "high", "E:/dev/rtm", addDirs = listOf("E:/docs"), prompt = "triage\nerrors"),
        Schedule(setOf(MONDAY, WEDNESDAY), LocalTime.of(8, 0)), enabled = true,
        allowedTools = listOf("Read", "Bash(git log:*)"), folder = "f7", since = since,
    )
    private val minimal = ScheduledTask("t2", Preset("Retro", prompt = "retro"), Schedule(setOf(FRIDAY), LocalTime.of(7, 30)), false, emptyList(), null, since)

    @Test
    fun `round trips every field through JSON`() {
        assertEquals(listOf(full, minimal), parseScheduledTasks(scheduledTasksJson(listOf(full, minimal))))
    }

    @Test
    fun `rejects an unknown key, another version and a duplicate id`() {
        val json = scheduledTasksJson(listOf(minimal))
        assertThrows<IllegalStateException> { parseScheduledTasks(json.replace("\"name\"", "\"color\": \"red\", \"name\"")) }
        assertThrows<IllegalStateException> { parseScheduledTasks(json.replace("\"version\": 1", "\"version\": 2")) }
        assertThrows<IllegalStateException> { scheduledTasksJson(listOf(minimal, minimal)) }
    }

    @Test
    fun `rejects a task without a prompt, with a worktree, with no days`() {
        val schedule = Schedule(setOf(MONDAY), LocalTime.NOON)
        assertThrows<IllegalArgumentException> { ScheduledTask("a", Preset("A"), schedule, true, emptyList(), null, since) }
        assertThrows<IllegalArgumentException> { ScheduledTask("a", Preset("A", worktree = true, prompt = "p"), schedule, true, emptyList(), null, since) }
        val pairs = listOf(Variant("opus"), Variant("sonnet"))
        assertThrows<IllegalArgumentException> { ScheduledTask("a", Preset("A", prompt = "p", variants = pairs), schedule, true, emptyList(), null, since) }
        assertThrows<IllegalArgumentException> { Schedule(emptySet(), LocalTime.NOON) }
    }

    @Test
    fun `puts allowed tools before the preset flags and the prompt after --`() {
        val task = minimal.copy(preset = Preset("R", "opus", prompt = "triage"), allowedTools = listOf("Read", "Bash(git log:*)"))
        assertEquals(listOf("--session-id", "s1", "--allowedTools", "Read,Bash(git log:*)", "--model", "opus", "--", "triage"), task.claudeArgs("s1"))
        assertEquals(listOf("--session-id", "s1", "--", "retro"), minimal.claudeArgs("s1"))
    }

    @Test
    fun `an edit moves since only when the schedule changes or the task is enabled again`() {
        val now = since.plusSeconds(3600)
        assertEquals(since, edited(full, full.copy(preset = full.preset.copy(prompt = "other")), now).since)
        assertEquals(now, edited(full, full.copy(schedule = Schedule(setOf(MONDAY), LocalTime.of(9, 0))), now).since)
        assertEquals(now, edited(minimal, minimal.copy(enabled = true), now).since)
        assertEquals(since, edited(full, full.copy(enabled = false), now).since)
    }

    @Test
    fun `read returns a failure naming a broken file and an empty list for a missing one`(@TempDir temp: Path) {
        val store = ScheduledTaskStore(temp.resolve("scheduled-tasks.json"))
        assertEquals(emptyList(), store.read().getOrThrow())
        store.write(listOf(full))
        assertEquals(listOf(full), store.read().getOrThrow())
        store.file.writeText("{\"version\": 1, \"tasks\": [{\"id\": 3}]}")
        assertTrue(store.read().isFailure)
    }
}
