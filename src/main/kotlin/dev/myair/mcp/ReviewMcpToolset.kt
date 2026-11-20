package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.EDT
import dev.myair.review.ReviewChanges
import dev.myair.review.ReviewGit
import dev.myair.review.ReviewService
import dev.myair.review.baseProblem
import dev.myair.review.readGit
import dev.myair.review.reviewJson
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.BranchReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private val COMMIT = Regex("[0-9a-f]{7,40}")

/** Lets the manager of a review folder read and change the review, and a reviewer run report its findings. */
@Suppress("FunctionName")
class ReviewMcpToolset : McpToolset {
    // The default is experimental, which may hide the tools.
    override fun isExperimental() = false

    @McpTool
    @McpDescription(
        "The review of a My Air review folder as JSON: folder (its path), branch, base, dir (the work tree under review), " +
            "pr (the pull request number or null), plan (the path of the linked plan's folder, which my_air_plan_apply takes and " +
            "whose review and blockers follow this review, or null), manager (the session managing the review: session, cwd, project; or null), summary (Markdown or null), " +
            "chapters (the explanation of the changes: title, text in Markdown, files), runs (reviewer runs: id W<n>, reviewer, session, " +
            "job, range (the git range it reviews), started, reported (when it reported, or null), commit (the commit it reported on, " +
            "or null), status: running, waiting (its job needs input), stopped (its job is idle without a report), reported or failed " +
            "(its job ended without a report)), findings (id F<n>, file, category, " +
            "line (of its newest report), severity (the highest of its reports: blocker, major, minor or nit), reports (run, reviewer, " +
            "commit, line, severity, summary, failure_scenario), state (open, accepted, rejected, fixed or posted), reason (why rejected), " +
            "fixCommit (the commit that fixed it), comment (the pull request comment id it was posted as), maybe_fixed (true when the " +
            "work tree changed a line of its file within 3 lines of it since its newest report's commit; the state is not changed)), " +
            "questions (for the author: id Q<n>, text, reviewer, resolved, run), reviewed (the commit of the newest reported run or null), " +
            "head (HEAD of dir), since_reviewed (commits HEAD is past reviewed, null when unknown) and git_error (why git could not be " +
            "read, else null; head, since_reviewed and maybe_fixed are then empty).",
    )
    suspend fun my_air_review_get(
        @McpDescription("The review folder's id, or its path, names joined by /, such as rtm/Review: feature-x; an id is looked up first.") folder: String,
    ): String =
        reviewJsonOf(folder) { service, id -> service.review(id) }

    @McpTool
    @McpDescription(
        "Changes the review of a My Air review folder, all or none. Change the state of a finding only on the user's word. " +
            "changes is a JSON array applied in order: " +
            "{\"op\":\"set_summary\",\"text\":\"...\"} (the branch's summary, Markdown; null clears it), " +
            "{\"op\":\"set_chapters\",\"chapters\":[{\"title\":\"...\",\"text\":\"...\",\"files\":[\"src/a.kt\"]}]} (replaces the " +
            "explanation: chapters in reading order, text Markdown, files repository-relative paths the chapter explains), " +
            "{\"op\":\"set_pr\",\"pr\":123} (the pull request number; null clears it), " +
            "{\"op\":\"set_base\",\"base\":\"origin/main\"} (the branch or commit the branch is reviewed against: changes, the " +
            "explanation's diff and new reviewer runs count from its merge base with HEAD; refused when git in the review's work " +
            "tree cannot count from it; runs already started keep their range), " +
            "{\"op\":\"set_state\",\"id\":\"F3\",\"state\":\"rejected\",\"reason\":\"...\",\"commit\":\"abc1234\",\"comment\":\"...\"} " +
            "(state open, accepted, rejected, fixed or posted; rejected needs reason, fixed needs commit (the fixing commit), posted " +
            "needs comment (the pull request comment id); reason, commit and comment are optional otherwise, and one left out or null " +
            "is cleared), " +
            "{\"op\":\"add_question\",\"text\":\"...\",\"reviewer\":\"...\"} (Q<n>, a question for the author; reviewer optional), " +
            "{\"op\":\"resolve_question\",\"id\":\"Q2\"}. An unknown op or field fails naming the change. Returns the review as my_air_review_get does.",
    )
    suspend fun my_air_review_apply(
        @McpDescription("The review folder's id or path, as for my_air_review_get.") folder: String,
        @McpDescription("The JSON array of changes.") changes: String,
    ): String {
        // A new base is checked against git off the EDT first, so a review is never stored against a base git cannot count from.
        val (before, after) = withReview(folder) { service, id -> service.review(id).let { it to ReviewChanges.apply(it, changes) } }
        if (after.base != before.base) withContext(Dispatchers.IO) { baseProblem(after) }?.let { throw McpExpectedError("base ${after.base} refused: $it") }
        return reviewJsonOf(folder) { service, id -> service.update(id) { ReviewChanges.apply(it, changes) } }
    }

