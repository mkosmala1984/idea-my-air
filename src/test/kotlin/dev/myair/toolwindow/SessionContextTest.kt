package dev.myair.toolwindow

import dev.myair.actions.Git
import dev.myair.actions.GitState
import dev.myair.claude.BackgroundTask
import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.TaskStatus
import dev.myair.claude.ClaudeSession
import dev.myair.claude.SkillOrigin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals

class SessionContextTest {
    private val main = Path.of("E:/dev/projects/my-air")

    private fun session(cwd: Path, changedFiles: List<Path> = emptyList()) =
        ClaudeSession("s1", cwd, "title", Instant.EPOCH, changedFiles, emptyList(), project = main)

    @Test
    fun `skills are grouped by their plugin, those of none first, each keeping the listing's order`() {
        val skills = listOf("tdd", "superpowers:brainstorming", "pstack:how", "init", "superpowers:writing-plans")
        assertEquals(
            listOf(
                NO_PLUGIN to listOf("tdd", "init"), "pstack" to listOf("pstack:how"),
                "superpowers" to listOf("superpowers:brainstorming", "superpowers:writing-plans"),
            ),
            skillGroups(skills),
        )
    }

    @Test
    fun `an update is told only when the marketplace names a version, and the date it was checked when there is none`() {
        val origin = SkillOrigin("pstack@market", null, "1.15.0", "1.15.0", Instant.parse("2026-10-18T23:30:00Z"))
        assertEquals("up to date as of 2026-10-19", updateState(origin, ZoneOffset.ofHours(2)))
        assertEquals("update available: 1.15.0 → 1.16.0", updateState(origin.copy(latest = "1.16.0"), ZoneOffset.UTC))
        assertEquals(null, updateState(origin.copy(latest = null), ZoneOffset.UTC))
    }

    @Test
    fun `a session in a linked worktree of its project is marked as one`() {
        val context = contextOf(session(main.resolve(".claude/worktrees/x")), cwdExists = true, live = false, git = null)
        assertEquals("my-air" to true, context.project to context.worktree)
        assertEquals(false, contextOf(session(main), cwdExists = true, live = false, git = null).worktree)
    }

    @Test
    fun `a session moved to another work tree shows that one and where it started, a changed branch the one it started on`() {
        val worktree = main.resolve(".claude/worktrees/x")
        val started = session(main).copy(workDir = worktree, startBranch = "main")
        val git = GitState("worktree-x", false, worktree, "main", true, null, emptyList(), null)
        val moved = contextOf(started, cwdExists = true, live = false, git = git, workTree = worktree)
        assertEquals(listOf(worktree, true, main, "main"), listOf(moved.cwd, moved.worktree, moved.startedIn, moved.startBranch))
        val stayed = contextOf(started, cwdExists = true, live = false, git = git.copy(branch = "main", root = main))
        assertEquals(listOf(main, false, null, null), listOf(stayed.cwd, stayed.worktree, stayed.startedIn, stayed.startBranch))
        // Before git is read the branch now is unknown, so no start branch is told.
        assertEquals(null, contextOf(started, cwdExists = true, live = false, git = null).startBranch)
    }

    @Test
    fun `a cd within the work tree it started in moves nothing, entering a linked worktree moves the session there`(@TempDir dir: Path) {
        val repo = dir.resolve("repo").createDirectories()
        fun git(vararg args: String) = Git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", *args)
        git("init", "-b", "main")
        git("commit", "--allow-empty", "-m", "first")
        val sub = repo.resolve("src").createDirectories()
        val worktree = repo.resolve(".claude/worktrees/x")
        git("worktree", "add", "-b", "worktree-x", worktree.toString())
        val repoRoot = checkNotNull(Git.root(sub))
        val worktreeRoot = checkNotNull(Git.root(worktree))
        assertEquals(repo, workTreeOf(repo, sub, repoRoot))
        assertEquals(sub, workTreeOf(sub, repo, repoRoot))
        assertEquals(worktreeRoot, workTreeOf(repo, worktree, worktreeRoot))
        assertEquals(repoRoot, workTreeOf(worktree, repo, repoRoot))
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
    fun `files are the ones written outside the work tree once git has read it, all of them before`() {
        val inside = main.resolve("src/A.kt")
        val outside = Path.of("C:/Users/me/.claude/scripts/s.mjs")
        val written = session(main, listOf(inside, outside))
        val git = GitState("main", false, main, "origin/main", true, null, emptyList(), null)
        assertEquals(listOf(outside), contextOf(written, cwdExists = true, live = false, git = git).files)
        assertEquals(listOf(inside, outside), contextOf(written, cwdExists = true, live = false, git = null).files)
    }

    @Test
    fun `a model id reads as its family and version, a date suffix dropped`() {
        assertEquals("Opus 5.5", modelName("claude-opus-5-5"))
        assertEquals("Sonnet 4.5", modelName("claude-sonnet-4-5-20250929"))
        assertEquals("Fable 5.1", modelName("claude-fable-5-1"))
        assertEquals("my-local-model", modelName("my-local-model"))
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
    fun `an agent shows its model and usage, a workflow its agents, a monitor its events`() {
        val utc = ZoneOffset.UTC
        val agent = task("a").copy(kind = BackgroundTask.Kind.AGENT, model = "claude-opus-5-5")
        assertEquals("opus-5-5 · since 10:02", backgroundState(agent, live = true, utc))
        val ended = task("a", BackgroundTask.Status.COMPLETED).copy(kind = BackgroundTask.Kind.AGENT, model = "claude-opus-5-5", usage = BackgroundTask.Usage(154_221, 23, null))
        assertEquals("opus-5-5 · completed · 2 min 40 s · 154k tokens · 23 tools", backgroundState(ended, live = true, utc))
        val workflow = task("w", BackgroundTask.Status.COMPLETED).copy(kind = BackgroundTask.Kind.WORKFLOW, usage = BackgroundTask.Usage(1_377_311, 125, BackgroundTask.Agents(11, 10, 1)))
        assertEquals("completed · 2 min 40 s · 10/11 agents, 1 failed · 1.4M tokens · 125 tools", backgroundState(workflow, live = true, utc))
        val monitor = task("m").copy(kind = BackgroundTask.Kind.MONITOR)
        assertEquals("since 10:02 · no events", backgroundState(monitor, live = true, utc))
        assertEquals("since 10:02 · 1 event, last 10:07", backgroundState(monitor.copy(events = 1, lastEvent = Instant.parse("2026-10-24T10:07:00Z")), live = true, utc))
        assertEquals("since 10:02 · 3 events, last 10:09", backgroundState(monitor.copy(events = 3, lastEvent = Instant.parse("2026-10-24T10:09:00Z")), live = true, utc))
    }

    @Test
    fun `counts read as they are under a thousand, then in thousands, then in millions`() {
        assertEquals("950", countText(950))
        assertEquals("154k", countText(154_221))
        assertEquals("1.4M", countText(1_377_311))
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
