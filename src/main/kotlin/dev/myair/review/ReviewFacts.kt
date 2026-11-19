package dev.myair.review

import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Run
import dev.myair.store.Severity

enum class RunStatus { running, reported, failed }

/** A run without a job is starting (`claude --bg` ignores `--session-id`, so a run is recorded before its job exists). */
fun runStatus(run: Run, liveJobs: Set<String>): RunStatus = when {
    run.reported != null -> RunStatus.reported
    run.job == null || run.job in liveJobs -> RunStatus.running
    else -> RunStatus.failed
}

private const val NEAR = 3

/** [changed]: old-side ranges since the finding's newest commit; null when that commit is unknown. */
fun maybeFixed(finding: Finding, changed: Map<String, List<IntRange>>?): Boolean =
    changed?.get(finding.file)?.any { it.first <= finding.line + NEAR && it.last >= finding.line - NEAR } ?: false

/** Blocker-severity findings still standing: open or accepted. */
fun blockerCount(review: BranchReview): Int =
    review.findings.count { it.severity == Severity.blocker && it.state in setOf(FindingState.open, FindingState.accepted) }

fun toDecide(review: BranchReview): Int = review.findings.count { it.state == FindingState.open && it.severity != Severity.blocker }

/** The commit of the newest reported run's reports; null without a reported run or when it reported no finding. */
fun reviewedCommit(review: BranchReview): String? {
    val run = review.runs.filter { it.reported != null }.maxByOrNull { it.reported!! } ?: return null
    return review.findings.flatMap { it.reports }.lastOrNull { it.run == run.id }?.commit
}

data class Precision(val kept: Int, val triaged: Int, val reviews: Int)

private val KEPT = setOf(FindingState.accepted, FindingState.fixed, FindingState.posted)

/** Per reviewer: findings it reported that were kept, those triaged (kept or rejected), and the reviews it ran in. */
fun precision(reviews: Collection<BranchReview>): Map<String, Precision> {
    val result = mutableMapOf<String, Precision>()
    for (review in reviews) {
        val names = review.runs.map { it.reviewer }.toSet()
        for (name in names) {
            val states = review.findings.filter { f -> f.reports.any { it.reviewer == name } }.map { it.state }
            val kept = states.count { it in KEPT }
            val old = result[name] ?: Precision(0, 0, 0)
            result[name] = Precision(old.kept + kept, old.triaged + kept + states.count { it == FindingState.rejected }, old.reviews + 1)
        }
    }
    return result
}

