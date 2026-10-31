package dev.myair.editor

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabTitleTest {
    private val ide = Path.of("E:/dev/projects/my-air")

    @Test
    fun `a session of the open project or a folder below it is not of another project`() {
        assertFalse(isOtherProject(ide, ide))
        assertFalse(isOtherProject(ide.resolve("taskbar"), ide))
    }

    @Test
    fun `a session of another project is, even one whose path starts with the same text`() {
        assertTrue(isOtherProject(Path.of("E:/dev/projects/rtm-cloud"), ide))
        assertTrue(isOtherProject(Path.of("E:/dev/projects/my-air-2"), ide))
    }

    @Test
    fun `the tooltip names the project, and the worktree only when the session runs outside the project directory`() {
        assertEquals(listOf("Fix", "Project: $ide"), tabTooltip("Fix", ide, ide))
        val worktree = ide.resolve(".claude/worktrees/main-3fb8")
        assertEquals(listOf("Fix", "Project: $ide", "Worktree: $worktree"), tabTooltip("Fix", worktree, ide))
    }
}
