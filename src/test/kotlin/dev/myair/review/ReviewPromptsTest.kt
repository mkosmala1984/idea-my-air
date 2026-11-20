package dev.myair.review

import dev.myair.actions.GitState
import dev.myair.actions.RangeSize
import dev.myair.store.BranchReview
import dev.myair.store.Reviewer
import dev.myair.store.Run
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewPromptsTest {
    private fun review(runs: List<Run> = emptyList()) =
        BranchReview("feat", "main", Path.of("."), null, null, null, null, emptyList(), runs, emptyList(), emptyList(), emptyMap())

    private fun run(id: String, reported: String?, commit: String?) = Run(id, "a", null, null, "x..y", "2026-01-01", reported, commit)

    @Test
    fun `reviewer args put the prompt last after a double dash`() {
        assertEquals(
            listOf("--bg", "--model", "opus", "--effort", "high", "--append-system-prompt", "SYS", "--", "go"),
            reviewerArgs(Reviewer("a", "go", "opus", "high"), "SYS"),
        )
        assertEquals(listOf("--bg", "--append-system-prompt", "SYS", "--", "go"), reviewerArgs(Reviewer("a", "go"), "SYS"))
    }

    @Test
    fun `the reviewer prompt names folder, run, range and the report tool on one line`() {
        val p = reviewerPrompt("4f1c-id", "W3", review(), "mb", "def", "abc..def", RangeSize(3, 2))
        listOf(
            "folder '4f1c-id'", "W3", "feat", "against main (merge base mb, head def)", "abc..def (3 commits, 2 files;", "commit 'def'",
            "my_air_review_report", "If git diff abc..def is empty",
        ).forEach { assertTrue(it in p, it) }
        assertFalse('\n' in p)
    }

    @Test
    fun `the manager prompt names both review tools and forbids starting reviewers`() {
        val p = reviewManagerPrompt("4f1c-id", review())
        assertTrue("work tree ." in p && "no PR" in p)
        assertTrue("folder '4f1c-id'" in p, "the manager passes the folder id to the tools, which a rename keeps")
        assertTrue("PR #42" in reviewManagerPrompt("4f1c-id", review().copy(pr = 42)))
        listOf(
            "my_air_review_get", "my_air_review_apply", "Start no reviewer", "only when the user asks", "only on the user's word",
            "it wins over branch.feat.myAirBase", "merge base..HEAD", "my_air_review_get gives the current merge_base, range, commits and files",
        ).forEach { assertTrue(it in p, it) }
        assertFalse('\n' in p)
    }

    @Test
    fun `the range is new commits only when asked and a reviewed commit exists`() {
        val reviewed = review(listOf(run("W1", "2026-01-02", "old"), run("W2", null, null)))
        assertEquals("old..h", rangeFor(reviewed, "mb", "h", newOnly = true))
        assertEquals("mb..h", rangeFor(reviewed, "mb", "h", newOnly = false))
        assertEquals("mb..h", rangeFor(review(), "mb", "h", newOnly = true))
    }

    @Test
    fun `a range that starts at HEAD is refused`() {
        assertThrows(IllegalArgumentException::class.java) { rangeFor(review(), "h1", "h1", newOnly = false) }
        val reviewed = review(listOf(run("W1", "2026-01-02", "abc1234")))
        assertThrows(IllegalArgumentException::class.java) { rangeFor(reviewed, "mb", "abc1234ff00", newOnly = true) }
        assertEquals("abc1234..def", rangeFor(reviewed, "mb", "def", newOnly = true))
    }

    @Test
    fun `run problem names why a run cannot start`() {
        val state = GitState("feat", false, Path.of("."), "main", false, "mb", emptyList(), null)
        fun git(state: GitState?, since: Int? = null, error: String? = null) = ReviewGit(state, "h", since, emptyMap(), null, null, null, null, error)
        assertEquals("Git not read yet", runProblem(null, null, newOnly = false))
        assertEquals("boom", runProblem(git(null, error = "boom"), null, newOnly = false))
        val stacked = state.copy(mergeBase = null, changes = null, problem = "Base x already contains feat: nothing to review")
        assertEquals("Base x already contains feat: nothing to review", runProblem(git(stacked), null, newOnly = false))
        assertEquals("No commits since abc1234", runProblem(git(state, since = 0), "abc1234ff00", newOnly = true))
        assertEquals(null, runProblem(git(state, since = 0), "abc1234ff00", newOnly = false))
        assertEquals(null, runProblem(git(state, since = 2), "abc1234ff00", newOnly = true))
    }

    @Test
    fun `parseJobId reads the id claude prints and throws on other output`() {
        val out = "backgrounded · f2b8ed26\n  claude agents             list sessions\n  claude attach f2b8ed26    open in this terminal\n"
        assertEquals("f2b8ed26", parseJobId(out))
        assertThrows(IllegalStateException::class.java) { parseJobId("backgrounded - f2b8ed26") }
        assertThrows(IllegalStateException::class.java) { parseJobId("") }
    }
}
