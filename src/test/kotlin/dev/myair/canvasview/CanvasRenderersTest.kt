package dev.myair.canvasview

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasRenderersTest {
    @Test
    fun `a loaded page of the canvas is chosen first, then any loaded page, then any page`() {
        val slots = listOf(RendererSlot("loading", "c3", false), RendererSlot("other", "c2", true), RendererSlot("own", "c1", true), RendererSlot("own-loading", "c1", false))
        assertEquals("own", chooseValidator(slots, "c1"))
        assertEquals("other", chooseValidator(slots, "c9"))
        assertEquals("loading", chooseValidator(listOf(RendererSlot("loading", "c3", false), RendererSlot("x", "c1", false)), "c1"))
        assertNull(chooseValidator(emptyList<RendererSlot<String>>(), "c1"))
    }
}
