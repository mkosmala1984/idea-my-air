package dev.myair.review

import dev.myair.folders.Blocker
import dev.myair.claude.SessionStatus
import dev.myair.folders.Plan
import dev.myair.folders.PlanChanges
import dev.myair.folders.ReviewStatus
import dev.myair.folders.SessionFolders
import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Report
import dev.myair.store.Run
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReviewMirrorTest {
    private val today = LocalDate.of(2026, 11, 19)
    private val reported = Run("W1", "a", null, "j", "main..HEAD", "2026-11-19T10:00:00Z", "2026-11-19T10:05:00Z", "abc1234")

    private fun finding(id: String, state: FindingState = FindingState.open, severity: Severity = Severity.blocker, line: Int = 3) =
        Finding(id, "a.kt", "bug", listOf(Report("W1", "a", "abc1234", line, severity, "race", "x")), state, "r", "f", "c")

    private fun review(findings: List<Finding>, runs: List<Run> = listOf(reported), blockers: Map<String, String> = emptyMap()) =
        BranchReview("b", "main", Path.of("E:/d"), null, "p", null, null, emptyList(), runs, findings, emptyList(), emptyMap(), blockers)

    private fun structure(plan: Plan = Plan()) = SessionFolders().createFolder(null, "p", "p", plan = plan)

    /** Applies the mirror to [plan] and returns the resulting plan. */
    private fun applied(review: BranchReview, plan: Plan = Plan()): Pair<Plan, Mirror> {
        val mirror = assertNotNull(mirrorToPlan(review, plan, emptyMap()))
        return PlanChanges.apply(structure(plan), "p", mirror.changes, today).find("p")!!.plan!! to mirror
    }

    @Test fun `a blocker finding becomes a plan blocker and its id is recorded`() {
        val (plan, mirror) = applied(review(listOf(finding("F1"), finding("F2", line = 9))))
        assertEquals(listOf(Blocker("B1", "F1 a.kt:3 race"), Blocker("B2", "F2 a.kt:9 race")), plan.review!!.blockers)
        assertEquals(mapOf("F1" to "B1", "F2" to "B2"), mirror.blockers)
        assertEquals("abc1234", plan.review.commit)
        val (more, again) = applied(review(listOf(finding("F3")), blockers = mirror.blockers), plan)
        assertEquals("B3", again.blockers.getValue("F3"))
        assertEquals("F3 a.kt:3 race", more.review!!.blockers.single { it.id == "B3" }.text)
    }

    @Test fun `a fixed or rejected blocker finding resolves its plan blocker`() {
        val (plan, mirror) = applied(review(listOf(finding("F1"), finding("F2"), finding("F3"), finding("F4"))))
        val states = listOf("F1" to FindingState.accepted, "F2" to FindingState.fixed, "F3" to FindingState.rejected, "F4" to FindingState.open)
        val (after, _) = applied(review(states.map { (id, s) -> finding(id, s) }, blockers = mirror.blockers), plan)
        assertEquals(listOf(false, true, true, false), after.review!!.blockers.map { it.resolved })
    }

    @Test fun `plan blockers the review did not make are left alone`() {
        val (plan, _) = applied(review(emptyList()), Plan(next = mapOf("B" to 2), review = dev.myair.folders.Review(ReviewStatus.NOT_DONE, null, "2026-11-18", listOf(Blocker("B1", "by hand")))))
        assertEquals(listOf(Blocker("B1", "by hand")), plan.review!!.blockers)
        val (next, mirror) = applied(review(listOf(finding("F1"))), plan)
        assertEquals(listOf(Blocker("B1", "by hand"), Blocker("B2", "F1 a.kt:3 race")), next.review!!.blockers)
        assertEquals("B2", mirror.blockers["F1"])
    }

    @Test fun `a mapped blocker deleted from the plan is added again`() {
        val r = review(listOf(finding("F1")), blockers = mapOf("F1" to "B1"))
        val (plan, mirror) = applied(r, Plan(next = mapOf("B" to 2), review = dev.myair.folders.Review(ReviewStatus.DONE, "abc1234", "2026-11-18")))
        assertEquals(listOf(Blocker("B2", "F1 a.kt:3 race")), plan.review!!.blockers)
        assertEquals(mapOf("F1" to "B2"), mirror.blockers)
    }

    @Test fun `the review status is in progress while a run runs, done after a report, else not done`() {
        val starting = Run("W2", "a", null, null, "main..HEAD", "2026-11-19T11:00:00Z")
        fun status(runs: List<Run>) = applied(review(emptyList(), runs)).first.review!!
        assertEquals(ReviewStatus.IN_PROGRESS, status(listOf(reported, starting)).status)
        assertEquals(ReviewStatus.DONE, status(listOf(reported)).status)
        assertEquals(ReviewStatus.DONE, status(listOf(reported, starting.copy(job = "dead", session = "s"))).status)
        assertEquals(ReviewStatus.NOT_DONE, status(listOf(starting.copy(job = "dead", session = "s"))).status)
        assertNull(status(listOf(starting.copy(job = "dead"))).commit)
    }

    @Test fun `a run that needs input or stopped without a report does not keep the review in progress`() {
        fun status(job: SessionStatus, runs: List<Run>) = PlanChanges.apply(structure(), "p", assertNotNull(mirrorToPlan(review(emptyList(), runs), Plan(), mapOf("j2" to job))).changes, today)
            .find("p")!!.plan!!.review!!.status
        val run = Run("W2", "a", "s", "j2", "main..HEAD", "2026-11-19T11:00:00Z")
        assertEquals(ReviewStatus.IN_PROGRESS, status(SessionStatus.WORKING, listOf(reported, run)))
        assertEquals(ReviewStatus.DONE, status(SessionStatus.NEEDS_INPUT, listOf(reported, run)))
        assertEquals(ReviewStatus.DONE, status(SessionStatus.IDLE, listOf(reported, run)))
        assertEquals(ReviewStatus.NOT_DONE, status(SessionStatus.NEEDS_INPUT, listOf(run)))
        assertEquals(ReviewStatus.NOT_DONE, status(SessionStatus.IDLE, listOf(run)))
    }

    @Test fun `nothing to change gives no mirror`() {
        val r = review(listOf(finding("F1")))
        val (plan, mirror) = applied(r)
        assertNull(mirrorToPlan(r.copy(blockers = mirror.blockers), plan, emptyMap()))
    }

    @Test fun `a blocker finding downgraded or dropped by a re-report resolves its plan blocker and keeps its mapping`() {
        val (plan, mirror) = applied(review(listOf(finding("F1"), finding("F2"))))
        val downgraded = review(listOf(finding("F1", severity = Severity.minor), finding("F2")), blockers = mirror.blockers)
        val (afterDowngrade, m1) = applied(downgraded, plan)
        assertEquals(listOf(true, false), afterDowngrade.review!!.blockers.map { it.resolved })
        assertEquals(mirror.blockers, m1.blockers)
        val dropped = review(listOf(finding("F1")), blockers = mirror.blockers)
        val (afterDrop, m2) = applied(dropped, plan)
        assertEquals(listOf(false, true), afterDrop.review!!.blockers.map { it.resolved })
        assertEquals(mirror.blockers, m2.blockers)
        assertNull(mirrorToPlan(dropped, afterDrop, emptyMap()))
    }
}
