package dev.myair

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSessionRegistryTest {
    @TempDir
    lateinit var dir: Path

    private fun entry(pid: Long, session: String, status: String, extra: String = "") = dir.resolve("$pid.json").writeText(
        """{"pid":$pid,"sessionId":"$session","cwd":"E:\\x","procStart":"${pid * 10}","kind":"interactive","entrypoint":"cli",$extra"status":"$status"}"""
    )

    @Test
    fun `maps statuses and drops entries whose process is gone`() {
        entry(1, "a", "busy")
        entry(2, "b", "waiting", """"waitingFor":"dialog open",""")
        entry(3, "c", "idle")
        entry(4, "d", "busy")
        val alive = setOf(1L, 2L, 3L)
        val live = LiveSessionRegistry(dir) { pid, procStart -> pid in alive && procStart == pid * 10 }.read()
        assertEquals(
            mapOf(
                "a" to LiveSession(1, "cli", SessionStatus.WORKING, null),
                "b" to LiveSession(2, "cli", SessionStatus.NEEDS_INPUT, "dialog open"),
                "c" to LiveSession(3, "cli", SessionStatus.IDLE, null),
            ),
            live,
        )
    }

    @Test
    fun `unknown status fails loudly`() {
        entry(1, "a", "sleeping")
        assertFailsWith<IllegalStateException> { LiveSessionRegistry(dir) { _, _ -> true }.read() }
    }

    @Test
    fun `liveness check matches the current process by its FILETIME start and rejects another start time`() {
        val self = ProcessHandle.current()
        val fileTime = (self.info().startInstant().get().toEpochMilli() + 11_644_473_600_000L) * 10_000
        assertTrue(LiveSessionRegistry.isSameProcess(self.pid(), fileTime))
        assertFalse(LiveSessionRegistry.isSameProcess(self.pid(), fileTime + 10_000_000))
    }
}
