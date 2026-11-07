package dev.myair.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSessionRegistryTest {
    @TempDir
    lateinit var dir: Path

    private fun entry(pid: Long, session: String, status: String, extra: String = "") = dir.resolve("$pid.json").writeText(
        """{"pid":$pid,"sessionId":"$session","cwd":"E:\\x","startedAt":${pid * 100},"procStart":"${pid * 10}","kind":"interactive","entrypoint":"cli",$extra"status":"$status","statusUpdatedAt":${pid * 1000}}"""
    )

    @Test
    fun `maps statuses and drops entries whose process is gone`() {
        entry(1, "a", "busy")
        entry(2, "b", "waiting", """"waitingFor":"dialog open",""")
        entry(3, "c", "idle")
        entry(4, "d", "busy")
        entry(5, "e", "shell")
        val alive = setOf(1L, 2L, 3L, 5L)
        val live = LiveSessionRegistry(dir) { pid, procStart -> pid in alive && procStart == pid * 10 }.read()
        assertEquals(
            mapOf(
                "a" to LiveSession(1, "cli", SessionStatus.WORKING, null, Instant.ofEpochMilli(1000), Path.of("E:\\x"), Instant.ofEpochMilli(100)),
                "b" to LiveSession(2, "cli", SessionStatus.NEEDS_INPUT, "dialog open", Instant.ofEpochMilli(2000), Path.of("E:\\x"), Instant.ofEpochMilli(200)),
                "c" to LiveSession(3, "cli", SessionStatus.IDLE, null, Instant.ofEpochMilli(3000), Path.of("E:\\x"), Instant.ofEpochMilli(300)),
                "e" to LiveSession(5, "cli", SessionStatus.WORKING, null, Instant.ofEpochMilli(5000), Path.of("E:\\x"), Instant.ofEpochMilli(500)),
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
    fun `a process that has not reported a status yet is skipped`() {
        entry(1, "a", "busy")
        dir.resolve("2.json").writeText("""{"pid":2,"sessionId":"b","procStart":"20","kind":"interactive","entrypoint":"cli","updatedAt":1}""")
        assertEquals(setOf("a"), LiveSessionRegistry(dir) { _, _ -> true }.read().keys)
    }

    @Test
    fun `a missing status after one was reported fails loudly`() {
        dir.resolve("1.json").writeText("""{"pid":1,"sessionId":"a","procStart":"10","kind":"interactive","entrypoint":"cli","statusUpdatedAt":1}""")
        assertFailsWith<IllegalStateException> { LiveSessionRegistry(dir) { _, _ -> true }.read() }
    }

    /** A bg worker's record as `claude` 2.1.319 wrote it. */
    private val bgRecord =
        """{"pid":7244,"sessionId":"0aa16524-d67b-4b5b-9b9d-84cb32d6c5d0","cwd":"E:\\dev\\projects\\my-air","startedAt":1794051965907,"procStart":"134385255649481048","version":"2.1.319","peerProtocol":1,"peerFeatures":["notify_idle","artifact_yield"],"kind":"bg","entrypoint":"cli","pidDomain":"win32:desktop-56vkfel","asksForConvertedSkills":false,"messagingSocketPath":"\\\\.\\pipe\\LOCAL\\cc-msg-2fc8975750fb1e982ebb1d43423f1e26","name":"sleep 120 foreground test","nameSince":1794051971504,"jobId":"0aa16524","status":"idle","updatedAt":1794051976814,"statusUpdatedAt":1794051976814,"nameSource":"auto"}"""

    private val bgSession = "0aa16524-d67b-4b5b-9b9d-84cb32d6c5d0"

    private fun read() = LiveSessionRegistry(dir) { _, _ -> true }.read()

    @Test
    fun `a background job's worker is the entry of its session, with the job's id`() {
        dir.resolve("7244.json").writeText(bgRecord)
        assertEquals(
            mapOf(bgSession to LiveSession(7244, "cli", SessionStatus.IDLE, null, Instant.ofEpochMilli(1794051976814), Path.of("E:\\dev\\projects\\my-air"), Instant.ofEpochMilli(1794051965907), job = "0aa16524")),
            read(),
        )
    }

    @Test
    fun `a window parked into a job shows it and is no entry of its own`() {
        dir.resolve("7244.json").writeText(bgRecord)
        entry(42, "old", "idle", """"parkedJobId":"0aa16524",""")
        entry(43, "other", "idle", """"parkedJobId":"gone",""")
        assertEquals(mapOf(bgSession to setOf(42L)), read().mapValues { it.value.parkedBy })
    }

    @Test
    fun `a bg record without a job id fails loudly`() {
        dir.resolve("7244.json").writeText(bgRecord.replace(""""jobId":"0aa16524",""", ""))
        assertFailsWith<IllegalStateException> { read() }
    }

    @Test
    fun `spare workers and daemons run no session`() {
        entry(1, "a", "idle", """"spare":true,""")
        entry(2, "b", "idle")
        dir.resolve("3.json").writeText(dir.resolve("2.json").readText().replace("interactive", "daemon").replace("\"b\"", "\"c\""))
        dir.resolve("4.json").writeText(dir.resolve("2.json").readText().replace("interactive", "daemon-worker").replace("\"b\"", "\"d\""))
        assertEquals(setOf("b"), read().keys)
    }

    @Test
    fun `an unknown kind fails loudly`() {
        dir.resolve("1.json").writeText("""{"pid":1,"sessionId":"a","procStart":"10","kind":"robot","entrypoint":"cli","status":"idle","statusUpdatedAt":1}""")
        assertFailsWith<IllegalStateException> { read() }
    }

    @Test
    fun `a job's worker wins over another process of the same session, whichever file comes first`() {
        entry(1, bgSession, "busy")
        dir.resolve("7244.json").writeText(bgRecord)
        entry(9999, bgSession, "busy")
        assertEquals(7244, read().getValue(bgSession).pid)
    }

    @Test
    fun `a file deleted between the listing and its read counts as a process gone`() {
        assertEquals(null, LiveSessionRegistry(dir) { _, _ -> true }.entry(dir.resolve("9.json")))
    }

    @Test
    fun `liveness check matches the current process by its FILETIME start and rejects another start time`() {
        val self = ProcessHandle.current()
        val fileTime = (self.info().startInstant().get().toEpochMilli() + 11_644_473_600_000L) * 10_000
        assertTrue(LiveSessionRegistry.isSameProcess(self.pid(), fileTime))
        assertFalse(LiveSessionRegistry.isSameProcess(self.pid(), fileTime + 10_000_000))
    }
}
