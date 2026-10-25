package dev.myair.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeHomeWatcherTest {
    @TempDir
    lateinit var home: Path

    private val calls = LinkedBlockingQueue<Long>()
    private val sessions by lazy { home.resolve("sessions").createDirectories() }
    private val projects by lazy { home.resolve("projects").createDirectories() }

    private fun watcher() = ClaudeHomeWatcher(listOf(sessions), projects) { calls.put(System.nanoTime()) }

    private fun awaitCall() = assertNotNull(calls.poll(5, TimeUnit.SECONDS), "onChange was not called")
    private fun assertNoCall() = assertNull(calls.poll(1500, TimeUnit.MILLISECONDS), "unexpected onChange")

    @Test
    fun `status file write and transcript append in an existing project each refresh once`() {
        val project = projects.resolve("E--x").createDirectories()
        watcher().use {
            sessions.resolve("1.json").writeText("{}")
            awaitCall()
            assertNoCall()
            project.resolve("s.jsonl").writeText("{}\n")
            awaitCall()
            assertNoCall()
        }
    }

    @Test
    fun `transcript in a project directory created after start is seen`() {
        watcher().use {
            val project = projects.resolve("E--new").createDirectories()
            awaitCall()
            project.resolve("s.jsonl").writeText("{}\n")
            awaitCall()
        }
    }

    @Test
    fun `a burst of writes collapses into one call, and continuous writes still refresh within the max wait`() {
        watcher().use {
            val file = sessions.resolve("1.json")
            val start = System.nanoTime()
            repeat(20) { i ->
                file.writeText("""{"n":$i}""")
                Thread.sleep(100)
            }
            val first = awaitCall()
            // Writes every 100 ms never leave 500 ms of quiet, so only the 700 ms cap can have fired the first call.
            val firstMs = TimeUnit.NANOSECONDS.toMillis(first - start)
            assertTrue(firstMs < 1500, "first call came $firstMs ms after the burst began")
            Thread.sleep(1500)
            val total = 1 + calls.size
            assertTrue(total in 2..5, "2 s of writes produced $total calls")
        }
    }

    @Test
    fun `close stops the watcher thread`() {
        val w = watcher()
        w.close()
        sessions.resolve("1.json").writeText("{}")
        assertNoCall()
        assertEquals(0, calls.size)
    }
}
