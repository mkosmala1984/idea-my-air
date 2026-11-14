package dev.myair.dashboard

import dev.myair.actions.BranchFate
import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class DashboardTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")

    private fun session(id: String, daysAgo: Long, brief: Brief? = null, asks: Boolean = false) =
        ClaudeSession(id, Path.of("/work/proj"), "title $id", now - Duration.ofDays(daysAgo), emptyList(), emptyList(), brief = brief, asks = asks)

    private fun live(status: SessionStatus) = LiveSession(1, "cli", status, null, Instant.EPOCH, Path.of("/work/proj"), Instant.EPOCH)

    private val clean = Result.success(SessionGit(false, null))

    @Test
    fun `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files`() {
        val halfDone = Brief("goal", listOf(BriefTask("a", TaskStatus.COMPLETED), BriefTask("b", TaskStatus.PENDING)))
        val sessions = listOf(
            session("working", 0), session("waiting", 0), session("settled", 1, asks = true),
            session("brief", 3, halfDone), session("asks", 4, asks = true), session("merged", 5, halfDone),
            session("mergedDirty", 6), session("unknown", 7), session("done", 8),
        )
        val merged = BranchFate(0, BranchFate.Merged("abc", 7617))
        val git = mapOf(
            "brief" to clean, "asks" to clean, "done" to clean,
            "merged" to Result.success(SessionGit(false, merged)),
            "mergedDirty" to Result.success(SessionGit(true, merged)),
        )
        val live = mapOf("working" to live(SessionStatus.WORKING), "waiting" to live(SessionStatus.NEEDS_INPUT))
        val board = dashboardOf(sessions, live, setOf("settled"), emptySet(), SessionFolders(), git, emptyMap(), emptyMap(), now)

        assertEquals(listOf("waiting", "working"), board.now.map { it.id })
        val unfinished = board.unfinished.single()
        assertEquals("proj", unfinished.name)
        assertEquals(
            mapOf(
                "brief" to listOf("brief 1/2"), "asks" to listOf("asks"),
                "mergedDirty" to listOf("uncommitted", "PR #7617"),
            ),
            unfinished.rows.associate { row -> row.session.id to row.chips.map(::chipText) },
        )
        // Git still being read may yet show the branch merged, so the session is neither unfinished nor finished yet.
        assertEquals(listOf("unknown"), board.checking.map { it.session.id })
        assertEquals(listOf("merged", "done"), board.finished.map { it.session.id })
        assertEquals(2 to 6, board.recent to board.older)
    }

    private fun board(sessions: List<ClaudeSession>, git: Map<String, Result<SessionGit>>, analyses: Map<String, Analysis> = emptyMap(), failures: Map<String, String> = emptyMap()) =
        dashboardOf(sessions, emptyMap(), emptySet(), emptySet(), SessionFolders(), git, analyses, failures, now)

    @Test
    fun `an analysis of an older last message is not shown`() {
        val s = session("s", 3, asks = true)
        val stale = Analysis(s.lastMessage - Duration.ofHours(1), Verdict.OPEN, "tests", now)
        val row = board(listOf(s), mapOf("s" to clean), mapOf("s" to stale)).unfinished.single().rows.single()
        assertEquals(RowAnalysis.None, row.analysis)
        assertEquals(listOf("asks"), row.chips.map(::chipText))
    }

    @Test
    fun `a current analysis adds its verdict chip and does not keep a session unfinished`() {
        val s = session("s", 3)
        val board = board(listOf(s), mapOf("s" to clean), mapOf("s" to Analysis(s.lastMessage, Verdict.OPEN, "tests", now)))
        val row = board.finished.single()
        assertEquals(RowAnalysis.Current(Verdict.OPEN, "tests"), row.analysis)
        assertEquals(listOf("open"), row.chips.map(::chipText))
    }

    @Test
    fun `a failed analysis shows until the next result`() {
        val s = session("s", 3)
        val row = board(listOf(s), mapOf("s" to clean), failures = mapOf("s" to "Not logged in")).finished.single()
        assertEquals(RowAnalysis.Failed("Not logged in"), row.analysis)
        assertEquals(listOf("analysis failed"), row.chips.map(::chipText))
    }

    @Test
    fun `candidates leave out merged, current and too old sessions, not those whose git is still being read`() {
        val current = session("current", 3, asks = true)
        val sessions = listOf(current, session("fresh", 3, asks = true), session("old", 20), session("merged", 2), session("pending", 1), session("failed", 4))
        val git = mapOf(
            "current" to clean, "fresh" to clean, "old" to clean, "failed" to clean,
            "merged" to Result.success(SessionGit(false, BranchFate(0, BranchFate.Merged("abc", 1)))),
        )
        val b = board(sessions, git, mapOf("current" to Analysis(current.lastMessage, Verdict.OPEN, "x", now)), mapOf("failed" to "boom"))
        assertEquals(listOf("pending", "fresh", "failed"), analysisCandidates(b, 14, now).map { it.id })
        assertEquals(listOf("pending", "fresh", "failed", "old"), analysisCandidates(b, null, now).map { it.id })
    }
}
