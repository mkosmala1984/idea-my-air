package dev.myair.store

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasFilesTest {
    @TempDir
    lateinit var dir: Path

    private val at = Instant.parse("2026-11-23T10:15:00Z")
    private val state = JsonObject().apply { add("fix", JsonObject().apply { addProperty("test", "watcher") }) }

    private fun frame(n: Int) = Frame("C$n", "Frame $n", "root = Stack([])\n", at.plusSeconds(n.toLong()), null, null)

    private val canvas = Canvas(
        PlanManager("s1", Path.of("E:\\dev\\rtm"), Path.of("E:\\dev\\rtm"), "work"), 3,
        listOf(frame(1), Frame("C2", null, "root = Stack([t])\nt = TextContent(\"a \\\"quote\\\" <b>\")", at, "s2", state)),
    )

    @Test
    fun `a canvas round-trips through its file`() {
        val files = CanvasFiles(dir)
        files.write("f1", canvas)
        assertEquals(canvas, files.read("f1"))
        assertEquals(mapOf("f1" to canvas), files.readAll().mapValues { it.value.getOrThrow() })
        assertContains(files.file("f1").readText(), "\"format\": 1")
        val plain = canvas.copy(manager = null, frames = emptyList())
        files.write("f2", plain)
        assertEquals(plain, files.read("f2"))
    }

    @Test
    fun `a missing or unknown key throws naming it, a broken file is a failure of readAll`() {
        val files = CanvasFiles(dir)
        files.write("f1", canvas)
        val text = files.file("f1").readText()
        files.file("f1").writeText(text.replaceFirst("\"author\": null,", ""))
        assertContains(assertThrows<IllegalStateException> { files.read("f1") }.message!!, "author")
        files.file("f1").writeText(text.replace("\"next\": 3", "\"next\": 3, \"extra\": 1"))
        assertContains(assertThrows<IllegalStateException> { files.read("f1") }.message!!, "extra")
        files.file("f1").writeText(text.replace("\"next\": 3,", ""))
        assertContains(assertThrows<IllegalStateException> { files.read("f1") }.message!!, "next")
        assertTrue(files.readAll().getValue("f1").isFailure)
    }

    @Test
    fun `a newer format throws saying so, even with keys this build does not know`() {
        val file = dir.resolve("f1").createDirectories().resolve("canvas.json")
        file.writeText("""{"format": 2, "manager": null, "next": 1, "frames": [], "layout": {}}""")
        assertContains(assertThrows<IllegalStateException> { CanvasFiles(dir).read("f1") }.message!!, "newer")
        file.writeText("""{"format": 0, "manager": null, "next": 1, "frames": []}""")
        assertThrows<IllegalStateException> { CanvasFiles(dir).read("f1") }
    }

    @Test
    fun `a write keeps the newest 50 frames`() {
        val many = Canvas(null, 61, (1..60).map { frame(it) })
        val stored = CanvasFiles(dir).write("f1", many)
        assertEquals((11..60).map { "C$it" }, stored.frames.map { it.id })
        assertEquals(stored, CanvasFiles(dir).read("f1"))
        assertEquals(61, stored.next)
    }
}
