package dev.myair.reviewview

import dev.myair.planview.parseFolderTabPath
import dev.myair.review.REVIEW_MANAGER_FIRST_MESSAGE
import dev.myair.review.managedReviewFolder
import dev.myair.store.BranchReview
import dev.myair.store.PlanManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ReviewFilesTest {
    private val dir = Path.of("E:/dev/r/.claude/worktrees/review-feature")
    private fun review(manager: PlanManager?) =
        BranchReview("feature", "main", dir, null, null, manager, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

    @Test fun `a review tab path round-trips its folder id`() {
        val id = "3f2a9c1e-0000-4000-8000-000000000001"
        assertEquals(id, parseFolderTabPath(ReviewVirtualFile(id).path))
    }

    private val main = Path.of("E:/dev/r")
    private val mainOf = { d: Path -> assertEquals(dir, d); main }

    @Test fun `a review without a manager starts a new session in its work tree, its project the main checkout, with the first message`() =
        assertEquals(PlanManager("n", dir, main) to listOf("--session-id", "n", "--", REVIEW_MANAGER_FIRST_MESSAGE), reviewManagerStart(review(null), "n", mainOf) { true })

    @Test fun `a manager entry resumes in its stored directory without a message`() {
        val m = PlanManager("s", Path.of("E:/dev/elsewhere"), Path.of("E:/dev/elsewhere"))
        assertEquals(m to listOf("--resume", "s"), reviewManagerStart(review(m), "n", { error("a stored manager keeps its project") }) { true })
    }

    @Test fun `a manager closed before its first message gets the message again`() {
        val m = PlanManager("s", dir, main)
        assertEquals(m to listOf("--session-id", "s", "--", REVIEW_MANAGER_FIRST_MESSAGE), reviewManagerStart(review(m), "n", { error("unused") }) { false })
    }

    @Test fun `a review's manager opens its review and a review that cannot be read names none`() {
        val reviews = mapOf(
            "a" to Result.success(review(PlanManager("s", dir, dir))),
            "b" to Result.failure(IllegalStateException("broken")),
            "c" to Result.success(review(null)),
        )
        assertEquals("a", managedReviewFolder(reviews, "s"))
        assertNull(managedReviewFolder(reviews, "t"))
    }
}
