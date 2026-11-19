package dev.myair.review

import dev.myair.claude.SessionStatus
import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Run
import dev.myair.store.Severity

/** [text]: how the panel names it. */
enum class RunStatus(val text: String) { running("running"), waiting("needs input"), stopped("stopped without report"), reported("reported"), failed("failed") }

/**
 * A reported run is reported, whatever its job does: reviewers stay attachable for follow-up questions. Otherwise its
 * job's live entry in [liveJobs] (job id to status) tells: working is running, needing input waiting, idle stopped
 * without a report. Without an entry a run is starting until its session is learned from the registry (`claude --bg`
 * ignores `--session-id`, so a run is recorded before its job exists, and the registry skips a starting `claude`'s
 * entry), and failed once its session was seen.
 */
fun runStatus(run: Run, liveJobs: Map<String, SessionStatus>): RunStatus = if (run.reported != null) RunStatus.reported else when (liveJobs[run.job]) {
    SessionStatus.WORKING -> RunStatus.running
    SessionStatus.NEEDS_INPUT -> RunStatus.waiting
    SessionStatus.IDLE -> RunStatus.stopped
    null -> if (run.session == null) RunStatus.running else RunStatus.failed
}

private const val NEAR = 3

/** [changed]: old-side ranges since the finding's newest commit; null when that commit is unknown. */
fun maybeFixed(finding: Finding, changed: Map<String, List<IntRange>>?): Boolean =
    changed?.get(finding.file)?.any { it.first <= finding.line + NEAR && it.last >= finding.line - NEAR } ?: false

/** Blocker-severity findings still standing: open or accepted. */
fun blockerCount(review: BranchReview): Int =
    review.findings.count { it.severity == Severity.blocker && it.state in setOf(FindingState.open, FindingState.accepted) }

fun toDecide(review: BranchReview): Int = review.findings.count { it.state == FindingState.open && it.severity != Severity.blocker }

/** The commit the newest reported run reported on, findings or not; null without a reported run. */
fun reviewedCommit(review: BranchReview): String? = review.runs.filter { it.reported != null }.maxByOrNull { it.reported!! }?.commit

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

