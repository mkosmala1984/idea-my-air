package dev.myair.canvasview

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasActionsTest {
    private fun action(type: String, params: String = "{}", message: String = "", form: String = "") =
        CanvasMessage.parse("""{"type":"action","frame":"C3","event":{"type":"$type","params":$params,"humanFriendlyMessage":"$message"$form}}""")
            as CanvasMessage.Action

    @Test
    fun `a ToAssistant click without context or form is the bare message, naming folder and frame`() {
        assertEquals(CanvasCommand.Send("[canvas Flaky tests · C3] Skip"), commandOf(action("continue_conversation", message = "Skip"), "Flaky tests", null))
    }

    @Test
    fun `the frame's author is named when known`() {
        assertEquals(
            CanvasCommand.Send("[canvas F · C3] Skip (frame by session 1f2e)"),
            commandOf(action("continue_conversation", message = "Skip"), "F", "1f2e"),
        )
    }

    @Test
    fun `context and the submitting form's fields follow as a JSON block`() {
        val click = action(
            "continue_conversation", """{"context":{"source":"spike"}}""", "Fix the selected test",
            ""","formName":"fix","formState":{"fix":{"test":{"value":"watcher","componentType":"Select"},"note":{"value":"a <b> & \"c\""}}}""",
        )
        assertEquals(
            CanvasCommand.Send(
                "[canvas F · C3] Fix the selected test (frame by session s1)\n```json\n" +
                    """{"context":{"source":"spike"},"form":{"fix":{"test":"watcher","note":"a <b> & \"c\""}}}""" + "\n```",
            ),
            commandOf(click, "F", "s1"),
        )
    }

    @Test
    fun `a string context alone is attached too`() {
        assertEquals(
            CanvasCommand.Send("[canvas F · C3] More\n```json\n{\"context\":\"row 3\"}\n```"),
            commandOf(action("continue_conversation", """{"context":"row 3"}""", "More"), "F", null),
        )
    }

    @Test
    fun `OpenUrl browses http and https only`() {
        assertEquals(CanvasCommand.Browse("https://www.openui.com/docs"), commandOf(action("open_url", """{"url":"https://www.openui.com/docs"}"""), "F", null))
        assertEquals(CanvasCommand.Browse("HTTP://example.com/a?b=1"), commandOf(action("open_url", """{"url":"HTTP://example.com/a?b=1"}"""), "F", null))
        listOf("file:///C:/Windows/win.ini", "javascript:alert(1)", "myair-canvas://x", "https://", "not a url", "")
            .forEach { url -> assertEquals(CanvasCommand.Unknown("open_url"), commandOf(action("open_url", """{"url":"$url"}"""), "F", null), url) }
        assertEquals(CanvasCommand.Unknown("open_url"), commandOf(action("open_url"), "F", null))
    }

    @Test
    fun `OpenFile opens the path at its line, which is optional`() {
        assertEquals(CanvasCommand.OpenFile("src/a.kt", 42), commandOf(action("OpenFile", """{"path":"src/a.kt","line":42}"""), "F", null))
        assertEquals(CanvasCommand.OpenFile("../outside.kt", null), commandOf(action("OpenFile", """{"path":"../outside.kt"}"""), "F", null))
        assertEquals(CanvasCommand.OpenFile("a.kt", null), commandOf(action("OpenFile", """{"path":"a.kt","line":0}"""), "F", null))
        assertEquals(CanvasCommand.Unknown("OpenFile"), commandOf(action("OpenFile", """{"line":3}"""), "F", null))
    }

    @Test
    fun `CopyToClipboard copies its text`() {
        assertEquals(CanvasCommand.Copy("./gradlew test"), commandOf(action("CopyToClipboard", """{"text":"./gradlew test"}"""), "F", null))
        assertEquals(CanvasCommand.Unknown("CopyToClipboard"), commandOf(action("CopyToClipboard"), "F", null))
    }

    @Test
    fun `any other action is unknown, by its name`() {
        assertEquals(CanvasCommand.Unknown("Zoom"), commandOf(action("Zoom", """{"level":2}"""), "F", null))
    }
}
