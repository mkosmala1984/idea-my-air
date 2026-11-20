package dev.myair.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MessageToTypeTest {
    private val file = Path.of("C:/m/1-x.md")

    @Test fun `a short line is typed as it is`() =
        assertEquals("Post finding F1 to PR #7.", messageToType("Post finding F1 to PR #7.") { error("no file for a short line") })

    @Test fun `a text of several lines goes into a file the message names`() {
        var written: String? = null
        assertEquals("My message is in the file $file: read it whole and do what it says.", messageToType("a\nb") { written = it; file })
        assertEquals("a\nb", written)
    }

    @Test fun `a line longer than the limit goes into a file`() {
        val long = "x".repeat(MESSAGE_CHARS + 1)
        var written: String? = null
        messageToType(long) { written = it; file }
        assertEquals(long, written)
        assertEquals("x".repeat(MESSAGE_CHARS), messageToType("x".repeat(MESSAGE_CHARS)) { error("no file at the limit") })
    }
}
