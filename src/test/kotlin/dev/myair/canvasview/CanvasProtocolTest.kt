package dev.myair.canvasview

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin's side of `canvas-web/src/protocol.json`, which the page's `protocol.test.ts` checks too. Gradle runs the
 * tests in the project directory, so the file is read where it lies (and `tasks.test` declares it as an input).
 */
class CanvasProtocolTest {
    private val contract = JsonParser.parseString(File("canvas-web/src/protocol.json").readText()).asJsonObject
    private fun names(key: String) = contract.getAsJsonArray(key).map { it.asString }

    /** One message of each type the page posts. */
    private val samples = mapOf(
        "ready" to """{"type":"ready","ms":3}""",
        "parsed" to """{"type":"parsed","frame":"C1","ms":4,"ok":true,"orphaned":[],"unresolved":[],"errors":[]}""",
        "validated" to """{"type":"validated","id":"v","ok":true,"errors":[],"unresolved":[],"orphaned":[],"boards":[]}""",
        "errors" to """{"type":"errors","frame":"C1","errors":[]}""",
        "state" to """{"type":"state","frame":"C1","state":{}}""",
        "action" to """{"type":"action","frame":"C1","event":{"type":"open_url","params":{"url":"https://x.dev"},"humanFriendlyMessage":""}}""",
        "move" to """{"type":"move","frame":"C1","element":"n","x":3,"y":-1,"w":null,"h":4}""",
        "js-error" to """{"type":"js-error","message":"x"}""",
    )

    /** The params of a click of each action type, as the page posts them. */
    private val actions = mapOf(
        "continue_conversation" to """{"context":"c"}""",
        "open_url" to """{"url":"https://x.dev"}""",
        "OpenFile" to """{"path":"a.kt","line":1}""",
        "CopyToClipboard" to """{"text":"t"}""",
    )

    @Test
    fun `the plugin reads every message type the page posts`() {
        assertEquals(names("messages").toSet(), samples.keys)
        samples.values.forEach { CanvasMessage.parse(it) }
    }

    @Test
    fun `the plugin runs every action the library has, built in or custom`() {
        assertEquals((names("builtinActions") + names("customActions")).toSet(), actions.keys)
        for ((type, params) in actions) {
            val click = CanvasMessage.parse("""{"type":"action","frame":"C1","event":{"type":"$type","params":$params,"humanFriendlyMessage":"m"}}""") as CanvasMessage.Action
            val command = commandOf(click, "F", null)
            assertTrue(command !is CanvasCommand.Unknown, "$type maps to $command")
        }
    }

    @Test
    fun `the plugin's scripts call exactly the page's functions`() {
        val scripts = listOf(showScript(CanvasFrame("C1", "", dark = false)), validateScript("v", ""), connectScript(""))
        val called = scripts.flatMap { Regex("""window\.myAir\.(\w+)\(""").findAll(it).map { m -> m.groupValues[1] } }
        assertEquals(names("pageCalls").toSet(), called.toSet())
    }
}
