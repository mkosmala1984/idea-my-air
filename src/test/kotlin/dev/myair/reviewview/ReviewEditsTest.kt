package dev.myair.reviewview

import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Report
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReviewEditsTest {
    private val finding = Finding("F1", "a.kt", "bug", listOf(Report("W1", "r", "c1", 1, Severity.minor, "s", "x")))

    private fun review(findings: List<Finding> = listOf(finding), summary: String? = "old") =
        BranchReview("b", "main", Path.of("E:/d"), null, null, null, summary, emptyList(), emptyList(), findings, emptyList(), emptyMap())

    @Test fun `a blank reason or commit is refused`() {
        assertNull(ReviewEdit.Reason("F1").apply("  "))
        assertNull(ReviewEdit.Commit("F1").apply(""))
    }

    @Test fun `an empty summary clears it and a reason or commit sets the state`() {
        assertNull(checkNotNull(ReviewEdit.Summary.apply(" \n"))(review()).summary)
        val rejected = checkNotNull(ReviewEdit.Reason("F1").apply(" dup "))(review()).findings.single()
        assertEquals(FindingState.rejected to "dup", rejected.state to rejected.reason)
        val fixed = checkNotNull(ReviewEdit.Commit("F1").apply("abc123"))(review()).findings.single()
        assertEquals(FindingState.fixed to "abc123", fixed.state to fixed.fixCommit)
    }

    @Test fun `a finding gone leaves the review as it is and closes its edit`() {
        val r = review(findings = emptyList())
        assertSame(r, checkNotNull(ReviewEdit.Reason("F1").apply("why"))(r))
        assertSame(r, checkNotNull(ReviewEdit.Commit("F1").apply("abc"))(r))
        assertNull(ReviewEdit.Reason("F1").current(r))
        assertEquals("", ReviewEdit.Summary.current(review(summary = null)))
    }
}