    @McpTool
    @McpDescription(
        "Records the report of a reviewer run of a My Air review folder: call it exactly once, at the end of the review, with " +
            "the folder and run the reviewer's prompt named. A second report of the same run replaces its first, findings and " +
            "questions; nothing is recorded when any part is invalid. Each finding joins a finding of the review in the same file " +
            "and category at most 3 lines away that no other finding of this report joined, else becomes a new one. Returns how many findings and questions were recorded.",
    )
    suspend fun my_air_review_report(
        @McpDescription("The review folder's id, as the reviewer's prompt names it (a path is taken too).") folder: String,
        @McpDescription("The run id the reviewer's prompt names, such as W3.") run: String,
        @McpDescription("The commit reviewed: the end of the run's range, as the reviewer's prompt names it; any other is refused.") commit: String,
        @McpDescription(
            "JSON array of findings, [] when there are none, each {\"file\": repository-relative path, \"line\": line number in " +
                "the reviewed commit, \"category\": a short kind such as bug, security or performance, \"severity\": blocker, major, " +
                "minor or nit, \"summary\": one line, \"failure_scenario\": how it fails, step by step}; file, category and summary " +
                "on one line, all fields required.",
        )
        findings: String,
        @McpDescription("JSON array of strings, questions for the branch's author; [] when there are none.") questions: String,
    ): String = withReview(folder) { service, id ->
        require(COMMIT.matches(commit)) { "commit must be 7 to 40 lowercase hex characters, got '$commit'" }
        val review = service.update(id) { ReviewChanges.report(it, run, commit, findings, questions, Instant.now()) }
        val reported = review.findings.count { f -> f.reports.any { it.run == run } }
        "ok, run $run recorded: $reported findings, ${review.questions.count { it.run == run }} questions"
    }

    /** [reviewJson] of the review [block] returns for the folder [folder] names, its git read now, off the EDT. */
    private suspend fun reviewJsonOf(folder: String, block: (ReviewService, String) -> BranchReview): String {
        val (json, review) = withReview(folder) { service, id ->
            val review = block(service, id)
            val structure = ClaudeSessionsService.getInstance().folders
            val jobs = service.liveJobs
            { git: ReviewGit -> reviewJson(structure, id, review, git, jobs) } to review
        }
        return json(withContext(Dispatchers.IO) { readGit(review) })
    }

    /**
     * Runs [block] on the EDT with the review folder [folder] names, a folder id or else a path; a folder that is no
     * review, an unreadable review or an input error reaches the agent with its message.
     */
    private suspend fun <T> withReview(folder: String, block: (ReviewService, String) -> T): T = withContext(Dispatchers.EDT) {
        val sessions = ClaudeSessionsService.getInstance()
        sessions.foldersError?.let { throw McpExpectedError("Cannot read ${sessions.structureFile}: $it") }
        try {
            val service = ReviewService.getInstance()
            val id = (sessions.folders.find(folder) ?: sessions.folders.findByPath(folder))?.id?.takeIf { it in service.reviews }
                ?: throw IllegalArgumentException("no review folder '$folder'")
            service.reviews.getValue(id).onFailure { throw IllegalArgumentException("the review '$folder' cannot be read: ${it.message}", it) }
            block(service, id)
        } catch (e: IllegalArgumentException) {
            throw McpExpectedError(e.message ?: e.toString())
        }
    }
}
