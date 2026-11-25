package dev.myair.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditBoardTest {
    private val doc = """
        root = Stack([b])
        b = Board([g, n, f, e1], 24)
        g = Shape("group", "G", {x: 0, y: 0}, [s, t], "row", 2)
        s = Shape("rect", "S", {w: 4, h: 2})
        t = Shape("rect", "T", {w: 4, h: 2})
        n = Note("a \"b\"", {x: 20, y: 0}, "pink")
        f = Frame("F", {x: 0, y: 10}, [tbl])
        tbl = Table([Col("A", [1])])
        e1 = Edge("s", "n", "uses")
        note1 = Note("orphan", {x: 0, y: 0})
    """.trimIndent()

    @Test
    fun `add puts a pinned note or shape at the end of the board's items, named with the first free number`() {
        val note = editBoard(doc, BoardEdit.Add("b", "note", 3, 4))
        assertEquals("b = Board([g, n, f, e1, note2], 24)", note.text.lines()[1])
        assertEquals("note2 = Note(\"New note\", {x: 3, y: 4, w: 8, pinned: true}, \"yellow\")", note.text.lines().last())
        val shape = editBoard(doc, BoardEdit.Add("b", "diamond", -1, 2))
        assertEquals("shape1 = Shape(\"diamond\", \"New diamond\", {x: -1, y: 2, w: 6, h: 3, pinned: true})", shape.text.lines().last())
        assertFailsWith<IllegalArgumentException> { editBoard(doc, BoardEdit.Add("b", "star", 0, 0)) }
        assertFailsWith<IllegalArgumentException> { editBoard(doc, BoardEdit.Add("n", "note", 0, 0)) }
    }

    @Test
    fun `add fills an empty board`() {
        val text = editBoard("root = Stack([b])\nb = Board([])", BoardEdit.Add("b", "rect", 0, 0)).text
        assertEquals("b = Board([shape1])", text.lines()[1])
    }

    @Test
    fun `connect adds an edge between two existing elements`() {
        val merge = editBoard(doc, BoardEdit.Connect("b", "t", "f"))
        assertEquals("b = Board([g, n, f, e1, edge1], 24)", merge.text.lines()[1])
        assertEquals("edge1 = Edge(\"t\", \"f\")", merge.text.lines().last())
        assertFailsWith<IllegalArgumentException> { editBoard(doc, BoardEdit.Connect("b", "t", "ghost")) }
    }

    @Test
    fun `remove takes elements out of their lists, with what they hold and the edges to them`() {
        val merge = editBoard(doc, BoardEdit.Remove(listOf("g", "f")))
        assertEquals("b = Board([n], 24)", merge.text.lines()[1])
        assertEquals(setOf("g", "s", "t", "f", "tbl", "e1", "note1"), merge.removed.toSet())
        val child = editBoard(doc, BoardEdit.Remove(listOf("t")))
        assertEquals("g = Shape(\"group\", \"G\", {x: 0, y: 0}, [s], \"row\", 2)", child.text.lines()[2])
        val edge = editBoard(doc, BoardEdit.Remove(listOf("e1")))
        assertEquals("b = Board([g, n, f], 24)", edge.text.lines()[1])
    }

    @Test
    fun `remove keeps what something else still uses`() {
        val shared = doc.replace("note1 = Note(\"orphan\", {x: 0, y: 0})", "f2 = Frame(\"F2\", {x: 9, y: 10}, [tbl])").replace("[g, n, f, e1]", "[g, n, f, f2, e1]")
        val text = editBoard(shared, BoardEdit.Remove(listOf("f"))).text
        assert("tbl = Table([Col(\"A\", [1])])" in text.lines())
        assert(text.lines().none { it.startsWith("f = ") })
    }

    @Test
    fun `text replaces a note's text, a shape's label and a frame's title, escaped`() {
        assertEquals("n = Note(\"line\\n\\\"two\\\"\", {x: 20, y: 0}, \"pink\")", editBoard(doc, BoardEdit.Text("n", "line\n\"two\"")).text.lines()[5])
        assertEquals("s = Shape(\"rect\", \"Source\", {w: 4, h: 2})", editBoard(doc, BoardEdit.Text("s", "Source")).text.lines()[3])
        assertEquals("f = Frame(\"Stats\", {x: 0, y: 10}, [tbl])", editBoard(doc, BoardEdit.Text("f", "Stats")).text.lines()[6])
        assertFailsWith<IllegalArgumentException> { editBoard(doc, BoardEdit.Text("e1", "x")) }
    }
}
