package dev.myair.planview

import dev.myair.store.PlanManager
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlanFilesTest {
    private val cwd = Path.of("E:/dev/a")

    @Test fun `a plan without a manager gets a new session`() =
        assertEquals(PlanManager("n", cwd, cwd) to listOf("--session-id", "n"), managerStart(null, "n", cwd, cwd) { true })

    @Test fun `a manager with a transcript resumes in its stored directory`() {
        val m = PlanManager("s", Path.of("E:/dev/b"), Path.of("E:/dev/b"))
        assertEquals(m to listOf("--resume", "s"), managerStart(m, "n", cwd, cwd) { true })
    }

    @Test fun `a manager closed before its first message starts again under its id`() {
        val m = PlanManager("s", cwd, cwd)
        assertEquals(m to listOf("--session-id", "s"), managerStart(m, "n", cwd, cwd) { false })
    }

    @Test fun `a plan tab path is the folder id`() {
        assertEquals("3f2a9c1e-0000-4000-8000-000000000001", parsePlanTabPath("3f2a9c1e-0000-4000-8000-000000000001"))
        for (bad in listOf("", "a/b", "a?b", "a&b")) assertThrows<IllegalArgumentException>(bad) { parsePlanTabPath(bad) }
    }
}
