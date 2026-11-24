package dev.myair.canvasview

import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.folders.placementProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewCanvasTest {
    /** rtm holds plan Feature (with notes) and review r (with x) holding canvas inRev; canvas c holds d. */
    private val structure = SessionFolders()
        .createFolder(null, "top", "rtm")
        .createFolder("top", "plan", "Feature", plan = Plan())
        .createFolder("plan", "inPlan", "notes")
        .createFolder("top", "rev", "Review: a")
        .createFolder("rev", "inRev", "x")
        .createFolder(null, "can", "Canvas: c")
        .createFolder("can", "inCan", "d")
    private val reviews = setOf("rev")
    private val canvases = setOf("can")

    private fun parent(place: String?) = canvasParent(structure, reviews, canvases, place)

    @Test fun `a canvas goes into a plain or a plan folder, and the top level without a place`() {
        assertEquals("top", parent("top"))
        assertEquals("plan", parent("plan"))
        assertEquals("inPlan", parent("inPlan"))
        assertNull(parent(null))
    }

    @Test fun `a canvas from inside a review or canvas goes beside the outermost of them`() {
        assertEquals("top", parent("rev"))
        assertEquals("top", parent("inRev"))
        assertNull(parent("can"))
        assertNull(parent("inCan"))
        val nested = structure.createFolder("inCan", "deep", "deep")
        assertNull(canvasParent(nested, reviews, canvases, "deep"))
    }

    @Test fun `where canvasParent puts a canvas the placement rules allow it`() {
        listOf("top", "plan", "inPlan", "rev", "inRev", "can", "inCan", null).forEach { place ->
            val at = parent(place)
            val after = structure.createFolder(at, "new", canvasFolderName(structure, at, "n"))
            assertNull(placementProblem(structure, after, reviews, canvases + "new"), "from $place")
        }
    }

    @Test fun `a gone place throws`() {
        assertThrows<IllegalArgumentException> { parent("gone") }
    }

    @Test fun `the folder name is Canvas and the name, made unique among its siblings`() {
        assertEquals("Canvas: c (2)", canvasFolderName(structure, null, "c"))
        assertEquals("Canvas: c", canvasFolderName(structure, "top", " c "))
        assertEquals("Canvas: a-b", canvasFolderName(structure, "top", "a/b"))
        val two = structure.createFolder(null, "c2", "Canvas: c (2)")
        assertEquals("Canvas: c (3)", canvasFolderName(two, null, "c"))
    }
}
