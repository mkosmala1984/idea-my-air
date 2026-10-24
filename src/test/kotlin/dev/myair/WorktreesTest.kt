package dev.myair

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals

class WorktreesTest {
    private val branches = listOf("main", "a", "b", "c", "origin/main", "origin/x")

    @Test
    fun `without recent bases the default branch comes first and is selected`() {
        assertEquals(BaseChoices(branches, 1, 1, 0), baseChoices(branches, "main", emptyList()))
    }

    @Test
    fun `recent bases follow the default newest first and the newest is selected`() {
        assertEquals(BaseChoices(listOf("main", "b", "origin/x", "a", "c", "origin/main"), 1, 3, 1), baseChoices(branches, "main", listOf("b", "origin/x")))
    }

    @Test
    fun `a recent default branch is not listed twice and stays selected when newest`() {
        assertEquals(BaseChoices(listOf("main", "b", "a", "c", "origin/main", "origin/x"), 1, 2, 0), baseChoices(branches, "main", listOf("main", "b")))
    }

    @Test
    fun `a deleted recent base is not shown and the next one is selected`() {
        assertEquals(BaseChoices(listOf("main", "a", "b", "c", "origin/main", "origin/x"), 1, 2, 1), baseChoices(branches, "main", listOf("gone", "a")))
    }

    @Test
    fun `a default branch missing from the branches throws`() {
        assertThrows<IllegalStateException> { baseChoices(branches, "trunk", emptyList()) }
    }

    @Test
    fun `pushing a base moves it to the front, drops deleted ones and keeps five`() {
        assertEquals(listOf("c", "a", "b", "main", "origin/x"), pushRecent(listOf("a", "gone", "b", "c", "main", "origin/x"), "c", branches))
        assertEquals(listOf("origin/main", "a", "b", "c", "main"), pushRecent(listOf("a", "b", "c", "main", "origin/x"), "origin/main", branches))
    }

    @Test
    fun `the default branch is what origin HEAD points at`() {
        assertEquals("origin/trunk", defaultBranch(listOf("origin"), "origin/trunk", listOf("main")))
        assertThrows<IllegalStateException> { defaultBranch(listOf("origin"), null, listOf("main")) }
    }

    @Test
    fun `without origin the default branch is the one local main or master`() {
        assertEquals("main", defaultBranch(emptyList(), null, listOf("feature", "main")))
        assertEquals("master", defaultBranch(listOf("upstream"), null, listOf("master")))
        assertThrows<IllegalStateException> { defaultBranch(emptyList(), null, listOf("main", "master")) }
        assertThrows<IllegalStateException> { defaultBranch(emptyList(), null, listOf("trunk")) }
    }

    @Test
    fun `a relative worktrees directory resolves against the main checkout, an absolute one is kept`() {
        val root = Path.of("E:/repo")
        assertEquals(Path.of("E:/repo/.claude/worktrees/ab12cd34"), worktreePath(root, ".claude/worktrees", "ab12cd34"))
        assertEquals(Path.of("D:/wt/ab12cd34"), worktreePath(root, "D:/wt", "ab12cd34"))
    }

    @Test
    fun `remote HEAD pointers are not branches`() {
        assertEquals(listOf("main", "feature/x", "origin/main"), parseBranches("refs/heads/main\nrefs/heads/feature/x\nrefs/remotes/origin/HEAD\nrefs/remotes/origin/main\n"))
    }
}
