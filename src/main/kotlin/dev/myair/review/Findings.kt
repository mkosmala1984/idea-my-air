package dev.myair.review

import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.Report
import dev.myair.store.Run
import dev.myair.store.Severity
import kotlin.math.abs

/** One finding of a reviewer's report, before it is joined to the review's. */
data class IncomingFinding(
    val file: String,
    val line: Int,
    val category: String,
    val severity: Severity,
    val summary: String,
    val failureScenario: String,
)

private const val JOIN_DISTANCE = 3

private fun Finding.number() = id.drop(1).toInt()

/**
 * Adds [incoming], reported by [run] at [commit], to [review]: the run's earlier reports are dropped first (and findings
 * left with none); each incoming finding joins the nearest finding of the same file and category at most
 * [JOIN_DISTANCE] lines away (ties: lowest id), keeping its triage, else becomes a new one.
 */
fun mergeFindings(review: BranchReview, run: Run, commit: String, incoming: List<IncomingFinding>): BranchReview {
    val kept = review.findings.mapNotNull { f ->
        f.reports.filter { it.run != run.id }.takeIf { it.isNotEmpty() }?.let { f.copy(reports = it) }
    }
    return incoming.fold(review.copy(findings = kept)) { r, f ->
        val report = Report(run.id, run.reviewer, commit, f.line, f.severity, f.summary, f.failureScenario)
        val target = r.findings
            .filter { it.file == f.file && it.category == f.category && abs(it.line - f.line) <= JOIN_DISTANCE }
            .minWithOrNull(compareBy<Finding>({ abs(it.line - f.line) }, { it.number() }))
        if (target != null) r.copy(findings = r.findings.map { if (it === target) it.copy(reports = it.reports + report) else it })
        else r.nextId("F").let { (id, next) -> next.copy(findings = next.findings + Finding(id, f.file, f.category, listOf(report))) }
    }
}
