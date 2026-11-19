package dev.myair.schedule

import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunRecordsTest {
    @TempDir
    lateinit var temp: Path

    private val zone = ZoneId.of("Europe/Warsaw")
    private val slot = Instant.parse("2026-11-16T07:00:00Z")
    private val record = RunRecord("t1", slot, false, 42, slot, "s1", RunState.STARTING, null, false, slot)

    private fun records() = RunRecords(temp, zone)

    @Test
    fun `a slot is claimed once`() {
        assertTrue(records().claim(record))
        assertFalse(records().claim(record.copy(session = "s2")))
        assertTrue(records().exists("t1", slot))
        assertTrue(temp.resolve("t1/2026-11-16T08-00.json").exists())
    }

    @Test
    fun `of two threads claiming the same slot exactly one wins`() {
        repeat(50) { round ->
            val r = record.copy(slot = slot.plusSeconds(60L * round))
            val start = CountDownLatch(1)
            val wins = AtomicInteger()
            val threads = (1..2).map { thread { start.await(); if (RunRecords(temp, zone).claim(r)) wins.incrementAndGet() } }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, wins.get(), "round $round")
        }
    }

    @Test
    fun `a manual run is named by its second and cannot clash with a slot`() {
        val manual = record.copy(manual = true, slot = slot.plusSeconds(5))
        assertTrue(records().claim(record))
        assertTrue(records().claim(manual))
        assertTrue(temp.resolve("t1/2026-11-16T08-00-05-manual.json").exists())
    }

    @Test
    fun `update replaces a record and of lists newest first`() {
        val later = record.copy(slot = slot.plusSeconds(86400), startedAt = slot.plusSeconds(86400))
        records().claim(record)
        records().claim(later)
        records().update(record.copy(state = RunState.FAILED, error = "directory missing"))
        assertEquals(listOf(later, record.copy(state = RunState.FAILED, error = "directory missing")), records().of("t1"))
        assertEquals(2, records().all().size)
        assertEquals(emptyList(), records().of("unknown"))
    }

    @Test
    fun `prune deletes records over 90 days old, including those of a deleted task`() {
        records().claim(record)
        records().claim(record.copy(taskId = "gone"))
        val fresh = record.copy(slot = slot.plusSeconds(60), startedAt = slot.plusSeconds(86400L * 89))
        records().claim(fresh)
        records().prune(slot.plusSeconds(86400L * 91))
        assertEquals(listOf(fresh), records().all())
        assertFalse(temp.resolve("gone").exists())
    }

    @Test
    fun `a record with an error must be failed or skipped`() {
        assertThrows<IllegalArgumentException> { record.copy(error = "x") }
        assertThrows<IllegalArgumentException> { record.copy(state = RunState.FAILED) }
        record.copy(state = RunState.SKIPPED, error = "previous run still running")
    }

    @Test
    fun `this IDE fails its run left starting, which only an exception in the start leaves`() {
        assertEquals(Verdict.Failed("the start did not finish", close = false), verdict(record, own = true, ideAlive = true, hasTranscript = false, processRuns = false, slot))
    }

    @Test
    fun `a run that wrote its first message ran, whichever IDE sees it, also after its IDE ended`() {
        val started = record.copy(state = RunState.STARTED)
        assertEquals(Verdict.Ran, verdict(started, own = true, ideAlive = true, hasTranscript = true, processRuns = true, slot))
        assertEquals(Verdict.Ran, verdict(started, own = false, ideAlive = false, hasTranscript = true, processRuns = false, slot))
    }

    @Test
    fun `a run of an IDE gone fails before its first message, one of a live IDE is left to it`() {
        val gone = Verdict.Failed("the IDE running it ended", close = false)
        assertEquals(gone, verdict(record, own = false, ideAlive = false, hasTranscript = false, processRuns = false, slot))
        assertEquals(gone, verdict(record.copy(state = RunState.STARTED), own = false, ideAlive = false, hasTranscript = false, processRuns = false, slot))
        assertNull(verdict(record.copy(state = RunState.STARTED), own = false, ideAlive = true, hasTranscript = false, processRuns = false, slot))
    }

    @Test
    fun `this IDE fails its run whose process ended before the first message or that waited 5 minutes for it`() {
        val started = record.copy(state = RunState.STARTED)
        assertEquals(Verdict.Failed("claude ended before its first message", close = false), verdict(started, own = true, ideAlive = true, hasTranscript = false, processRuns = false, slot))
        assertNull(verdict(started, own = true, ideAlive = true, hasTranscript = false, processRuns = true, slot.plusSeconds(299)))
        assertEquals(Verdict.Failed("no first message within 5 minutes", close = true), verdict(started, own = true, ideAlive = true, hasTranscript = false, processRuns = true, slot.plusSeconds(300)))
    }

    @Test
    fun `a finished run is left alone`() {
        for (state in listOf(RunState.RAN, RunState.FAILED, RunState.SKIPPED)) {
            val done = record.copy(state = state, error = if (state == RunState.RAN) null else "e")
            assertNull(verdict(done, own = false, ideAlive = false, hasTranscript = false, processRuns = false, slot.plusSeconds(3600)))
        }
    }

    @Test
    fun `an IDE is alive only while a process with its pid and its start runs`() {
        val me = ProcessHandle.current()
        val started = me.info().startInstant().get()
        assertTrue(ideAlive(me.pid(), started))
        assertFalse(ideAlive(me.pid(), started.minusSeconds(1)))
    }

    @Test
    fun `an unreadable record is skipped and named, not thrown`() {
        records().claim(record)
        temp.resolve("t1/2026-11-17T08-00.json").writeText("{\"taskId\": \"t1\", \"color\": 1}")
        val r = records()
        assertEquals(listOf(record), r.all())
        assertEquals(listOf("t1/2026-11-17T08-00.json"), r.broken().keys.map { temp.relativize(it).toString().replace('\\', '/') })
    }

    @Test
    fun `a working or waiting previous run blocks the next slot`() {
        assertTrue(blocksNext(SessionStatus.WORKING))
        assertTrue(blocksNext(SessionStatus.NEEDS_INPUT))
        assertFalse(blocksNext(SessionStatus.IDLE))
        assertFalse(blocksNext(null))
    }
}
