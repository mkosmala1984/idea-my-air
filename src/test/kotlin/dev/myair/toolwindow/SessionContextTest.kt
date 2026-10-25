package dev.myair.toolwindow

import dev.myair.claude.BackgroundTask
import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class SessionContextTest {
    private val main = Path.of("E:/dev/projects/my-air")

    private fun session(cwd: Path, changedFiles: List<Path> = emptyList()) =
        ClaudeSession("s1", cwd, "title", Instant.EPOCH, changedFiles, emptyList(), project = main)

    @Test
    fun `a session in a linked worktree of its project is marked as one`() {
        val context = contextOf(session(main.resolve(".claude/worktrees/x")), cwdExists = true, live = false, git = null)
        assertEquals("my-air" to true, context.project to context.worktree)
        assertEquals(false, contextOf(session(main), cwdExists = true, live = false, git = null).worktree)
    }

    @Test
    fun `outputs are the markdown and html files written, the newest first`() {
        val plan = main.resolve("docs/superpowers/plans/p.md")
        val description = Path.of("C:/tmp/scratch/pr-description.MD")
        val page = Path.of("C:/tmp/scratch/board.html")
        val written = listOf(plan, main.resolve("src/A.kt"), page, description)
        assertEquals(listOf(description, page, plan), contextOf(session(main, written), cwdExists = true, live = false, git = null).outputs)
    }

    @Test
    fun `background tasks show only while the session has a process, as they end with it`() {
        val task = BackgroundTask("b1", BackgroundTask.Kind.SHELL, "Run the sandbox")
        val session = session(main).copy(background = listOf(task))
        assertEquals(listOf(task), contextOf(session, cwdExists = true, live = true, git = null).background)
        assertEquals(emptyList(), contextOf(session, cwdExists = true, live = false, git = null).background)
    }
}
