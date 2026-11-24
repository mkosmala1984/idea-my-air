package dev.myair.canvas

import dev.myair.store.Canvas
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoveBoardElementTest {
    private val doc = """
        root = Stack([b])
        b = Board([g, n, f])
        g = Shape("group", "G", {x: 0, y: 0}, [s], "row", 2)
        s = Shape("rect", "S", {w: 4, h: 2})
        n = Note("a, (b) {c}", {rightOf: "g", gap: 2, w: 6}, "pink")
        f = Frame("F", fbox, [TextContent("x")])
        fbox = {below: "g"}
    """.trimIndent()

    @Test
    fun `a moved element gets an absolute pinned box, its other arguments untouched`() {
        val merge = moveBoardElement(doc, "n", 12, -3, 6, null)
        assertEquals("n = Note(\"a, (b) {c}\", {x: 12, y: -3, w: 6, pinned: true}, \"pink\")", merge.text.lines()[4])
        assertEquals(listOf("n"), merge.set)
        assertEquals(emptyList(), merge.removed)
    }

    @Test
    fun `the box of a Shape is its third argument, and children keep theirs`() {
        val text = moveBoardElement(doc, "g", 1, 2, null, null).text
        assertEquals("g = Shape(\"group\", \"G\", {x: 1, y: 2, pinned: true}, [s], \"row\", 2)", text.lines()[2])
        assertEquals("s = Shape(\"rect\", \"S\", {w: 4, h: 2})", text.lines()[3])
    }

    @Test
    fun `a box given by reference is replaced inline and the old one is dropped`() {
        val merge = moveBoardElement(doc, "f", 0, 9, null, 5)
        assertEquals("f = Frame(\"F\", {x: 0, y: 9, h: 5, pinned: true}, [TextContent(\"x\")])", merge.text.lines().last())
        assertEquals(listOf("fbox"), merge.removed)
    }

    @Test
    fun `a missing statement or one that is no board element is refused`() {
        assertEquals("no statement 'ghost'", assertFailsWith<IllegalArgumentException> { moveBoardElement(doc, "ghost", 0, 0, null, null) }.message)
        assertEquals("'b' is Board, not a Shape, Note or Frame", assertFailsWith<IllegalArgumentException> { moveBoardElement(doc, "b", 0, 0, null, null) }.message)
        assertEquals("'fbox' is no component, not a Shape, Note or Frame", assertFailsWith<IllegalArgumentException> { moveBoardElement(doc, "fbox", 0, 0, null, null) }.message)
    }

    @Test
    fun `moveInFrame changes the frame in place`() {
        val canvas = addFrame(Canvas(null, 1, emptyList()), "Board", doc, Instant.parse("2026-11-24T10:00:00Z"))
        val moved = moveInFrame(canvas, "C1", "n", 3, 4, null, null)
        assertEquals("C1", moved.newest!!.id)
        assertEquals("n = Note(\"a, (b) {c}\", {x: 3, y: 4, pinned: true}, \"pink\")", moved.newest!!.openui.lines()[4])
    }
}
