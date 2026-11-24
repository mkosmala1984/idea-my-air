package dev.myair.actions

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class BranchSearchTest {
    private val main = Worktree(Path.of("/repo"), "main", false)
    private val linked = Worktree(Path.of("/repo/.claude/worktrees/wt"), "TR-11909-blokery-review", false)
    private val trees = listOf(main, linked)
    private val branches = listOf(
        "main", "TR-11909-blokery-review", "tr-11909-lower", "TR-119090-other", "TR-5-no-tree", "feature/login-form", "origin/TR-5-no-tree",
        "origin/remote-only-thing",
    )

    private fun find(text: String) = findBranches(text, branches, setOf("origin"), trees).map { it.name }

    @Test
    fun `a key matches every branch containing it, ignoring case, and no other`() {
        assertEquals(listOf("TR-11909-blokery-review", "TR-119090-other", "tr-11909-lower"), find("please look at TR-11909 today"))
    }

    @Test
    fun `any of several keys matches`() {
        assertEquals(listOf("TR-11909-blokery-review", "TR-119090-other", "TR-5-no-tree", "tr-11909-lower"), find("TR-11909 and TR-5"))
    }

    @Test
    fun `without a key only branches holding every word of three or more characters match`() {
        assertEquals(listOf("feature/login-form"), find("login form"))
        assertEquals(emptyList(), find("login nothing"))
        assertEquals(listOf("feature/login-form"), find("a login, of form"))
    }

    @Test
    fun `a text without a key or a long word matches nothing`() {
        assertEquals(emptyList(), find("  a b "))
    }

    @Test
    fun `a local and a remote branch of one name are one match, a remote-only one is marked`() {
        val matches = findBranches("TR-5 remote-only-thing", branches, setOf("origin"), trees)
        assertEquals(listOf("TR-5-no-tree"), matches.map { it.name })
        assertEquals(listOf("TR-5-no-tree"), matches.map { it.ref })
        val remoteOnly = findBranches("remote only thing", branches, setOf("origin"), trees).single()
        assertEquals("remote-only-thing", remoteOnly.name)
        assertEquals("origin/remote-only-thing", remoteOnly.ref)
        assertEquals(true, remoteOnly.remoteOnly)
    }

    @Test
    fun `a pasted remote ref is compared by its local name`() {
        assertEquals(listOf("remote-only-thing"), find("origin/remote-only-thing"))
    }

    @Test
    fun `a match carries the worktree holding its branch, the main checkout flagged, and those with one come first`() {
        val matches = findBranches("TR-11909 TR-5 main", branches, setOf("origin"), trees)
        assertEquals(listOf("TR-11909-blokery-review", "TR-119090-other", "TR-5-no-tree", "tr-11909-lower"), matches.map { it.name })
        assertEquals(listOf(linked, null, null, null), matches.map { it.worktree })
        assertEquals(false, matches.first().main)
        val onMain = findBranches("main", branches, setOf("origin"), trees).single()
        assertEquals(main, onMain.worktree)
        assertEquals(true, onMain.main)
    }
}
