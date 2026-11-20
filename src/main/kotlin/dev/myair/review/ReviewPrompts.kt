package dev.myair.review

import dev.myair.actions.RangeSize
import dev.myair.store.BranchReview
import dev.myair.store.Reviewer

/** The reviewer's system prompt, one line: a Windows command line passes it whole. It names the folder by id, which a rename keeps. */
fun reviewerPrompt(folderId: String, run: String, review: BranchReview, mergeBase: String, head: String, range: String, size: RangeSize) =
    "You are a code reviewer in My Air review folder $folderId, run $run, of branch ${review.branch} against ${review.base} (merge base $mergeBase, head $head). " +
        "Review only the changes in the git range $range (${size.commits} commits, ${size.files} files; git diff $range) and read the surrounding code as needed. " +
        "If git diff $range is empty, call my_air_review_report with no findings and one question saying so, and stop. " +
        "At the end call my_air_review_report exactly once with folder '$folderId', run '$run', commit '${range.substringAfter("..")}' (the end of the range), " +
        "your findings and your questions; report no findings as an empty list. Do not change any file."

/** The review manager's system prompt, one line. It names the folder by id, which a rename keeps. */
fun reviewManagerPrompt(folderId: String, review: BranchReview) =
    "You manage the review of branch ${review.branch} against ${review.base} (work tree ${review.dir}, ${review.pr?.let { "PR #$it" } ?: "no PR"}) in My Air review folder $folderId. " +
        "Read it with my_air_review_get and change it only with my_air_review_apply, passing folder '$folderId'. " +
        "The review's base is the folder's base, ${review.base}; it wins over branch.${review.branch}.myAirBase. Its range is " +
        "merge base..HEAD; my_air_review_get gives the current merge_base, range, commits and files. " +
        "Fill the summary and the chapters of the review with my_air_review_apply. " +
        "Start no reviewer yourself: the user runs them. " +
        "Publish findings to the pull request only when the user asks. " +
        "Change the state of a finding only on the user's word."

/** The first message of a review's manager, sent once, when its session starts with no transcript. */
const val REVIEW_MANAGER_FIRST_MESSAGE =
    "Read the review with my_air_review_get and the branch's changes against its base, then fill the review's summary and " +
        "its chapters, every changed file in one of them, with my_air_review_apply. Then tell me in a few lines what the branch does and where to look first."

/** `claude --bg` ignores `--session-id`, so none is passed; the prompt follows `--`. */
fun reviewerArgs(reviewer: Reviewer, prompt: String): List<String> = buildList {
    add("--bg")
    reviewer.model?.let { addAll(listOf("--model", it)) }
    reviewer.effort?.let { addAll(listOf("--effort", it)) }
    addAll(listOf("--append-system-prompt", prompt, "--", reviewer.prompt))
}

private val BACKGROUNDED = Regex("^backgrounded · ([0-9a-f]{8})$")

/** The job id on the first line of what `claude --bg` printed. */
fun parseJobId(stdout: String): String =
    BACKGROUNDED.find(stdout.lineSequence().first().trimEnd())?.groupValues?.get(1) ?: error("claude --bg printed no job id:\n$stdout")

/**
 * The commits a run reviews: after the last reviewed commit when [newOnly] and there is one, else from [mergeBase]. A
 * range starting at [head] (a report may name it abbreviated) is empty and throws: no run is recorded for it.
 */
fun rangeFor(review: BranchReview, mergeBase: String, head: String, newOnly: Boolean): String {
    val start = (if (newOnly) reviewedCommit(review) else null) ?: mergeBase
    require(!head.startsWith(start)) { "empty range: $start..$head" }
    return "$start..$head"
}

/**
 * Why a run of [git]'s review cannot start, null when it can: git not read, its error, the state's problem (a base
 * holding HEAD among them), or for [newOnly] no commit since the [reviewed] one.
 */
fun runProblem(git: ReviewGit?, reviewed: String?, newOnly: Boolean): String? = when {
    git == null -> "Git not read yet"
    git.error != null -> git.error
    git.state?.problem != null -> git.state.problem
    newOnly && git.sinceReviewed == 0 -> "No commits since ${checkNotNull(reviewed).take(7)}"
    else -> null
}
