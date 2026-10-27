package dev.myair.toolwindow

import dev.myair.claude.BackgroundTask
import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.TaskStatus
import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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

    private val started = Instant.parse("2026-10-24T10:02:00Z")

    private fun task(id: String, status: BackgroundTask.Status? = null, endedAt: String = "2026-10-24T10:04:40Z", summary: String = "done") =
        BackgroundTask(id, BackgroundTask.Kind.SHELL, id, started, end = status?.let { BackgroundTask.End(Instant.parse(endedAt), it, summary) })

    @Test
    fun `running background tasks come first in the order launched, then the ended ones, the latest end first`() {
        val tasks = listOf(
            task("old", BackgroundTask.Status.COMPLETED, "2026-10-24T10:03:00Z"), task("r1"),
            task("new", BackgroundTask.Status.FAILED, "2026-10-24T10:09:00Z"), task("r2"),
        )
        val context = contextOf(session(main).copy(background = tasks), cwdExists = true, live = false, git = null)
        assertEquals(listOf("r1", "r2", "new", "old"), context.background.map { it.id })
    }

    @Test
    fun `a running task says when it started, an ended one how it ended and how long it took`() {
        val utc = ZoneOffset.UTC
        assertEquals("since 10:02", backgroundState(task("r"), live = true, utc))
        assertEquals("ended with its process", backgroundState(task("r"), live = false, utc))
        assertEquals("completed · 2 min 40 s", backgroundState(task("c", BackgroundTask.Status.COMPLETED, summary = "completed (exit code 0)"), live = false, utc))
        assertEquals("failed · exit 1 · 2 min 40 s", backgroundState(task("f", BackgroundTask.Status.FAILED, summary = "failed with exit code 1"), live = true, utc))
        assertEquals("killed · 2 min 40 s", backgroundState(task("k", BackgroundTask.Status.KILLED), live = true, utc))
    }

    @Test
    fun `durations read in whole seconds, then minutes and seconds, then hours and minutes`() {
        assertEquals("45 s", durationText(Duration.ofMillis(45_900)))
        assertEquals("1 min 0 s", durationText(Duration.ofSeconds(60)))
        assertEquals("59 min 59 s", durationText(Duration.ofSeconds(3599)))
        assertEquals("1 h 5 min", durationText(Duration.ofSeconds(3900)))
    }

    @Test
    fun `files are all the files written, first touch first, and the brief is the session's`() {
        val written = listOf(main.resolve("src/A.kt"), main.resolve("docs/p.md"))
        val brief = Brief("Ship it", listOf(BriefTask("Write tests", TaskStatus.COMPLETED)))
        val context = contextOf(session(main, written).copy(brief = brief), cwdExists = true, live = false, git = null)
        assertEquals(written, context.files)
        assertEquals(brief, context.brief)
    }

    @Test
    fun `a file inside the session's folder reads relative to it, any other by its full path`() {
        assertEquals(Path.of("src/A.kt").toString(), fileLabel(main.resolve("src/A.kt"), main))
        assertEquals(Path.of("C:/tmp/x.md").toString(), fileLabel(Path.of("C:/tmp/x.md"), main))
    }
}
