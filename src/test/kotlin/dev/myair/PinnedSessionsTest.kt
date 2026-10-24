package dev.myair

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PinnedSessionsTest {
    /** Newest first, as the scanner returns them. */
    private val sessions = (1..6).map { session("s$it", 100L - it) }

    private fun session(id: String, time: Long) = ClaudeSession(id, Path.of("/p"), "title $id", FileTime.fromMillis(time), emptyList())

    private fun live(status: SessionStatus) = LiveSession(1, "cli", status, null)

    private fun ids(live: Map<String, LiveSession>, pins: Pins, list: List<ClaudeSession> = sessions) = pinnedSessions(list, live, pins).map { it.id }

    @Test
    fun `lists running and hand-pinned sessions, nothing else`() {
        assertEquals(listOf("s5", "s2"), ids(mapOf("s5" to live(SessionStatus.WORKING)), Pins(setOf("s2", "gone"))))
    }

    @Test
    fun `sessions waiting for the user come first, pinned ones without a process last, newest first within a group`() {
        val live = mapOf(
            "s1" to live(SessionStatus.WORKING),
            "s2" to live(SessionStatus.IDLE),
            "s3" to live(SessionStatus.NEEDS_INPUT),
            "s4" to live(SessionStatus.IDLE),
            "s6" to live(SessionStatus.NEEDS_INPUT),
        )
        assertEquals(listOf("s3", "s6", "s2", "s4", "s1", "s5"), ids(live, Pins(setOf("s5", "s1"))))
    }

    @Test
    fun `a removed running session stays hidden while it works and comes back when it waits again`() {
        val idle = mapOf("s1" to live(SessionStatus.IDLE))
        val pins = Pins(setOf("s1")).remove(listOf(sessions[0]), idle)
        assertEquals(Pins(dismissed = mapOf("s1" to 99L)), pins)
        assertEquals(emptyList(), ids(idle, pins))
        val working = mapOf("s1" to live(SessionStatus.WORKING))
        val wrote = listOf(session("s1", 150))
        assertEquals(emptyList(), ids(working, pins, wrote))
        assertEquals(pins, pins.prune(wrote, working))
        val waiting = mapOf("s1" to live(SessionStatus.NEEDS_INPUT))
        assertEquals(listOf("s1"), ids(waiting, pins, wrote))
        assertEquals(Pins(), pins.prune(wrote, waiting))
    }

    @Test
    fun `a removed session without a process is only unpinned, and a dismissal ends with the process or a pin`() {
        val pins = Pins(setOf("s2")).remove(listOf(sessions[0], sessions[1]), mapOf("s1" to live(SessionStatus.IDLE)))
        assertEquals(Pins(dismissed = mapOf("s1" to 99L)), pins)
        assertEquals(Pins(), pins.prune(sessions, emptyMap()))
        assertEquals(Pins(setOf("s1")), pins.pin(listOf("s1")))
    }

    @Test
    fun `pins survive a fresh store, keep pinning order, and an unchanged update writes nothing`(@TempDir dir: Path) {
        val file = dir.resolve("pins.json")
        val store = PinStore(file)
        assertEquals(Pins(), store.update { it })
        assertFalse(file.exists())
        store.update { it.pin(listOf("b")) }
        store.update { it.pin(listOf("a", "c")) }
        store.update { it.unpin(listOf("c")) }
        assertFalse("dismissed" in file.readText())
        store.update { it.copy(dismissed = mapOf("x" to 5L)) }
        assertEquals(Pins(linkedSetOf("b", "a"), mapOf("x" to 5L)), PinStore(file).read())
        assertEquals(listOf("b", "a"), PinStore(file).read().sessions.toList())
    }

    @Test
    fun `a pins file in an unknown shape throws`(@TempDir dir: Path) {
        val file = dir.resolve("pins.json")
        file.writeText("""{"sessions":["a"],"extra":1}""")
        assertThrows<IllegalStateException> { PinStore(file).read() }
        file.writeText("""{"sessions":[1]}""")
        assertThrows<IllegalStateException> { PinStore(file).read() }
    }
}
