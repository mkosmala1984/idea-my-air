package dev.myair.reviewview

import dev.myair.planview.parseFolderTabPath
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

    @Test fun `a review without a manager starts a new session in its work tree`() =
        assertEquals(PlanManager("n", dir, dir) to listOf("--session-id", "n"), reviewManagerStart(review(null), "n") { true })

    @Test fun `a manager entry resumes in its stored directory`() {
        val m = PlanManager("s", Path.of("E:/dev/elsewhere"), Path.of("E:/dev/elsewhere"))
        assertEquals(m to listOf("--resume", "s"), reviewManagerStart(review(m), "n") { true })
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
