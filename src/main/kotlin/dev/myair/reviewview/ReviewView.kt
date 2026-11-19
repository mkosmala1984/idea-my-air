package dev.myair.reviewview

import dev.myair.actions.FileChange
import dev.myair.claude.ClaudeSession
import dev.myair.review.Precision
import dev.myair.review.ReviewGit
import dev.myair.review.RunStatus
import dev.myair.review.maybeFixed
import dev.myair.review.reviewedCommit
import dev.myair.review.runStatus
import dev.myair.store.BranchReview
import dev.myair.store.Chapter
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Question
import dev.myair.store.Run
import dev.myair.store.Severity

enum class FindingFilter {
    all, blockers, open;

    /** Open: open or accepted; blockers: blocker-severity findings open or accepted. */
    fun matches(f: Finding) = this == all || f.state in STANDING && (this == open || f.severity == Severity.blocker)

    private companion object {
        val STANDING = setOf(FindingState.open, FindingState.accepted)
    }
}

/**
 * [reviewed]: the commit of the newest reported run; [sinceReviewed]: how many commits [head] is past it, null without
 * one or when [reviewedGone] (git does not know it, as after a rebase); [prUrl] null for an origin not on Bitbucket.
 */
data class Header(
    val branch: String, val base: String, val pr: Int?, val prUrl: String?, val reviewed: String?, val head: String?, val sinceReviewed: Int?, val reviewedGone: Boolean,
)

/** A file a chapter names; [unchanged] when git was read and the file is not among the changed ones, [change] null then. */
data class ChapterFile(val path: String, val change: FileChange?, val unchanged: Boolean)

data class ChapterView(val chapter: Chapter, val files: List<ChapterFile>)

/** [precision] null before any finding of the reviewer was triaged; [session] null until it is learned and scanned. */
data class RunView(val run: Run, val status: RunStatus, val findings: Int, val precision: Precision?, val session: ClaudeSession?)

/** [agreeing] of the [ran] reviewers with a reported run in the review reported it ([agreement]). */
data class FindingView(val finding: Finding, val maybeFixed: Boolean, val agreeing: Int, val ran: Int)

data class ReviewView(
    val header: Header, val summary: String?, val chapters: List<ChapterView>, val unexplained: List<FileChange>, val runs: List<RunView>,
    val findings: List<FindingView>, val questions: List<Question>, val resolvedQuestions: Int,
)

private val BITBUCKET = Regex("""^(?:git@bitbucket\.org:|(?:https|ssh)://(?:[^@/]+@)?bitbucket\.org/)([^/]+)/([^/]+?)(?:\.git)?/?$""")

/** The pull request page of [pr] for an `origin` on Bitbucket (SSH or HTTPS); null for any other host. */
fun bitbucketPrUrl(originUrl: String, pr: Int): String? =
    BITBUCKET.find(originUrl.trim())?.destructured?.let { (workspace, repo) -> "https://bitbucket.org/$workspace/$repo/pull-requests/$pr" }

/**
 * The distinct reviewers a finding has a report of, out of those with a reported run in the review: a later run of new
 * commits only says nothing about older findings, so it takes no agreement away.
 */
private fun agreement(review: BranchReview): (Finding) -> Pair<Int, Int> {
    val ran = review.runs.filter { it.reported != null }.map { it.reviewer }.toSet().size
    return { f -> f.reports.map { it.reviewer }.toSet().size to ran }
}

/** What the review panel shows of [review], its findings narrowed by [filter], [git] null before its git facts are read. */
fun reviewViewOf(
    review: BranchReview, git: ReviewGit?, liveJobs: Set<String>, precision: Map<String, Precision>, filter: FindingFilter, sessions: Map<String, ClaudeSession>,
): ReviewView {
    val reviewed = reviewedCommit(review)
    val header = Header(
        review.branch, review.base, review.pr, review.pr?.let { pr -> git?.origin?.let { bitbucketPrUrl(it, pr) } }, reviewed, git?.head, git?.sinceReviewed,
        reviewedGone = reviewed != null && git?.head != null && git.sinceReviewed == null,
    )
    val changes = git?.state?.changes
    val byPath = changes.orEmpty().associateBy { it.path }
    val chapters = review.chapters.map { c -> ChapterView(c, c.files.map { ChapterFile(it, byPath[it], changes != null && it !in byPath) }) }
    val named = review.chapters.flatMap { it.files }.toSet()
    val runs = review.runs.sortedWith(compareByDescending<Run> { it.started }.thenBy { it.id }).map { run ->
        RunView(run, runStatus(run, liveJobs), review.findings.count { f -> f.reports.any { it.run == run.id } }, precision[run.reviewer]?.takeIf { it.triaged > 0 }, run.session?.let(sessions::get))
    }
    val agreed = agreement(review)
    val findings = review.findings.filter(filter::matches).sortedWith(compareBy<Finding> { it.severity }.thenBy { it.file }.thenBy { it.line }).map { f ->
        val (agreeing, ran) = agreed(f)
        FindingView(f, maybeFixed(f, git?.changed?.get(f.reports.last().commit)), agreeing, ran)
    }
    return ReviewView(
        header, review.summary, chapters, changes.orEmpty().filter { it.path !in named }, runs, findings,
        review.questions.filter { !it.resolved }, review.questions.count { it.resolved },
    )
}

/** The first message of a session fixing [f], one paragraph: its file, line, severity, summary and failure scenario. */
fun findingMessage(f: Finding): String {
    val report = f.reports.last()
    // The scenario may run over several lines; a paragraph keeps the message one argument of one line.
    val scenario = report.failureScenario.replace(Regex("\\s*[\\r\\n]+\\s*"), " ")
    return "Fix this review finding ${f.id} in ${f.file} at line ${f.line} (${f.severity}, ${f.category}): ${report.summary} Failure scenario: $scenario"
}
