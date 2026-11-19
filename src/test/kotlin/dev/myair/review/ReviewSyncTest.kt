package dev.myair.review

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.folders.Plan
import dev.myair.folders.PlanChanges
import dev.myair.folders.SessionFolders
import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.Report
import dev.myair.store.Run
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReviewSyncTest {
    private fun live(job: String?) = LiveSession(1, "cli", SessionStatus.WORKING, null, Instant.EPOCH, Path.of("E:/d"), Instant.EPOCH, job)

    private fun run(id: String, job: String?, session: String? = null) = Run(id, "a", session, job, "main..HEAD", "2026-11-19T10:00:00Z")

    private fun review(runs: List<Run>, plan: String? = null, findings: List<Finding> = emptyList()) =
        BranchReview("b", "main", Path.of("E:/d"), null, plan, null, null, emptyList(), runs, findings, emptyList(), emptyMap())

    @Test fun `job sessions map each live job to the session it runs`() {
        assertEquals(mapOf("j1" to "s1"), jobSessions(mapOf("s1" to live("j1"), "s2" to live(null))))
    }

    @Test fun `a run with a job and no session learns the session of its job's live entry`() {
        val (learned, filed) = learnSessions(review(listOf(run("W1", "j1"), run("W2", "j2"), run("W3", null), run("W4", "j4", "old"))), mapOf("j1" to "s1", "j4" to "s4"))
        assertEquals(listOf("s1", null, null, "old"), learned.runs.map { it.session })
        assertEquals(listOf("s1"), filed)
    }

    @Test fun `a review with nothing to learn is returned as it is`() {
        val r = review(listOf(run("W1", "j1", "s1")))
        assertSame(r, learnSessions(r, mapOf("j1" to "s1")).first)
    }

    @Test fun `precision counts a review whose folder left the structure, not one that cannot be read`() {
        val structure = SessionFolders().createFolder(null, "r1", "Review: b")
        val kept = Finding("F1", "a.kt", "bug", listOf(Report("W1", "a", "c1", 1, Severity.minor, "s", "x")), dev.myair.store.FindingState.accepted)
        val all = mapOf("r1" to Result.success(review(listOf(run("W1", null)))), "gone" to Result.success(review(listOf(run("W1", null)), findings = listOf(kept))),
            "bad" to Result.failure(IllegalStateException("broken")))
        assertEquals(setOf("r1"), inStructure(all, structure).keys)
        assertEquals(Precision(1, 1, 2), precision(onDisk(all))["a"])
    }

    @Test fun `reviews whose folder is not in the structure are left out`() {
        val structure = SessionFolders().createFolder(null, "r1", "Review: b")
        val all = mapOf("r1" to Result.success(review(emptyList())), "gone" to Result.success(review(emptyList())))
        assertEquals(setOf("r1"), inStructure(all, structure).keys)
    }

    @Test fun `the linked plan is found only while its folder has a plan`() {
        val structure = SessionFolders().createFolder(null, "p", "p", plan = Plan()).createFolder(null, "x", "x")
        assertEquals(Plan(), linkedPlan(review(emptyList(), "p"), structure))
        assertNull(linkedPlan(review(emptyList(), "x"), structure))
        assertNull(linkedPlan(review(emptyList(), "gone"), structure))
        assertNull(linkedPlan(review(emptyList()), structure))
    }

    @Test fun `a mirror is due when a blocker finding is not in the plan yet`() {
        val structure = SessionFolders().createFolder(null, "p", "p", plan = Plan())
        val blocker = Finding("F1", "a.kt", "bug", listOf(Report("W1", "a", "c1", 1, Severity.blocker, "s", "x")))
        val r = review(listOf(run("W1", null)), "p", listOf(blocker))
        assertEquals(mapOf("F1" to "B1"), mirrorDue(r, structure, emptyMap())?.blockers)
        assertNull(mirrorDue(review(emptyList()), structure, emptyMap()))
    }

    @Test fun `mirroring against a plan another IDE already mirrored into adds nothing, against the stale plan it would add again`() {
        val stale = SessionFolders().createFolder(null, "p", "p", plan = Plan())
        val blocker = Finding("F1", "a.kt", "bug", listOf(Report("W1", "a", "c1", 1, Severity.blocker, "s", "x")))
        val r = review(listOf(run("W1", null)), "p", listOf(blocker))
        val mirror = checkNotNull(mirrorDue(r, stale, emptyMap()))
        val mirrored = PlanChanges.apply(stale, "p", mirror.changes, LocalDate.of(2026, 11, 19))
        val stored = r.copy(blockers = mirror.blockers)
        assertNull(mirrorDue(stored, mirrored, emptyMap()))
        assertEquals(mapOf("F1" to "B1"), mirrorDue(stored, stale, emptyMap())?.blockers, "the plan as this IDE read it lacks B1")
    }
}
