package dev.myair.service

import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
}
