package dev.myair.reviewview

import dev.myair.actions.Git
import dev.myair.actions.GitFailure
import dev.myair.actions.Worktree
import dev.myair.claude.ClaudeSession
import dev.myair.folders.FolderChanges
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.folders.refuseKindPlacement
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
        assertEquals(ReviewPlace("top", "plan"), reviewParent(structure, emptySet(), "plan"))
        assertEquals(ReviewPlace("top", "plan"), reviewParent(structure, emptySet(), "inPlan"))
        assertEquals(ReviewPlace(null, "topPlan"), reviewParent(structure, emptySet(), "topPlan"))
    }

    @Test fun `a review elsewhere goes into the place`() {
        assertEquals(ReviewPlace("top", null), reviewParent(structure, emptySet(), "top"))
        assertEquals(ReviewPlace(null, null), reviewParent(structure, emptySet(), null))
    }

    @Test fun `a review from inside a review goes beside that review, a plan linked only from a plan folder`() {
        val withReviews = structure.createFolder("top", "rev", "Review: a").createFolder("rev", "inRev", "x").createFolder(null, "topRev", "Review: b")
            .createFolder("topPlan", "planRev", "Review: c")
        val reviews = setOf("rev", "topRev", "planRev")
        assertEquals(ReviewPlace("top", null), reviewParent(withReviews, reviews, "rev"))
        assertEquals(ReviewPlace("top", null), reviewParent(withReviews, reviews, "inRev"))
        assertEquals(ReviewPlace(null, null), reviewParent(withReviews, reviews, "topRev"))
        assertEquals(ReviewPlace(null, "topPlan"), reviewParent(withReviews, reviews, "planRev"))
    }

    @Test fun `a review from inside a canvas goes beside the canvas, and beside a plan holding the canvas, linked to it`() {
        val withCanvases = structure.createFolder("top", "can", "Canvas: a").createFolder("can", "inCan", "x").createFolder("plan", "planCan", "Canvas: b")
        val canvases = setOf("can", "planCan")
        assertEquals(ReviewPlace("top", null), reviewParent(withCanvases, emptySet(), "can", canvases))
        assertEquals(ReviewPlace("top", null), reviewParent(withCanvases, emptySet(), "inCan", canvases))
        assertEquals(ReviewPlace("top", "plan"), reviewParent(withCanvases, emptySet(), "planCan", canvases))
    }

    @Test fun `a plan made, created or moved into a review folder by a change list is refused naming both, one there before is not`() {
        val base = SessionFolders().createFolder(null, "r", "Review: a").createFolder("r", "sub", "sub").createFolder(null, "p", "plan", plan = Plan())
        val reviews = setOf("r")
        fun apply(changes: String) = FolderChanges.apply(base, changes, emptySet(), emptySet()).let { refuseKindPlacement(base, it, reviews) }
        listOf(
            """[{"op":"add_plan","path":"Review: a"}]""",
            """[{"op":"add_plan","path":"Review: a/sub"}]""",
            """[{"op":"create","path":"Review: a/new","plan":true}]""",
            """[{"op":"move","path":"plan","to":"Review: a/sub"}]""",
        ).forEach { changes ->
            val e = assertThrows<IllegalArgumentException>(changes) { apply(changes) }
            assertContains(e.message!!, "review folder 'Review: a'")
        }
        apply("""[{"op":"create","path":"plan2","plan":true},{"op":"create","path":"Review: a/plain"}]""")
        val already = base.addPlan("sub")
        refuseKindPlacement(already, already.rename("r", "Review: z"), reviews)
    }

    @Test fun `a review folder moved or created into a review or plan folder is refused, one there before is not`() {
        val base = SessionFolders().createFolder(null, "r", "Review: a").createFolder(null, "r2", "Review: b").createFolder(null, "p", "plan", plan = Plan())
        val reviews = setOf("r", "r2", "new")
        fun refused(after: SessionFolders) = assertThrows<IllegalArgumentException> { refuseKindPlacement(base, after, reviews) }.message!!
        assertEquals("review folder 'Review: a/Review: b' would lie in review folder 'Review: a'", refused(base.moveFolder("r2", "r", 0)))
        assertEquals("review folder 'plan/Review: b' would lie in plan folder 'plan'", refused(base.moveFolder("r2", "p", 0)))
        assertEquals("review folder 'plan/Review: c' would lie in plan folder 'plan'", refused(base.createFolder("p", "new", "Review: c")))
        assertEquals("'Review: a/plan' would be a plan in review folder 'Review: a', which holds no plan", refused(base.moveFolder("p", "r", 0)))
        refuseKindPlacement(base, base.createFolder(null, "new", "Review: c"), reviews)
        val already = base.moveFolder("r2", "r", 0)
        refuseKindPlacement(already, already.rename("r2", "Review: z").createFolder(null, "x", "x"), reviews)
    }

    @Test fun `a place no longer in the structure throws`() {
        assertThrows<IllegalArgumentException> { reviewParent(structure, emptySet(), "gone") }
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
        listOf("0", "-3", "+3", " 42", "4a", "99999999999", "٣").forEach { assertThrows<IllegalArgumentException>(it) { prNumber(it) } }
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
