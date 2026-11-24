package dev.myair.canvasview

import dev.myair.store.Canvas
import dev.myair.store.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class CanvasViewTest {
    private fun canvas(vararg ids: String) = Canvas(null, 10, ids.map { Frame(it, null, "root = Stack([])", Instant.EPOCH, null, null) })

    @Test fun `the first show selects the newest frame, none without frames`() {
        assertEquals("C3", selectedFrame(null, null, canvas("C1", "C2", "C3")))
        assertNull(selectedFrame(null, null, canvas()))
        assertNull(selectedFrame("C3", "C3", canvas()))
    }

    @Test fun `a new frame is selected, whatever the user picked`() =
        assertEquals("C4", selectedFrame("C1", "C3", canvas("C1", "C2", "C3", "C4")))

    @Test fun `the user's choice stays while no frame arrives`() =
        assertEquals("C1", selectedFrame("C1", "C3", canvas("C1", "C2", "C3")))

    @Test fun `removing the newest frame keeps the choice, removing the chosen one selects the newest`() {
        assertEquals("C1", selectedFrame("C1", "C3", canvas("C1", "C2")))
        assertEquals("C3", selectedFrame("C2", "C3", canvas("C1", "C3")))
    }

    @Test fun `ids are compared by number, so C10 is newer than C9`() =
        assertEquals("C10", selectedFrame("C1", "C9", canvas("C1", "C9", "C10")))

    @Test fun `a frame after a clear is new`() = assertEquals("C7", selectedFrame("C2", "C6", canvas("C7")))

    private val root = Path.of("E:/dev/r")
    private val other = Path.of("E:/dev/wt")

    @Test fun `a relative path resolves against the first root`() {
        assertEquals(root.resolve("src/A.kt"), canvasFileTarget("src/A.kt", listOf(root, other)))
        assertEquals(root.resolve("src/A.kt"), canvasFileTarget("./src/../src/A.kt", listOf(root)))
    }

    @Test fun `a path that climbs out of the roots is refused`() {
        assertNull(canvasFileTarget("../secret.txt", listOf(root)))
        assertNull(canvasFileTarget("src/../../r2/x", listOf(root)))
    }

    @Test fun `an absolute path is opened only inside a root`() {
        assertEquals(other.resolve("B.kt"), canvasFileTarget("E:/dev/wt/B.kt", listOf(root, other)))
        assertNull(canvasFileTarget("C:/Windows/win.ini", listOf(root, other)))
        assertNull(canvasFileTarget("E:/dev/rx/B.kt", listOf(root)))
        // A drive-less root path is not absolute on Windows: it would land on the root's drive, outside the root.
        assertNull(canvasFileTarget("/Windows/win.ini", listOf(root)))
    }

    @Test fun `no root or no path refuses`() {
        assertNull(canvasFileTarget("A.kt", emptyList()))
        assertNull(canvasFileTarget("a\u0000b", listOf(root)))
    }

    private fun parsed(ok: Boolean = true, errors: List<CanvasError> = emptyList(), unresolved: List<String> = emptyList()) =
        CanvasMessage.Parsed("C1", 1, ok, emptyList(), unresolved, errors)

    @Test fun `a clean frame has no problems`() {
        assertNull(canvasProblems(parsed(), emptyList(), emptyList()))
        assertNull(canvasProblems(null, emptyList(), emptyList()))
    }

    @Test fun `errors, warnings and script errors are counted and listed`() {
        val error = CanvasError("unknown-component", "Unknown component Foo", statementId = "x", component = "Foo")
        val warning = CanvasError("missing-required", "title is missing", warning = true)
        val problems = canvasProblems(parsed(false, listOf(error, warning), listOf("data")), listOf(error), listOf("boom"))!!
        assertEquals("2 errors, 2 warnings", problems.summary)
        assertEquals(
            listOf(
                "Error unknown-component: Unknown component Foo (statement x, Foo)",
                "Warning missing-required: title is missing",
                "Script error: boom",
                "Warning: data is used but never defined",
            ),
            problems.lines,
        )
    }

    @Test fun `a document without a root is an error`() =
        assertEquals("1 error", canvasProblems(parsed(ok = false), emptyList(), emptyList())!!.summary)
}
