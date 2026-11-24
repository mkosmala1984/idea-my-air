package dev.myair.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
        assertEquals(listOf("folder", "path", "frame", "stored", "validated", "ok", "errors", "unresolved", "orphaned", "note"), o.keySet().toList())
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
    fun `an unchecked frame says why, with ok null`() {
        val o = json(canvasShowJson("c1", "p", frame, CanvasCheck.unchecked(CanvasCheck.NO_PAGE)))
        assertEquals(false, o["validated"].asBoolean)
        assertTrue(o["ok"].isJsonNull)
        assertEquals("no canvas tab is open in this IDE, the frame was stored unchecked", o["note"].asString)
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
        assertEquals(setOf("id", "title", "created", "author"), frames[0].keySet())
        assertEquals("2026-11-23T10:15:00Z", frames[0]["created"].asString)
        assertEquals("s2", frames[1]["author"].asString)
        assertEquals("root = Stack([a])", o["newest"].asJsonObject["openui"].asString)
        assertEquals(state, o["newest"].asJsonObject["state"])
        val none = json(canvasGetJson("c1", "p", Canvas(null, 1, emptyList())))
        assertTrue(none["newest"].isJsonNull)
        assertTrue(none["manager"].isJsonNull)
    }
}
