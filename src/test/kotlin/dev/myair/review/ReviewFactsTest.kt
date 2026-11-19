package dev.myair.review

import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Report
import dev.myair.store.Run
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewFactsTest {
    private fun run(id: String, reviewer: String = "a", job: String? = "j", reported: String? = null) = Run(id, reviewer, null, job, "main..HEAD", "2026-11-19T10:00:00Z", reported)

    private fun finding(id: String, state: FindingState = FindingState.open, severity: Severity = Severity.minor, reviewer: String = "a", line: Int = 11, commit: String = "c1", run: String = "W1") =
        Finding(id, "a.kt", "bug", listOf(Report(run, reviewer, commit, line, severity, "s", "x")), state, "r", "f", "c")

    private fun review(runs: List<Run>, findings: List<Finding>) =
        BranchReview("b", "main", Path.of("E:/d"), null, null, null, null, emptyList(), runs, findings, emptyList(), emptyMap())

    @Test fun `a run is running while its job is live and unreported`() {
        assertEquals(RunStatus.running, runStatus(run("W1"), setOf("j")))
        assertEquals(RunStatus.running, runStatus(run("W1", job = null), emptySet()))
        assertEquals(RunStatus.reported, runStatus(run("W1", reported = "2026-11-19T11:00:00Z"), emptySet()))
        assertEquals(RunStatus.failed, runStatus(run("W1"), emptySet()))
    }

    @Test fun `a finding is maybe fixed when a changed range comes within three lines`() {
        assertTrue(maybeFixed(finding("F1"), mapOf("a.kt" to listOf(14..14))))
        assertFalse(maybeFixed(finding("F1"), mapOf("a.kt" to listOf(15..15))))
        assertFalse(maybeFixed(finding("F1"), mapOf("b.kt" to listOf(11..11))))
    }

    @Test fun `an unknown commit makes nothing maybe fixed`() {
        assertFalse(maybeFixed(finding("F1"), null))
    }

    @Test fun `blockers are blocker-severity findings open or accepted`() {
        val r = review(emptyList(), FindingState.entries.mapIndexed { i, s -> finding("F$i", s, Severity.blocker) } + finding("M", severity = Severity.major) + finding("N"))
        assertEquals(2, blockerCount(r))
        assertEquals(2, toDecide(r))
    }

    @Test fun `the reviewed commit is the one of the newest reported run`() {
        val r = review(
            listOf(run("W1", reported = "2026-11-19T10:30:00Z"), run("W2", reported = "2026-11-19T11:00:00Z"), run("W3")),
            listOf(finding("F1", commit = "c1", run = "W1"), finding("F2", commit = "c2", run = "W2")),
        )
        assertEquals("c2", reviewedCommit(r))
        assertEquals(null, reviewedCommit(review(listOf(run("W3")), emptyList())))
    }

    @Test fun `precision counts kept over triaged per reviewer across reviews`() {
        val r1 = review(listOf(run("W1")), listOf(finding("F1", FindingState.accepted), finding("F2", FindingState.fixed), finding("F3", FindingState.rejected), finding("F4")))
        val r2 = review(listOf(run("W1")), listOf(finding("F1", FindingState.posted)))
        assertEquals(mapOf("a" to Precision(3, 4, 2)), precision(listOf(r1, r2)))
    }
}
