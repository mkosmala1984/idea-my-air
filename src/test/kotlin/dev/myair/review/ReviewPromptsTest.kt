package dev.myair.review

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
        val p = reviewerPrompt("4f1c-id", "W3", review(), "abc..def")
        listOf("folder '4f1c-id'", "W3", "feat", "main", "abc..def", "commit 'def'", "my_air_review_report").forEach { assertTrue(it in p, it) }
        assertFalse('\n' in p)
    }

    @Test
    fun `the manager prompt names both review tools and forbids starting reviewers`() {
        val p = reviewManagerPrompt("Work/Rev", review())
        assertTrue("work tree ." in p && "no PR" in p)
        assertTrue("PR #42" in reviewManagerPrompt("Work/Rev", review().copy(pr = 42)))
        listOf("my_air_review_get", "my_air_review_apply", "Start no reviewer", "only when the user asks", "only on the user's word").forEach { assertTrue(it in p, it) }
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
    fun `parseJobId reads the id claude prints and throws on other output`() {
        val out = "backgrounded · f2b8ed26\n  claude agents             list sessions\n  claude attach f2b8ed26    open in this terminal\n"
        assertEquals("f2b8ed26", parseJobId(out))
        assertThrows(IllegalStateException::class.java) { parseJobId("backgrounded - f2b8ed26") }
        assertThrows(IllegalStateException::class.java) { parseJobId("") }
    }
}
