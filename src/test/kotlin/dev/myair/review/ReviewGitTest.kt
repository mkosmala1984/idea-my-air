package dev.myair.review

import dev.myair.actions.Git
import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.Report
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real `git` on PATH against a repository in a temp directory. */
class ReviewGitTest {
    @TempDir
    lateinit var dir: Path

    private fun git(repo: Path, vararg args: String) = Git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", *args).trim()

    /** A repository with `main`, `other` (one commit off main's first) and `feature` (one file off main) checked out. */
    private fun repo(): Path = dir.resolve("repo").createDirectories().also {
        git(it, "init", "-b", "main")
        git(it, "commit", "--allow-empty", "-m", "first")
        git(it, "branch", "other")
        git(it, "commit", "--allow-empty", "-m", "second")
        git(it, "checkout", "-b", "feature")
        it.resolve("a.txt").writeText("1\n")
        git(it, "add", ".")
        git(it, "commit", "-m", "feature")
    }

    private fun review(repo: Path, branch: String = "feature", base: String = "main") =
        BranchReview(branch, base, repo, null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

    @Test fun `a work tree with another branch checked out than the review's is an error`() {
        val repo = repo()
        git(repo, "checkout", "main")
        val git = readGit(review(repo))
        assertEquals("IllegalStateException: $repo has main checked out, not feature", git.error)
        assertNull(git.state)
    }

    @Test fun `changes are counted against the review's base, not the branch's default`() {
        val repo = repo()
        assertEquals(git(repo, "rev-parse", "main"), readGit(review(repo)).state?.mergeBase)
        val againstOther = readGit(review(repo, base = "other")).state!!
        assertEquals(git(repo, "rev-parse", "other"), againstOther.mergeBase)
        assertEquals("other", againstOther.base)
    }

    @Test fun `a base naming no commit, sharing no history or unreadable is a problem, an existing one none`() {
        val repo = repo()
        assertNull(baseProblem(review(repo, base = "other")))
        assertNull(baseProblem(review(repo, base = git(repo, "rev-parse", "--short", "main"))))
        assertEquals("Base gone no longer exists", baseProblem(review(repo, base = "gone")))
        git(repo, "checkout", "--orphan", "lonely")
        git(repo, "commit", "--allow-empty", "-m", "alone")
        git(repo, "checkout", "feature")
        assertEquals("Base lonely shares no history with feature", baseProblem(review(repo, base = "lonely")))
        git(repo, "checkout", "main")
        assertEquals("IllegalStateException: $repo has main checked out, not feature", baseProblem(review(repo, base = "other")))
    }

    @Test fun `the stamp changes with a commit, which leaves HEAD's file as it was, and with review json`() {
        val repo = repo()
        val file = dir.resolve("review.json").also { it.writeText("{}") }
        val before = gitStamp(review(repo), file)
        Thread.sleep(20)
        git(repo, "commit", "--allow-empty", "-m", "more")
        val after = gitStamp(review(repo), file)
        assertEquals(before.head, after.head)
        assertNotEquals(before, after)
        val kept = ReviewGit(null, null, null, emptyMap(), null, "kept")
        fun reread(last: Map<String, GitStamp>) = readGits(mapOf("r" to review(repo)), { file }, last, mapOf("r" to kept)).second.getValue("r")
        assertNotEquals(kept, reread(mapOf("r" to before)), "a changed stamp is due")
        assertEquals(kept, reread(mapOf("r" to after)), "the same stamp is not")
        assertNotEquals(kept, reread(emptyMap()), "a review never read is due")
        assertEquals(GitStamp(null, null, null, null, after.review, emptyMap()), gitStamp(review(dir.resolve("gone")), file))
    }

    @Test fun `editing a file a finding names makes the review due, editing another file does not`() {
        val repo = repo()
        repo.resolve("b.txt").writeText("one")
        val file = dir.resolve("review.json").also { it.writeText("{}") }
        val finding = Finding("F1", "a.txt", "bug", listOf(Report("W1", "a", "c", 1, Severity.minor, "s", "x")))
        val reviewed = review(repo).copy(findings = listOf(finding, finding.copy(id = "F2", file = "gone.txt")))
        val read = gitStamp(reviewed, file)
        assertEquals(null, read.files.getValue("gone.txt"), "a missing file stamps as absent")
        val kept = ReviewGit(null, null, null, emptyMap(), null, "kept")
        fun reread() = readGits(mapOf("r" to reviewed), { file }, mapOf("r" to read), mapOf("r" to kept)).second.getValue("r")
        Thread.sleep(20)
        repo.resolve("b.txt").writeText("two")
        assertEquals(kept, reread(), "another file is not due")
        repo.resolve("a.txt").writeText("two")
        assertNotEquals(kept, reread(), "a named file is due")
    }

    @Test fun `a review whose stamp throws fails alone, with its error, and does not stop another's read`() {
        val repo = repo()
        val bad = Finding("F1", "a|b", "bug", listOf(Report("W1", "a", "c", 1, Severity.minor, "s", "x")))
        val reviews = mapOf("good" to review(repo), "bad" to review(repo).copy(findings = listOf(bad)))
        val (stamps, facts) = readGits(reviews, { dir.resolve("$it.json").also { f -> f.writeText("{}") } }, emptyMap(), emptyMap())
        assertEquals(setOf("good"), stamps.keys)
        assertNull(facts.getValue("good").error)
        assertNotNull(facts.getValue("good").state)
        assertTrue(facts.getValue("bad").error!!.startsWith("InvalidPathException"), facts.getValue("bad").error)
    }
}
