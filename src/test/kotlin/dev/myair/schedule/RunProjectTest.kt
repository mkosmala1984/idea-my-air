package dev.myair.schedule

import dev.myair.store.Preset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunProjectTest {
    @Test
    fun `picks the open project holding the directory, the deepest first, else the first`() {
        val bases = listOf(Path.of("E:/a"), Path.of("E:/dev"), Path.of("E:/dev/rtm"))
        assertEquals(2, pickProject(Path.of("E:/dev/rtm/sub"), bases))
        assertEquals(1, pickProject(Path.of("E:/dev/other"), bases))
        assertEquals(0, pickProject(Path.of("F:/x"), bases))
        assertNull(pickProject(Path.of("F:/x"), emptyList()))
    }

    @Test
    fun `names the first reason a run cannot start`(@TempDir temp: Path) {
        val gone = temp.resolve("gone")
        val task = ScheduledTask("t", Preset("T", prompt = "p", addDirs = listOf(gone.toString())), Schedule(setOf(DayOfWeek.MONDAY), LocalTime.NOON), true, emptyList(), "f1", Instant.EPOCH)
        assertEquals("no IDE project open", preStartError(task, null, folderExists = false, claudeFound = false))
        assertEquals("directory missing: $gone", preStartError(task, temp, folderExists = false, claudeFound = false))
        val plain = task.copy(preset = task.preset.copy(addDirs = emptyList()))
        assertEquals("folder deleted", preStartError(plain, temp, folderExists = false, claudeFound = false))
        assertEquals("claude not found on PATH", preStartError(plain, temp, folderExists = true, claudeFound = false))
        assertNull(preStartError(plain, temp, folderExists = true, claudeFound = true))
        assertNull(preStartError(plain.copy(folder = null), temp, folderExists = false, claudeFound = true))
    }
}
