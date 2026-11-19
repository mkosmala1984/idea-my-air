package dev.myair.reviewview

import dev.myair.review.setState
import dev.myair.review.setSummary
import dev.myair.store.BranchReview
import dev.myair.store.FindingState

/** A text of a review edited in place in the review panel: its summary, or what rejecting or fixing a finding asks for. */
sealed interface ReviewEdit {
    data object Summary : ReviewEdit

    /** Why finding [findingId] is rejected. */
    data class Reason(val findingId: String) : ReviewEdit

    /** The commit fixing finding [findingId]. */
    data class Commit(val findingId: String) : ReviewEdit

    val multiline get() = this == Summary
}

private fun BranchReview.has(findingId: String) = findings.any { it.id == findingId }

/** The text the edit opens with; null when its finding is gone, which closes it. */
fun ReviewEdit.current(review: BranchReview): String? = when (this) {
    ReviewEdit.Summary -> review.summary.orEmpty()
    is ReviewEdit.Reason -> review.findings.firstOrNull { it.id == findingId }?.let { it.reason.orEmpty() }
    is ReviewEdit.Commit -> review.findings.firstOrNull { it.id == findingId }?.let { it.fixCommit.orEmpty() }
}

/**
 * The change saving [text] makes, applied by id to the review as it is then, so a finding a re-report dropped meanwhile
 * is left as it is; null for a blank reason or commit, which is refused. An empty summary clears it.
 */
fun ReviewEdit.apply(text: String): ((BranchReview) -> BranchReview)? {
    val value = text.trim().ifEmpty { null }
    return when (this) {
        ReviewEdit.Summary -> { r -> r.setSummary(value) }
        is ReviewEdit.Reason -> value?.let { { r -> if (r.has(findingId)) r.setState(findingId, FindingState.rejected, reason = it) else r } }
        is ReviewEdit.Commit -> value?.let { { r -> if (r.has(findingId)) r.setState(findingId, FindingState.fixed, commit = it) else r } }
    }
}
