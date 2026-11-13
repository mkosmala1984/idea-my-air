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
        val board = dashboardOf(sessions, live, setOf("settled"), emptySet(), SessionFolders(), git, now)

        assertEquals(listOf("waiting", "working"), board.now.map { it.id })
        val unfinished = board.unfinished.single()
        assertEquals("proj", unfinished.name)
        assertEquals(
            mapOf(
                "brief" to listOf("brief 1/2"), "asks" to listOf("asks"),
                "mergedDirty" to listOf("uncommitted", "PR #7617"), "unknown" to listOf("…"),
            ),
            unfinished.rows.associate { row -> row.session.id to row.chips.map(::chipText) },
        )
        assertEquals(listOf("merged", "done"), board.finished.map { it.session.id })
        assertEquals(2 to 6, board.recent to board.older)
    }
}
