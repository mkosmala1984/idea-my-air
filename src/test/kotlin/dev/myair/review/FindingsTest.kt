package dev.myair.review

import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Run
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class FindingsTest {
    private val w1 = Run("W1", "security", null, null, "main..HEAD", "2026-11-19T10:00:00Z")
    private val w2 = Run("W2", "style", null, null, "main..HEAD", "2026-11-19T10:00:00Z")
    private val empty = BranchReview("b", "main", Path.of("E:/d"), null, null, null, null, emptyList(), listOf(w1, w2), emptyList(), emptyList(), emptyMap())

    private fun inc(line: Int, category: String = "bug", file: String = "a.kt") = IncomingFinding(file, line, category, Severity.minor, "s$line", "x")

    @Test fun `two reports three lines apart in one file and category merge`() {
        val r = mergeFindings(mergeFindings(empty, w1, "c1", listOf(inc(10))), w2, "c1", listOf(inc(13)))
        assertEquals(1, r.findings.size)
        assertEquals(listOf("W1", "W2"), r.findings.single().reports.map { it.run })
        assertEquals(13, r.findings.single().line)
    }

    @Test fun `two findings of one report stay two findings, also when a third report joins one of them`() {
        val r = mergeFindings(empty, w1, "c1", listOf(inc(10), inc(12)))
        assertEquals(listOf(10, 12), r.findings.map { it.line })
        val joined = mergeFindings(r, w2, "c1", listOf(inc(11), inc(12)))
        assertEquals(listOf(listOf("W1", "W2"), listOf("W1", "W2")), joined.findings.map { f -> f.reports.map { it.run } })
    }

    @Test fun `a report four lines away or of another category stays apart`() {
        val r = mergeFindings(empty, w1, "c1", listOf(inc(10)))
        assertEquals(2, mergeFindings(r, w2, "c1", listOf(inc(14))).findings.size)
        assertEquals(2, mergeFindings(r, w2, "c1", listOf(inc(10, "style"))).findings.size)
        assertEquals(2, mergeFindings(r, w2, "c1", listOf(inc(10, file = "b.kt"))).findings.size)
    }

    @Test fun `a joined finding takes the nearest candidate`() {
        val r = mergeFindings(empty, w1, "c1", listOf(inc(10), inc(20)))
        assertEquals(listOf(1, 2), mergeFindings(r, w2, "c1", listOf(inc(18))).findings.map { it.reports.size })
        // equidistant candidates (13 is 3 from both 10 and 16): the lowest id
        val tie = mergeFindings(mergeFindings(empty, w1, "c1", listOf(inc(10), inc(16))), w2, "c1", listOf(inc(13)))
        assertEquals(listOf(2, 1), tie.findings.map { it.reports.size })
    }

    @Test fun `a second report of the same run replaces the first`() {
        val r = mergeFindings(empty, w1, "c1", listOf(inc(10)))
        assertEquals(emptyList<Finding>(), mergeFindings(r, w1, "c2", emptyList()).findings)
        assertEquals(listOf("c2"), mergeFindings(r, w1, "c2", listOf(inc(11))).findings.single().reports.map { it.commit })
    }

    @Test fun `a merged finding keeps its triage`() {
        val r = mergeFindings(empty, w1, "c1", listOf(inc(10))).setState("F1", FindingState.accepted)
        val m = mergeFindings(r, w2, "c1", listOf(inc(12)))
        assertEquals(FindingState.accepted, m.findings.single().state)
        assertEquals(2, m.findings.single().reports.size)
    }
}
