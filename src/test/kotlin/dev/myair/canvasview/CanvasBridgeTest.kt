package dev.myair.canvasview

import com.google.gson.JsonParser
import dev.myair.canvas.BoardEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasBridgeTest {
    @Test
    fun `a ToAssistant click carries its message, context and form`() {
        val message = CanvasMessage.parse(
            """{"type":"action","frame":"C3","event":{"type":"continue_conversation","params":{"context":{"source":"spike"}},
              "humanFriendlyMessage":"Fix the selected test","formName":"fix","formState":{"fix":{"test":{"value":"watcher"}}}}}""",
        ) as CanvasMessage.Action
        assertEquals("continue_conversation", message.type)
        assertEquals("Fix the selected test", message.message)
        assertEquals("spike", message.params.getAsJsonObject("context").get("source").asString)
        assertEquals("fix", message.formName)
        assertEquals("watcher", message.formState!!.getAsJsonObject("fix").getAsJsonObject("test").get("value").asString)
    }

    @Test
    fun `an OpenUrl click without a form has no form state`() {
        val message = CanvasMessage.parse("""{"type":"action","frame":"C1","event":{"type":"open_url","params":{"url":"https://x.dev"},"humanFriendlyMessage":""}}""")
            as CanvasMessage.Action
        assertEquals("https://x.dev", message.params.get("url").asString)
        assertNull(message.formName)
        assertNull(message.formState)
    }

    @Test
    fun `parsed carries orphaned and unresolved names and the parser's errors`() {
        assertEquals(
            CanvasMessage.Parsed(
                "C1", 40, false, listOf("x"), listOf("missing"),
                listOf(CanvasError("unknown-component", "Widget is not a component", statementId = "w", component = "Widget", path = "")),
            ),
            CanvasMessage.parse(
                """{"type":"parsed","frame":"C1","ms":40,"ok":false,"orphaned":["x"],"unresolved":["missing"],
                  "errors":[{"code":"unknown-component","message":"Widget is not a component","statementId":"w","component":"Widget","path":""}]}""",
            ),
        )
    }

    @Test
    fun `validated answers a validate call by its id, and a warning is marked`() {
        assertEquals(
            CanvasMessage.Validated("v1", true, listOf(CanvasError("no-root", "No root", hint = "Add root", warning = true)), emptyList(), listOf("a")),
            CanvasMessage.parse(
                """{"type":"validated","id":"v1","ok":true,"errors":[{"code":"no-root","message":"No root","hint":"Add root","severity":"warning"}],
                  "unresolved":[],"orphaned":["a"],"boards":[]}""",
            ),
        )
    }

    @Test
    fun `a move names the frame and element, with the box's own sizes or null`() {
        assertEquals(
            CanvasMessage.Move("C2", "n", 3, -1, null, 4),
            CanvasMessage.parse("""{"type":"move","frame":"C2","element":"n","x":3,"y":-1,"w":null,"h":4}"""),
        )
        assertFailsWith<IllegalArgumentException> { CanvasMessage.parse("""{"type":"move","frame":"C2","element":"n","x":3}""") }
    }

    @Test
    fun `an edit carries its board and one of four operations`() {
        assertEquals(CanvasMessage.Edit("C2", "b", BoardEdit.Add("b", "note", 3, -1)),
            CanvasMessage.parse("""{"type":"edit","frame":"C2","board":"b","op":"add","kind":"note","x":3,"y":-1}"""))
        assertEquals(CanvasMessage.Edit("C2", "b", BoardEdit.Connect("b", "a", "c")),
            CanvasMessage.parse("""{"type":"edit","frame":"C2","board":"b","op":"connect","from":"a","to":"c"}"""))
        assertEquals(CanvasMessage.Edit("C2", "b", BoardEdit.Remove(listOf("a", "e"))),
            CanvasMessage.parse("""{"type":"edit","frame":"C2","board":"b","op":"remove","ids":["a","e"]}"""))
        assertEquals(CanvasMessage.Edit("C2", "b", BoardEdit.Text("a", "hi")),
            CanvasMessage.parse("""{"type":"edit","frame":"C2","board":"b","op":"text","element":"a","text":"hi"}"""))
        assertFailsWith<IllegalArgumentException> { CanvasMessage.parse("""{"type":"edit","frame":"C2","board":"b","op":"paint"}""") }
        assertFailsWith<IllegalArgumentException> { CanvasMessage.parse("""{"type":"edit","frame":"C2","board":"b","op":"add","kind":"note"}""") }
    }

    @Test
    fun `validated carries each board's geometry and problems`() {
        val answer = CanvasMessage.parse(
            """{"type":"validated","id":"v","ok":false,"errors":[],"unresolved":[],"orphaned":[],"boards":[{"board":"b","unit":24,
              "elements":[{"id":"g","kind":"shape","parent":null,"x":0,"y":-2,"w":10,"h":6,"estimated":false},
                          {"id":"n","kind":"note","parent":"g","x":1,"y":0,"w":8,"h":4,"estimated":true}],
              "edges":[{"id":"e","from":"g","to":"n"}],
              "problems":[{"severity":"error","code":"anchor-unknown","ids":["n","ghost"],"message":"m"},
                          {"severity":"warning","code":"overlap","ids":["a","b"],"message":"o"}]}]}""",
        ) as CanvasMessage.Validated
        assertEquals(
            listOf(
                CanvasBoard(
                    "b", 24,
                    listOf(BoardElement("g", "shape", null, 0, -2, 10, 6, false), BoardElement("n", "note", "g", 1, 0, 8, 4, true)),
                    listOf(BoardEdge("e", "g", "n")),
                    listOf(BoardProblem(true, "anchor-unknown", listOf("n", "ghost"), "m"), BoardProblem(false, "overlap", listOf("a", "b"), "o")),
                ),
            ),
            answer.boards,
        )
    }

    @Test
    fun `errors messages are read as canvas errors`() {
        val errors = CanvasMessage.parse("""{"type":"errors","frame":"C1","errors":[{"code":"render-error","message":"boom","component":"Table"}]}""") as CanvasMessage.Errors
        assertEquals(listOf(CanvasError("render-error", "boom", component = "Table")), errors.errors)
    }

    @Test
    fun `an unknown type, a missing field or a field of the wrong type throws`() {
        listOf(
            """{"type":"zoom"}""",
            """{"type":"state","frame":"C1"}""",
            """{"type":"parsed","frame":"C1","ms":1,"ok":true,"orphaned":[],"errors":[]}""",
            """{"type":"validated","id":"v","ok":"yes","errors":[],"unresolved":[],"orphaned":[],"boards":[]}""",
            """{"type":"validated","id":"v","ok":true,"errors":[],"unresolved":[],"orphaned":[]}""",
            """{"type":"validated","id":"v","ok":true,"errors":[],"unresolved":[],"orphaned":[],"boards":[{"board":"b","unit":24,"elements":[],"edges":[],
              "problems":[{"severity":"fatal","code":"x","ids":[],"message":"m"}]}]}""",
            """{"type":"validated","id":"v","ok":true,"errors":[],"unresolved":[],"orphaned":[],"boards":[{"board":"b","unit":24,
              "elements":[{"id":"a","kind":"circle","parent":null,"x":0,"y":0,"w":1,"h":1,"estimated":false}],"edges":[],"problems":[]}]}""",
            """{"type":"errors","frame":"C1","errors":[{"code":"x"}]}""",
            """{"type":"errors","frame":"C1","errors":[{"code":"x","message":"m","severity":"fatal"}]}""",
            """{"type":"action","frame":"C1","event":{"type":"open_url","params":{"url":"https://x.dev"}}}""",
            """{"type":"ready","ms":"soon"}""",
        ).forEach { json -> assertFailsWith<IllegalArgumentException>(json) { CanvasMessage.parse(json) } }
    }

    @Test
    fun `the show script passes the frame as a string literal that survives quotes and newlines`() {
        val openui = "root = Stack([t])\nt = TextContent(\"a \\\"b\\\" </script>\")"
        val script = showScript(CanvasFrame("C1", openui, dark = true))
        assertTrue(script.startsWith("window.myAir.show(\"") && script.endsWith("\");"))
        val frame = JsonParser.parseString(argumentOf(script, "show")).asJsonObject
        assertEquals(openui, frame.get("openui").asString)
        assertTrue(frame.get("dark").asBoolean)
    }

    @Test
    fun `the validate script passes the id and the document`() {
        val script = validateScript("v7", "root = Stack([])\n\"quoted\"")
        val call = JsonParser.parseString(argumentOf(script, "validate")).asJsonObject
        assertEquals("v7", call.get("id").asString)
        assertEquals("root = Stack([])\n\"quoted\"", call.get("openui").asString)
    }

    @Test
    fun `the connect script defines myAirPost with the injected body, then connects`() {
        assertEquals(
            "window.myAirPost = function(json) { cefQuery(json); }; window.myAir.connect();",
            connectScript("cefQuery(json);"),
        )
    }

    /** The JSON string a `window.myAir.<function>("…");` script passes. */
    private fun argumentOf(script: String, function: String): String {
        val literal = script.removePrefix("window.myAir.$function(").removeSuffix(");")
        return JsonParser.parseString(literal).asString
    }
}
