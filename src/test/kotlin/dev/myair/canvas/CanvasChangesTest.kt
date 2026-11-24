package dev.myair.canvas

import com.google.gson.JsonObject
import dev.myair.claude.ClaudeSession
import dev.myair.claude.ShownFrame
import dev.myair.folders.SessionFolders
import dev.myair.store.Canvas
import dev.myair.store.PlanManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasChangesTest {
    private val now = Instant.parse("2026-11-23T10:00:00Z")
    private val empty = Canvas(null, 1, emptyList())
    private val two = addFrame(addFrame(empty, "First", "root = Stack([])", now), null, "root = Stack([a])", now.plusSeconds(1))

    @Test
    fun `frames get increasing ids, newest last, and the oldest go past 50`() {
        assertEquals(listOf("C1", "C2"), two.frames.map { it.id })
        assertEquals(3, two.next)
        assertEquals("C2", two.newest!!.id)
        assertNull(two.newest!!.author)
        val many = (1..55).fold(empty) { c, i -> addFrame(c, null, "root = Stack([])", now.plusSeconds(i.toLong())) }
        assertEquals(50, many.frames.size)
        assertEquals("C6", many.frames.first().id)
        assertEquals("C55", many.newest!!.id)
    }

    @Test
    fun `an empty document or a title of two lines is refused`() {
        assertThrows<IllegalArgumentException> { addFrame(empty, null, "  ", now) }
        assertThrows<IllegalArgumentException> { addFrame(empty, "a\nb", "root = Stack([])", now) }
    }

    @Test
    fun `state and author are set on a frame, an author once learned is kept`() {
        val state = JsonObject().apply { addProperty("x", 1) }
        assertEquals(state, setState(two, "C1", state).frame("C1").state)
        assertThrows<IllegalArgumentException> { setState(two, "C9", state) }
        val authored = setAuthor(two, "C2", "s1")
        assertEquals("s1", setAuthor(authored, "C2", "s2").frame("C2").author)
    }

    @Test
    fun `authors are learned from the frames sessions showed, of this folder only`() {
        val shows = mapOf("s1" to listOf(ShownFrame("f1", "C1"), ShownFrame("f2", "C2"), ShownFrame("f1", "C9")), "s2" to listOf(ShownFrame("f1", "C2")))
        val learned = learnAuthors(two, "f1", shows)
        assertEquals(listOf("s1", "s2"), learned.frames.map { it.author })
        assertEquals(learned, learnAuthors(learned, "f1", shows))
        val all = mapOf("f1" to Result.success(two), "f2" to Result.success(empty), "f3" to Result.failure<Canvas>(IllegalStateException()))
        assertEquals(setOf("f1"), authorsDue(all, shows))
        assertEquals(emptySet(), authorsDue(mapOf("f1" to Result.success(learned)), shows))
        val session = ClaudeSession("s1", Path.of("/x"), "t", now, emptyList(), emptyList(), shownFrames = listOf(ShownFrame("f1", "C1")))
        assertEquals(mapOf("s1" to listOf(ShownFrame("f1", "C1"))), shownFrames(listOf(session, session.copy(id = "s3", shownFrames = emptyList()))))
    }

    @Test
    fun `changes remove, retitle and clear, all or none`() {
        assertEquals(listOf("C2"), CanvasChanges.apply(two, """[{"op":"remove_frame","id":"C1"}]""").frames.map { it.id })
        val retitled = CanvasChanges.apply(two, """[{"op":"set_title","id":"C2","title":"Second"},{"op":"set_title","id":"C1","title":null}]""")
        assertEquals(listOf(null, "Second"), retitled.frames.map { it.title })
        val cleared = CanvasChanges.apply(two, """[{"op":"clear"}]""")
        assertEquals(emptyList(), cleared.frames)
        assertEquals("C3", addFrame(cleared, null, "root = Stack([])", now).newest!!.id)
        assertContains(assertThrows<IllegalArgumentException> { CanvasChanges.apply(two, """[{"op":"remove_frame","id":"C1"},{"op":"remove_frame","id":"C7"}]""") }.message!!, "change 2")
        assertContains(assertThrows<IllegalArgumentException> { CanvasChanges.apply(two, """[{"op":"rename"}]""") }.message!!, "unknown op 'rename'")
        assertContains(assertThrows<IllegalArgumentException> { CanvasChanges.apply(two, """[{"op":"clear","all":true}]""") }.message!!, "all")
        assertContains(assertThrows<IllegalArgumentException> { CanvasChanges.apply(two, """[{"op":"set_title","id":"C1"}]""") }.message!!, "title")
        assertThrows<IllegalArgumentException> { CanvasChanges.apply(two, """{"op":"clear"}""") }
        assertThrows<IllegalArgumentException> { CanvasChanges.apply(two, """[{"op":"set_title","id":"C1","title":"a\nb"}]""") }
    }

    @Test
    fun `the managed canvas is found by its manager's session, only canvases in the structure count`() {
        val managed = two.copy(manager = PlanManager("m1", Path.of("/x"), Path.of("/x")))
        val all = mapOf("f1" to Result.success(two), "f2" to Result.success(managed), "f3" to Result.failure<Canvas>(IllegalStateException()))
        assertEquals("f2", managedCanvasFolder(all, "m1"))
        assertNull(managedCanvasFolder(all, "m2"))
        val structure = SessionFolders().createFolder(null, "f1", "Canvas: a").createFolder("f1", "f3", "x")
        assertEquals(setOf("f1", "f3"), canvasesInStructure(all, structure).keys)
    }

    @Test
    fun `a patch changes the frame in place, keeping its id, title and state, against its current revision`() {
        val state = JsonObject().apply { addProperty("x", 1) }
        val canvas = setState(two, "C1", state)
        val doc = "root = Stack([t])\nt = TextContent(\"hi\")"
        val (patched, merge) = patchFrame(canvas, "C1", doc, openUiRev("root = Stack([])"))
        val f = patched.frame("C1")
        assertEquals(doc, f.openui)
        assertEquals("First", f.title)
        assertEquals(state, f.state)
        assertEquals(listOf("C1", "C2"), patched.frames.map { it.id })
        assertEquals(3, patched.next)
        assertEquals(listOf("root", "t"), merge.set)
    }

    @Test
    fun `a patch against a stale revision is refused with the current one and the document`() {
        val e = assertThrows<IllegalArgumentException> { patchFrame(two, "C2", "root = Stack([])", "000000000000") }
        assertContains(e.message!!, "frame C2 is at rev ${openUiRev("root = Stack([a])")}, not 000000000000")
        assertContains(e.message!!, "root = Stack([a])")
        assertEquals("no frame 'C9'", assertThrows<IllegalArgumentException> { patchFrame(two, "C9", "root = Stack([])", "x") }.message)
    }
}
