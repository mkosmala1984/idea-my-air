package dev.myair.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.canvasview.BoardEdge
import dev.myair.canvasview.BoardElement
import dev.myair.canvasview.BoardProblem
import dev.myair.canvasview.CanvasBoard
import dev.myair.canvasview.CanvasError
import dev.myair.canvasview.CanvasMessage
import dev.myair.folders.SessionFolders
import dev.myair.store.Canvas
import dev.myair.store.Frame
import dev.myair.store.PlanManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasJsonTest {
    private val structure = SessionFolders().createFolder(null, "rtm", "rtm").createFolder("rtm", "c1", "Canvas: flaky").createFolder("rtm", "o", "other")
    private val at = Instant.parse("2026-11-23T10:15:00Z")
    private val frame = Frame("C3", "Flaky", "root = Stack([])", at, null, null)

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `a canvas folder is named by id or path, anything else is refused naming it`() {
        assertEquals("c1", resolveCanvasFolder(structure, setOf("c1"), "c1"))
        assertEquals("c1", resolveCanvasFolder(structure, setOf("c1"), "rtm/Canvas: flaky"))
        assertEquals("no folder 'nope'", assertThrows<IllegalArgumentException> { resolveCanvasFolder(structure, setOf("c1"), "nope") }.message)
        assertEquals("folder 'rtm/other' is not a canvas folder", assertThrows<IllegalArgumentException> { resolveCanvasFolder(structure, setOf("c1"), "o") }.message)
    }

    @Test
    fun `the show result carries the page's verdict, its errors with severity`() {
        val answer = CanvasMessage.Validated(
            "v", false,
            listOf(CanvasError("unknown-component", "Unknown component Foo", "a", "Foo", null, "Use Card", false), CanvasError("x", "minor", warning = true)),
            listOf("b"), listOf("c"),
        )
        val o = json(canvasShowJson("c1", "rtm/Canvas: flaky", frame, CanvasCheck.of(answer)))
        assertEquals(listOf("folder", "path", "frame", "rev", "stored", "validated", "ok", "errors", "unresolved", "orphaned", "boards", "note"), o.keySet().toList())
        assertEquals(dev.myair.canvas.openUiRev(frame.openui), o["rev"].asString)
        val patched = json(canvasShowJson("c1", "p", frame, CanvasCheck.of(answer), dev.myair.canvas.OpenUiMerge("x", listOf("a"), listOf("b"))))
        assertEquals(listOf("folder", "path", "frame", "rev", "set", "removed", "stored"), patched.keySet().take(7))
        assertEquals(listOf("b"), patched["removed"].asJsonArray.map { it.asString })
        assertEquals("c1", o["folder"].asString)
        assertEquals("C3", o["frame"].asString)
        assertTrue(o["stored"].asBoolean)
        assertTrue(o["validated"].asBoolean)
        assertEquals(false, o["ok"].asBoolean)
        assertEquals(listOf("error", "warning"), o["errors"].asJsonArray.map { it.asJsonObject["severity"].asString })
        assertEquals("a", o["errors"].asJsonArray[0].asJsonObject["statementId"].asString)
        assertTrue(o["errors"].asJsonArray[1].asJsonObject["statementId"].isJsonNull)
        assertEquals(CanvasCheck.INVALID, o["note"].asString)
        assertEquals(CanvasCheck.WARNINGS, CanvasCheck.of(answer.copy(ok = true)).note)
        assertEquals(null, CanvasCheck.of(answer.copy(ok = true, errors = emptyList())).note)
    }

    @Test
    fun `the show result carries the boards' geometry, and a board warning alone makes the note say so`() {
        val board = CanvasBoard(
            "b", 24, listOf(BoardElement("n", "note", null, 2, 3, 8, 4, true)), listOf(BoardEdge("e", "n", "m")),
            listOf(BoardProblem(false, "overlap", listOf("n", "m"), "n and m overlap.")),
        )
        val check = CanvasCheck.of(CanvasMessage.Validated("v", true, emptyList(), emptyList(), emptyList(), listOf(board)))
        assertEquals(CanvasCheck.WARNINGS, check.note)
        val b = json(canvasShowJson("c1", "p", frame, check))["boards"].asJsonArray.single().asJsonObject
        assertEquals(listOf("board", "unit", "elements", "edges", "problems"), b.keySet().toList())
        val e = b["elements"].asJsonArray.single().asJsonObject
        assertEquals(listOf("id", "kind", "parent", "x", "y", "w", "h", "estimated"), e.keySet().toList())
        assertTrue(e["parent"].isJsonNull)
        assertEquals(listOf(2, 3, 8, 4), listOf("x", "y", "w", "h").map { e[it].asInt })
        assertTrue(e["estimated"].asBoolean)
        val p = b["problems"].asJsonArray.single().asJsonObject
        assertEquals("warning", p["severity"].asString)
        assertEquals(listOf("n", "m"), p["ids"].asJsonArray.map { it.asString })
    }

    @Test
    fun `an unchecked frame says why, with ok null`() {
        val o = json(canvasShowJson("c1", "p", frame, CanvasCheck.unchecked(CanvasCheck.NO_PAGE)))
        assertEquals(false, o["validated"].asBoolean)
        assertTrue(o["ok"].isJsonNull)
        assertEquals("no canvas tab is open in this IDE, the frame was stored unchecked", o["note"].asString)
    }

    @Test
    fun `get gives a named frame whole too, and refuses an unknown one naming the frames`() {
        val canvas = Canvas(null, 5, listOf(frame.copy(id = "C1", openui = "root = Stack([old])"), frame.copy(id = "C4")))
        val o = json(canvasGetJson("c1", "p", canvas, "C1"))
        assertEquals("C4", o["newest"].asJsonObject["id"].asString)
        assertEquals("root = Stack([old])", o["frame"].asJsonObject["openui"].asString)
        assertEquals(setOf("id", "rev", "title", "openui", "state"), o["frame"].asJsonObject.keySet())
        assertTrue("frame" !in json(canvasGetJson("c1", "p", canvas)).keySet())
        val e = assertThrows<IllegalArgumentException> { canvasGetJson("c1", "p", canvas, "C9") }
        assertEquals("the canvas has no frame C9; its frames are C1, C4", e.message)
    }

    @Test
    fun `the show result carries the snapshot only when one was asked for`() {
        val check = CanvasCheck.unchecked(CanvasCheck.NO_PAGE)
        assertTrue("snapshot" !in json(canvasShowJson("c1", "p", frame, check)).keySet())
        val taken = json(canvasShowJson("c1", "p", frame, check, snapshot = CanvasSnapshot("/s/C3.png", null)))
        assertEquals("/s/C3.png", taken["snapshot"].asString)
        assertTrue(taken["snapshotNote"].isJsonNull)
        val missed = json(canvasShowJson("c1", "p", frame, check, snapshot = CanvasSnapshot(null, CanvasSnapshot.HIDDEN)))
        assertTrue(missed["snapshot"].isJsonNull)
        assertEquals(CanvasSnapshot.HIDDEN, missed["snapshotNote"].asString)
    }

    @Test
    fun `get lists frames without documents and gives the newest whole`() {
        val state = JsonObject().apply { addProperty("x", 1) }
        val canvas = Canvas(PlanManager("m", Path.of("/w"), Path.of("/w")), 5, listOf(frame.copy(id = "C1"), frame.copy(id = "C4", author = "s2", state = state, openui = "root = Stack([a])")))
        val o = json(canvasGetJson("c1", "rtm/Canvas: flaky", canvas))
        assertEquals("m", o["manager"].asJsonObject["session"].asString)
        assertEquals("default", o["manager"].asJsonObject["profile"].asString)
        val frames = o["frames"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("C1", "C4"), frames.map { it["id"].asString })
        assertEquals(setOf("id", "rev", "title", "created", "author"), frames[0].keySet())
        assertEquals("2026-11-23T10:15:00Z", frames[0]["created"].asString)
        assertEquals("s2", frames[1]["author"].asString)
        assertEquals("root = Stack([a])", o["newest"].asJsonObject["openui"].asString)
        assertEquals(state, o["newest"].asJsonObject["state"])
        val none = json(canvasGetJson("c1", "p", Canvas(null, 1, emptyList())))
        assertTrue(none["newest"].isJsonNull)
        assertTrue(none["manager"].isJsonNull)
    }
}
