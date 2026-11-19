package dev.myair.reviewview

import dev.myair.actions.FileChange
import dev.myair.actions.GitState
import dev.myair.review.ReviewGit
import dev.myair.store.BranchReview
import dev.myair.store.Chapter
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.Report
import dev.myair.store.Run
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewViewTest {
    private fun change(path: String) = FileChange(path, FileChange.Kind.MODIFIED, 3, 1, FileChange.Stage.COMMITTED)

    private fun git(changes: List<FileChange>?, head: String? = "h", sinceReviewed: Int? = null, origin: String? = null) =
        ReviewGit(GitState("b", false, Path.of("E:/d"), "main", false, "mb", changes, null), head, sinceReviewed, emptyMap(), origin, null)

    private fun run(id: String, reviewer: String, started: String = "2026-11-19T10:00:00Z", reported: String? = "2026-11-19T10:10:00Z", commit: String? = "c1") =
        Run(id, reviewer, null, null, "mb..h", started, reported, commit)

    private fun finding(id: String, severity: Severity = Severity.minor, state: FindingState = FindingState.open, reports: List<Pair<String, String>> = listOf("W1" to "r")) =
        Finding(id, "a.kt", "bug", reports.map { (run, reviewer) -> Report(run, reviewer, "c1", 10, severity, "s", "x") }, state, "why", "f1", "7")

    private fun review(chapters: List<Chapter> = emptyList(), runs: List<Run> = emptyList(), findings: List<Finding> = emptyList(), pr: Int? = null) =
        BranchReview("b", "main", Path.of("E:/d"), pr, null, null, null, chapters, runs, findings, emptyList(), emptyMap())

    private fun view(review: BranchReview, git: ReviewGit? = null, filter: FindingFilter = FindingFilter.all) =
        reviewViewOf(review, git, emptySet(), emptyMap(), filter, emptyMap())

    @Test fun `files no chapter names go to not explained`() {
        val v = view(review(listOf(Chapter("Model", "text", listOf("a.kt")))), git(listOf(change("a.kt"), change("b.kt"))))
        assertEquals(listOf("b.kt"), v.unexplained.map { it.path })
    }

    @Test fun `a chapter file that did not change is marked`() {
        val r = review(listOf(Chapter("Model", "text", listOf("a.kt", "gone.kt"))))
        val files = view(r, git(listOf(change("a.kt")))).chapters.single().files
        assertEquals(listOf(ChapterFile("a.kt", change("a.kt"), false), ChapterFile("gone.kt", null, true)), files)
        assertFalse(view(r, git(null)).chapters.single().files.any { it.unchanged }, "nothing is unchanged before the changes are read")
    }

    @Test fun `findings filter to blockers and to open ones`() {
        val r = review(findings = listOf(
            finding("F1", Severity.blocker), finding("F2", Severity.blocker, FindingState.accepted), finding("F3", Severity.blocker, FindingState.rejected),
            finding("F4", Severity.major), finding("F5", Severity.nit, FindingState.fixed),
        ))
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5"), view(r).findings.map { it.finding.id })
        assertEquals(listOf("F1", "F2"), view(r, filter = FindingFilter.blockers).findings.map { it.finding.id })
        assertEquals(listOf("F1", "F2", "F4"), view(r, filter = FindingFilter.open).findings.map { it.finding.id })
    }

    @Test fun `a finding shows how many reviewers of the latest runs reported it over how many ran`() {
        val runs = listOf(
            run("W1", "a"), run("W2", "b"), run("W3", "c"), run("W4", "d"),
            // d ran again on new commits only, which says nothing of the older finding; e is still running.
            run("W5", "d", started = "2026-11-19T11:00:00Z"), run("W6", "e", started = "2026-11-19T11:00:00Z", reported = null, commit = null),
        )
        // c reported nothing about it; a reported it twice in one run, counted once.
        val f = finding("F1", reports = listOf("W1" to "a", "W1" to "a", "W2" to "b", "W4" to "d"))
        val shown = view(review(runs = runs, findings = listOf(f))).findings.single()
        assertEquals(3 to 4, shown.agreeing to shown.ran)
    }

    @Test fun `the header tells a reviewed commit that is gone`() {
        val r = review(runs = listOf(run("W1", "a")))
        val gone = view(r, git(emptyList(), sinceReviewed = null)).header
        assertEquals("c1", gone.reviewed)
        assertTrue(gone.reviewedGone)
        val known = view(r, git(emptyList(), sinceReviewed = 2)).header
        assertFalse(known.reviewedGone)
        assertEquals(2, known.sinceReviewed)
        assertFalse(view(r).header.reviewedGone, "before git is read nothing is gone")
        assertFalse(view(review(), git(emptyList())).header.reviewedGone, "nothing reviewed, nothing gone")
    }

    @Test fun `a bitbucket origin gives a pr url and another origin none`() {
        val url = "https://bitbucket.org/ws/repo/pull-requests/12"
        assertEquals(url, bitbucketPrUrl("git@bitbucket.org:ws/repo.git", 12))
        assertEquals(url, bitbucketPrUrl("https://user@bitbucket.org/ws/repo.git", 12))
        assertEquals(url, bitbucketPrUrl("https://bitbucket.org/ws/repo", 12))
        assertNull(bitbucketPrUrl("git@github.com:ws/repo.git", 12))
        assertNull(bitbucketPrUrl("https://github.com/ws/repo.git", 12))
        assertEquals(url, view(review(pr = 12), git(emptyList(), origin = "git@bitbucket.org:ws/repo.git")).header.prUrl)
    }

    @Test fun `a finding message is one paragraph naming file line severity summary and failure scenario`() {
        val f = Finding("F3", "src/a.kt", "bug", listOf(Report("W1", "r", "c1", 42, Severity.major, "Null deref.", "Given x,\nthen y.")))
        assertEquals("Fix this review finding F3 in src/a.kt at line 42 (major, bug): Null deref. Failure scenario: Given x, then y.", findingMessage(f))
    }
}
