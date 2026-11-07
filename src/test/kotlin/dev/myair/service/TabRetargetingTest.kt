package dev.myair.service

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class TabRetargetingTest {
    private fun live(pid: Long, status: SessionStatus = SessionStatus.IDLE, job: String? = null, parkedBy: Set<Long> = emptySet()) =
        LiveSession(pid, "cli", status, null, Instant.EPOCH, Path.of("/p"), Instant.EPOCH, job, parkedBy)

    private fun retarget(pid: Long?, live: Map<String, LiveSession>, titles: Map<String, String> = emptyMap(), job: String? = null) =
        retarget("old", "Old title", pid, job, live, titles)

    @Test
    fun `a tab whose process is unknown or unregistered keeps its session, without a status`() {
        assertEquals(TabTarget("old", "Old title", null), retarget(null, mapOf("other" to live(7))))
        assertEquals(TabTarget("old", "Old title", null), retarget(42, mapOf("other" to live(7))))
    }

    @Test
    fun `a tab follows its process to the session the registry reports for it`() {
        assertEquals(
            TabTarget("new", "Cleared", SessionStatus.WORKING),
            retarget(42, mapOf("new" to live(42, SessionStatus.WORKING), "other" to live(7)), mapOf("new" to "Cleared")),
        )
    }

    @Test
    fun `a switched-to session without a transcript yet is a new session`() {
        assertEquals(TabTarget("new", NEW_SESSION_TITLE, SessionStatus.IDLE), retarget(42, mapOf("new" to live(42))))
    }

    @Test
    fun `a tab picks up a title its session gained later`() {
        assertEquals(TabTarget("old", "Named", SessionStatus.IDLE), retarget(42, mapOf("old" to live(42)), mapOf("old" to "Named")))
    }

    @Test
    fun `a tab attached to a job follows the job's worker, not its own pid`() {
        val live = mapOf("old" to live(42, SessionStatus.IDLE), "job" to live(7244, SessionStatus.WORKING, job = "0aa16524"))
        assertEquals(TabTarget("job", "Job", SessionStatus.WORKING), retarget(42, live, mapOf("job" to "Job"), job = "0aa16524"))
    }

    @Test
    fun `a tab attached to a job that ended keeps its session, without a status`() {
        assertEquals(TabTarget("old", "Old title", null), retarget(42, mapOf("other" to live(42)), job = "0aa16524"))
    }

    @Test
    fun `a tab whose process parked into a job shows the job's session and status`() {
        val live = mapOf("job" to live(7244, SessionStatus.NEEDS_INPUT, job = "0aa16524", parkedBy = setOf(42)))
        assertEquals(TabTarget("job", "Moved", SessionStatus.NEEDS_INPUT), retarget(42, live, mapOf("job" to "Moved")))
    }
}
