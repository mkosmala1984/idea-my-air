package dev.myair.dashboard

import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalyzerTest {
    @TempDir
    lateinit var dir: Path

    private val t = Instant.parse("2026-11-13T10:00:00Z")

    // As `claude -p --output-format json --json-schema` answered on 2026-11-13 (fields trimmed).
    private fun envelope(structured: String, isError: Boolean = false, result: String = "") =
        """{"type":"result","subtype":"success","is_error":$isError,"result":"$result","structured_output":$structured,"session_id":"x"}"""

    private fun session(id: String, transcript: Path? = null) =
        ClaudeSession(id, dir, "title $id", t, emptyList(), emptyList(), transcript = transcript)

    private fun script(name: String, body: String): Path = dir.resolve("$name.cmd").apply { writeText("@echo off\r\n$body\r\n") }

    @Test
    fun `a structured reply parses into an analysis`() {
        val a = parseAnalysis(envelope("""{"verdict":"open","left":"tests and PR"}"""), t, t.plusSeconds(5))
        assertEquals(Analysis(t, Verdict.OPEN, "tests and PR", t.plusSeconds(5)), a)
    }

    @Test
    fun `an error envelope fails with its text`() {
        val e = assertFailsWith<IllegalStateException> { parseAnalysis(envelope("null", isError = true, result = "Not logged in"), t, t) }
        assertTrue("Not logged in" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a reply off the schema fails with the raw output`() {
        val raw = envelope("""{"verdict":"maybe","left":""}""")
        val e = assertFailsWith<IllegalStateException> { parseAnalysis(raw, t, t) }
        assertTrue("maybe" in e.message.orEmpty(), e.message)
        assertFailsWith<IllegalStateException> { parseAnalysis("""{"type":"result","is_error":false,"result":"hi"}""", t, t) }
    }

    @Test
    fun `the arguments run haiku without tools, MCP servers or user settings`() {
        val args = analysisArgs(Path.of("claude"), "digest")
        assertEquals(listOf("claude", "-p", "--model", "haiku", "--no-session-persistence", "--output-format", "json"), args.take(7))
        assertTrue(args.containsAll(listOf("--tools", "", "--strict-mcp-config", "--setting-sources", "project", "--json-schema", ANALYSIS_SCHEMA)))
        assertEquals("--", args[args.size - 2])
        assertTrue("digest" in args.last())
    }

    @Test
    fun `a slow claude is killed after the timeout`() {
        val slow = script("slow", "ping -n 30 127.0.0.1 > nul")
        val started = System.nanoTime()
        assertFailsWith<IllegalStateException> { analyze(slow, "d", t, Duration.ofSeconds(1)) }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun `a failing claude reports its stderr`() {
        val failing = script("failing", "echo rate limited 1>&2\r\nexit /b 3")
        val e = assertFailsWith<IllegalStateException> { analyze(failing, "d", t) }
        assertTrue("rate limited" in e.message.orEmpty() && "3" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a claude answering on stdout gives its analysis`() {
        val ok = script("ok", "echo " + envelope("""{"verdict":"done","left":""}"""))
        assertEquals(Verdict.DONE, analyze(ok, "d", t).verdict)
    }

    @Test
    fun `a run keeps at most three analyses going and reports each`() {
        val going = AtomicInteger()
        val most = AtomicInteger()
        val results = Collections.synchronizedList(ArrayList<String>())
        val ended = CountDownLatch(1)
        val ends = AtomicInteger()
        val run = AnalysisRun((1..10).map { session("s$it") }, {
            most.accumulateAndGet(going.incrementAndGet(), ::maxOf)
            Thread.sleep(50)
            going.decrementAndGet()
            Analysis(t, Verdict.DONE, "", t)
        }, Executors.newFixedThreadPool(8), { s, r -> results += s.id; r.getOrThrow() }, { ends.incrementAndGet(); ended.countDown() })
        run.start()
        assertTrue(ended.await(10, TimeUnit.SECONDS))
        assertEquals(3, most.get())
        assertEquals((1..10).map { "s$it" }.toSet(), results.toSet())
        assertEquals(10, run.done)
        Thread.sleep(100)
        assertEquals(1, ends.get())
    }

    @Test
    fun `a cancelled run reports nothing further and ends once`() {
        val started = CountDownLatch(3)
        val results = AtomicInteger()
        val ends = AtomicInteger()
        val ended = CountDownLatch(1)
        val run = AnalysisRun((1..10).map { session("s$it") }, {
            started.countDown()
            Thread.sleep(10_000)
            Analysis(t, Verdict.DONE, "", t)
        }, Executors.newFixedThreadPool(8), { _, _ -> results.incrementAndGet() }, { ends.incrementAndGet(); ended.countDown() })
        run.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        run.cancel()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(0, results.get())
        assertEquals(1, ends.get())
    }

    @Test
    fun `a missing transcript fails that session only`() {
        val gone = dir.resolve("gone.jsonl")
        val results = Collections.synchronizedMap(HashMap<String, Result<Analysis>>())
        val ended = CountDownLatch(1)
        AnalysisRun(listOf(session("gone", gone), session("fine")), {
            if (it.id == "gone") analyzeSession(it, emptyList(), null) else Analysis(t, Verdict.DONE, "", t)
        }, Executors.newFixedThreadPool(2), { s, r -> results[s.id] = r }, { ended.countDown() }).start()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(results.getValue("fine").isSuccess)
        assertTrue("gone.jsonl" in results.getValue("gone").exceptionOrNull()?.message.orEmpty())
    }
}
