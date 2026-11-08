package dev.myair.service

import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class SessionProjectsTest {
    @TempDir
    lateinit var root: Path

    private fun session(cwd: Path) = ClaudeSession(cwd.toString(), cwd, "s", Instant.EPOCH, emptyList(), emptyList())

    /** A main checkout at [main] with linked worktrees at [worktrees], as `git worktree add` leaves them. */
    private fun repo(main: Path, vararg worktrees: Path) = main.also {
        worktrees.forEach { worktree ->
            val admin = main.resolve(".git/worktrees/${worktree.name}").createDirectories()
            admin.resolve("gitdir").writeText(worktree.resolve(".git").toString().replace('\\', '/') + "\n")
            worktree.createDirectories().resolve(".git").writeText("gitdir: $admin")
        }
    }

    @Test
    fun `a session in a linked worktree git lists, or below it, belongs to the main checkout, wherever the worktree lies`() {
        val inside = root.resolve("app/.claude/worktrees/a")
        val outside = root.resolve("elsewhere/b")
        val app = repo(root.resolve("app"), inside, outside)
        val cwds = listOf(app, inside, outside.resolve("taskbar"), root.resolve("elsewhere/bb"), root.resolve("plain").createDirectories())
        assertEquals(listOf(app, app, app, root.resolve("elsewhere/bb"), root.resolve("plain")), inProjects(cwds.map(::session)).map { it.project })
    }

    @Test
    fun `a worktree git no longer lists keeps its sessions in their own cwd`() {
        val worktree = root.resolve("elsewhere/b")
        val app = repo(root.resolve("app"), worktree)
        app.resolve(".git/worktrees").toFile().deleteRecursively()
        assertEquals(listOf(app, worktree), inProjects(listOf(session(app), session(worktree))).map { it.project })
    }

    @Test
    fun `an admin directory without gitdir, as a half-removed worktree leaves it, is no worktree`() {
        val worktree = root.resolve("elsewhere/b")
        val app = repo(root.resolve("app"), worktree)
        app.resolve(".git/worktrees/gone").createDirectories().resolve("index").writeText("")
        assertEquals(listOf(app, app), inProjects(listOf(session(app), session(worktree))).map { it.project })
    }

    @Test
    fun `a worktree is dated by when git made its admin directory`() {
        val old = root.resolve("w/old")
        val new = root.resolve("w/new")
        val app = repo(root.resolve("app"), old, new)
        fun created(name: String, at: String) = Instant.parse(at).also {
            Files.getFileAttributeView(app.resolve(".git/worktrees/$name"), BasicFileAttributeView::class.java).setTimes(null, null, FileTime.from(it))
        }
        val oldAt = created("old", "2026-01-01T00:00:00Z")
        val newAt = created("new", "2026-02-01T00:00:00Z")
        assertEquals(setOf(LinkedWorktree(new, newAt), LinkedWorktree(old, oldAt)), gitWorktrees(app).toSet())
    }
}
