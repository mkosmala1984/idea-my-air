package dev.myair.editor

import dev.myair.actions.Git
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

    private val ideHead = Git.Head(ide, "main")
    private val worktree = Git.Head(ide.resolve(".claude/worktrees/feat-3fb8"), "feat")

    @Test
    fun `a session of the window's project in its work tree, a subfolder included, has no mark`() {
        assertEquals(TabMark(false, null), tabMark(ide, ideHead, ideHead, ide, ideHead))
        assertEquals(TabMark(false, null), tabMark(ide.resolve("taskbar"), ideHead, ideHead, ide, ideHead))
    }

    @Test
    fun `a session of the window's project in another work tree is marked with it, detached or not`() {
        assertEquals(TabMark(false, worktree), tabMark(ide, worktree, ideHead, ide, ideHead))
        val detached = worktree.copy(branch = null)
        assertEquals(TabMark(false, detached), tabMark(ide, detached, ideHead, ide, ideHead))
        // The window opened on a worktree marks a session of the main checkout.
        assertEquals(TabMark(false, ideHead), tabMark(ide, ideHead, ideHead, ide, worktree))
    }

    @Test
    fun `outside a repository nothing is marked as a worktree`() {
        assertEquals(TabMark(false, null), tabMark(ide, null, null, ide, ideHead))
        assertEquals(TabMark(false, null), tabMark(ide, worktree, null, ide, null))
    }

    @Test
    fun `a session of another project is marked as such, and with a worktree only when it left its project's work tree`() {
        val other = Path.of("E:/dev/projects/rtm-cloud")
        val otherHead = Git.Head(other, "main")
        assertEquals(TabMark(true, null), tabMark(other, otherHead, otherHead, ide, ideHead))
        assertEquals(TabMark(true, null), tabMark(other.resolve("sub"), otherHead, otherHead, ide, ideHead))
        val otherWorktree = Git.Head(other.resolve(".claude/worktrees/x"), "x")
        assertEquals(TabMark(true, otherWorktree), tabMark(other, otherWorktree, otherHead, ide, ideHead))
    }

    @Test
    fun `the tooltip names the project, and for a worktree where it lies and its branch`() {
        assertEquals(listOf("Fix", "Project: $ide"), tabTooltip("Fix", ide, null))
        assertEquals(listOf("Fix", "Project: $ide", "Worktree: ${worktree.root}", "Branch: feat"), tabTooltip("Fix", ide, worktree))
        assertEquals(listOf("Fix", "Project: $ide", "Worktree: ${worktree.root}", "Branch: detached"), tabTooltip("Fix", ide, worktree.copy(branch = null)))
    }
}
