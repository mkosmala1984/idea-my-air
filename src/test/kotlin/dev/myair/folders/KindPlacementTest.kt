package dev.myair.folders

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KindPlacementTest {
    private val base = SessionFolders()
        .createFolder(null, "c", "Canvas: a").createFolder(null, "c2", "Canvas: b")
        .createFolder(null, "r", "Review: x").createFolder(null, "p", "plan", plan = Plan()).createFolder(null, "o", "other")
    private val reviews = setOf("r")
    private val canvases = setOf("c", "c2", "new")

    private fun problem(after: SessionFolders) = placementProblem(base, after, reviews, canvases)

    @Test
    fun `a canvas folder holds no plan, review or canvas folder`() {
        assertEquals("'Canvas: a/plan' would be a plan in canvas folder 'Canvas: a', which holds no plan", problem(base.moveFolder("p", "c", 0)))
        assertEquals("'Canvas: a' would be a plan in canvas folder 'Canvas: a', which holds no plan", problem(base.addPlan("c")))
        assertEquals("review folder 'Canvas: a/Review: x' would lie in canvas folder 'Canvas: a'", problem(base.moveFolder("r", "c", 0)))
        assertEquals("canvas folder 'Canvas: a/Canvas: b' would lie in canvas folder 'Canvas: a'", problem(base.moveFolder("c2", "c", 0)))
        assertEquals("canvas folder 'Canvas: a/Canvas: c' would lie in canvas folder 'Canvas: a'", problem(base.createFolder("c", "new", "Canvas: c")))
        // An ordinary folder may lie in a canvas.
        assertNull(problem(base.moveFolder("o", "c", 0)))
    }

    @Test
    fun `a canvas folder lies in no review folder, but may lie in a plan or an ordinary folder`() {
        assertEquals("canvas folder 'Review: x/Canvas: a' would lie in review folder 'Review: x'", problem(base.moveFolder("c", "r", 0)))
        assertNull(problem(base.moveFolder("c", "p", 0)))
        assertNull(problem(base.createFolder("p", "new", "Canvas: c")))
        assertNull(problem(base.moveFolder("c", "o", 0)))
    }

    @Test
    fun `a placement there before does not block, and the review rules stay as they were`() {
        val already = base.moveFolder("c2", "c", 0)
        refuseKindPlacement(already, already.rename("c2", "Canvas: z").createFolder(null, "x", "x"), reviews, canvases)
        assertThrows<IllegalArgumentException> { refuseKindPlacement(base, base.moveFolder("p", "r", 0), reviews, canvases) }
        assertEquals("review folder 'plan/Review: x' would lie in plan folder 'plan'", problem(base.moveFolder("r", "p", 0)))
        // Without canvas ids the canvas rules do not apply.
        assertNull(placementProblem(base, base.moveFolder("c2", "c", 0), reviews))
    }

    @Test
    fun `a review or canvas folder and what lies in it count as in one, a plan or plain folder does not`() {
        val nested = base.createFolder("c", "inC", "notes").createFolder("p", "inP", "notes")
        assertEquals(listOf(true, true, true, false, false, false), listOf("c", "inC", "r", "p", "inP", "o").map { inReviewOrCanvas(nested, reviews, canvases, it) })
        assertEquals(false, inReviewOrCanvas(nested, emptySet(), emptySet(), "inC"))
    }
}
