package dev.myair.reviewview

import dev.myair.actions.Git
import dev.myair.actions.GitFailure
import dev.myair.actions.Worktree
import dev.myair.claude.ClaudeSession
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.toolwindow.WorktreeRow
import dev.myair.toolwindow.reviewStart
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewReviewTest {
    private val structure = SessionFolders()
        .createFolder(null, "top", "rtm")
        .createFolder("top", "plan", "Feature", plan = Plan())
        .createFolder("plan", "inPlan", "notes")
        .createFolder(null, "topPlan", "Top plan", plan = Plan())

    @Test fun `a review from inside a plan goes beside the plan and links it`() {
        assertEquals(ReviewPlace("top", "plan"), reviewParent(structure, "plan"))
        assertEquals(ReviewPlace("top", "plan"), reviewParent(structure, "inPlan"))
        assertEquals(ReviewPlace(null, "topPlan"), reviewParent(structure, "topPlan"))
    }

    @Test fun `a review elsewhere goes into the place`() {
        assertEquals(ReviewPlace("top", null), reviewParent(structure, "top"))
        assertEquals(ReviewPlace(null, null), reviewParent(structure, null))
    }

    @Test fun `a place no longer in the structure throws`() {
        assertThrows<IllegalArgumentException> { reviewParent(structure, "gone") }
    }

    @Test fun `the folder is named after the branch, a slash as a hyphen, a name taken numbered`() {
        assertEquals("Review: feature-x", reviewFolderName(structure, "top", "feature/x"))
        val taken = structure.createFolder("top", "r1", "Review: main").createFolder("top", "r2", "review: MAIN (2)")
        assertEquals("Review: main (3)", reviewFolderName(taken, "top", "main"))
        assertEquals("Review: main", reviewFolderName(taken, null, "main"))
    }

    private val main = Path.of("E:/repo")
    private val worktrees = main.resolve(".claude/worktrees")

    @Test fun `the dir is the worktree holding the branch, else the main checkout holding it, else a new worktree`() {
        val linked = listOf(Worktree(Path.of("E:/wt/a"), "a", false), Worktree(Path.of("E:/wt/detached"), null, false))
        assertEquals(Path.of("E:/wt/a") to false, reviewDir(linked, main, "main", "a", worktrees))
        assertEquals(main to false, reviewDir(linked, main, "main", "main", worktrees))
        assertEquals(worktrees.resolve("review-feature-x") to true, reviewDir(linked, main, "main", "feature/x", worktrees))
        assertEquals(worktrees.resolve("review-main") to true, reviewDir(linked, main, null, "main", worktrees))
    }

    @Test fun `a pr number is plain positive digits or empty, anything else throws`() {
        assertNull(prNumber(""))
        assertEquals(42, prNumber("42"))
        listOf("0", "-3", "+3", " 42", "4a", "99999999999").forEach { assertThrows<IllegalArgumentException>(it) { prNumber(it) } }
    }

    private fun path(vararg items: Any) = TreePath(items.map(::DefaultMutableTreeNode).toTypedArray())

    @Test fun `a review starts from a session's branch and place, a worktree's branch, a project or a folder with a start dir`() {
        val repo = Path.of("E:/repo")
        val wt = repo.resolve("wt")
        val bound = SessionFolders().createFolder(null, "p", "proj", dirs = listOf(repo.toString())).createFolder("p", "f", "sub")
        val session = ClaudeSession("s1", repo, "t", Instant.EPOCH, emptyList(), emptyList(), workDir = wt)
        assertEquals(ReviewStart(wt, "feat", "p"), reviewStart(path(session), bound) { "feat" })
        assertNull(reviewStart(path(session), bound) { null })
        val row = WorktreeRow(wt, repo, "feat", Instant.EPOCH, false)
        assertEquals(ReviewStart(wt, "feat", "p"), reviewStart(path(row), bound) { null })
        assertEquals(ReviewStart(repo, null, "p"), reviewStart(path(repo), bound) { null })
        val p = bound.find("p")!!
        assertEquals(ReviewStart(repo, null, "f"), reviewStart(path(p, p.folders.single()), bound) { null })
        assertNull(reviewStart(path(SessionFolders().createFolder(null, "x", "x").folders.single()), bound) { null })
    }

    @TempDir
    lateinit var temp: Path

    private fun git(repo: Path, vararg args: String) = Git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", *args).trim()

    @Test fun `the review work tree is found or made by real git, a branch missing locally failing with git's message`() {
        val repo = temp.resolve("repo").createDirectories()
        git(repo, "init", "-b", "main")
        git(repo, "commit", "--allow-empty", "-m", "first")
        git(repo, "branch", "feature/x")
        val made = reviewWorkTree(repo, "feature/x", ".wt")
        assertEquals(repo.resolve(".wt/review-feature-x").toRealPath(), made.toRealPath())
        assertEquals("feature/x", git(made, "branch", "--show-current"))
        assertEquals(made.toRealPath(), reviewWorkTree(repo, "feature/x", ".wt").toRealPath())
        assertEquals(repo.toRealPath(), reviewWorkTree(made, "main", ".wt").toRealPath())
        val missing = assertThrows<GitFailure> { reviewWorkTree(repo, "nope", ".wt") }
        assertContains(missing.message!!, "refs/heads/nope")
        assertEquals(false, repo.resolve(".wt/review-nope").exists())
    }
}
