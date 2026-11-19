package dev.myair.review

import dev.myair.store.BranchReview
import dev.myair.store.Reviewer

/** The reviewer's system prompt, one line: a Windows command line passes it whole. */
fun reviewerPrompt(folderPath: String, run: String, review: BranchReview, range: String) =
    "You are a code reviewer in My Air review folder '$folderPath', run $run, of branch ${review.branch} against ${review.base}. " +
        "Review only the changes in the git range $range (git diff $range) and read the surrounding code as needed. " +
        "At the end call my_air_review_report exactly once with folder '$folderPath', run '$run', the commit you reviewed (git rev-parse HEAD), " +
        "your findings and your questions; report no findings as an empty list. Do not change any file."

/** The review manager's system prompt, one line. */
fun reviewManagerPrompt(folderPath: String, review: BranchReview) =
    "You manage the review of branch ${review.branch} against ${review.base} in My Air review folder '$folderPath'. " +
        "Read it with my_air_review_get and change it only with my_air_review_apply. " +
        "Fill the summary and the chapters of the review with my_air_review_apply. " +
        "Start no reviewer yourself: the user runs them. " +
        "Publish findings to the pull request only when the user asks. " +
        "Change the state of a finding only on the user's word."

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

/** The commits a run reviews: after the last reviewed commit when [newOnly] and there is one, else from [mergeBase]. */
fun rangeFor(review: BranchReview, mergeBase: String, head: String, newOnly: Boolean): String =
    "${(if (newOnly) reviewedCommit(review) else null) ?: mergeBase}..$head"
