package dev.myair.actions

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `a worktree is named after its base, the date and the start of the session id`() {
        val date = LocalDate.of(2026, 10, 28)
        assertEquals("main-2026-10-28-3fb8", worktreeName("main", date, "3fb8816a-812b-4758"))
        assertEquals("origin-feature-login-2026-10-28-3fb8", worktreeName("origin/feature/login", date, "3fb8816a-812b-4758"))
        assertThrows<IllegalStateException> { worktreeName("/", date, "3fb8816a") }
    }

    @Test
    fun `a new branch's worktree directory is the branch reduced to letters, digits and hyphens`() {
        assertEquals("TR-1234-fix-login", dirName("TR-1234-fix-login"))
        assertEquals("feature-TR-1-x", dirName("feature/TR-1_x"))
        assertThrows<IllegalStateException> { dirName("//") }
    }

    @Test
    fun `remote HEAD pointers are not branches`() {
        assertEquals(listOf("main", "feature/x", "origin/main"), parseBranches("refs/heads/main\nrefs/heads/feature/x\nrefs/remotes/origin/HEAD\nrefs/remotes/origin/main\n"))
    }

    @Test
    fun `contained bases keep the order given and leave out the branch, its remote copies and HEAD pointers`() {
        val refs = "refs/heads/x\nrefs/remotes/origin/x\nrefs/heads/main\nrefs/remotes/origin/HEAD\nrefs/remotes/origin/main\nrefs/heads/feature/x\n"
        assertEquals(listOf("main", "origin/main", "feature/x"), containedBases(refs, "x"))
    }

    @Test
    fun `contained bases stop at ten`() {
        val refs = (1..12).joinToString("\n") { "refs/heads/b$it" }
        assertEquals((1..10).map { "b$it" }, containedBases(refs, "x"))
    }

    @Test
    fun `undecided servers of mcp json files in and above the worktree are declined, decided ones kept`(@TempDir dir: Path) {
        dir.resolve(".mcp.json").writeText("""{"old": {"ignored": {}}, "mcpServers": {"above": {}, "on": {}}}""")
        val worktree = dir.resolve("trees/w").createDirectories()
        worktree.resolve(".mcp.json").writeText("""{"mcpServers": {"inside": {}, "off": {}}}""")
        worktree.resolve(".claude").createDirectories().resolve("settings.local.json")
            .writeText("""{"permissions": {"allow": ["Bash(git *)"]}, "enabledMcpjsonServers": ["on"], "disabledMcpjsonServers": ["off"]}""")
        declineProjectMcpServers(worktree)
        val settings = JsonParser.parseString(worktree.resolve(".claude/settings.local.json").readText()).asJsonObject
        assertEquals(listOf("off", "above", "inside"), settings.getAsJsonArray("disabledMcpjsonServers").map { it.asString })
        assertEquals(listOf("on"), settings.getAsJsonArray("enabledMcpjsonServers").map { it.asString })
        assertEquals("Bash(git *)", settings.getAsJsonObject("permissions").getAsJsonArray("allow")[0].asString)
    }

    @Test
    fun `a worktree without settings gets a file declining the servers above it`(@TempDir dir: Path) {
        dir.resolve(".mcp.json").writeText("""{"mcpServers": {"dev": {}}}""")
        val worktree = dir.resolve("w").createDirectories()
        declineProjectMcpServers(worktree)
        assertEquals("""{"disabledMcpjsonServers":["dev"]}""", JsonParser.parseString(worktree.resolve(".claude/settings.local.json").readText()).toString())
    }

    @Test
    fun `without mcp json no settings file is written`(@TempDir dir: Path) {
        declineProjectMcpServers(dir)
        assertFalse(dir.resolve(".claude").exists())
    }
}
