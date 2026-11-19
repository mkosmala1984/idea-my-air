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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunRecordsTest {
    @TempDir
    lateinit var temp: Path

    private val zone = ZoneId.of("Europe/Warsaw")
    private val slot = Instant.parse("2026-11-16T07:00:00Z")
    private val record = RunRecord("t1", slot, false, 42, "s1", RunState.STARTING, null, false, slot)

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
    fun `a run is orphaned when its IDE is gone before a transcript exists`() {
        assertTrue(orphaned(record, ideAlive = false, hasTranscript = false))
        assertTrue(orphaned(record.copy(state = RunState.STARTED), ideAlive = false, hasTranscript = false))
        assertFalse(orphaned(record, ideAlive = true, hasTranscript = false))
        assertFalse(orphaned(record, ideAlive = false, hasTranscript = true))
        assertFalse(orphaned(record.copy(state = RunState.FAILED, error = "e"), ideAlive = false, hasTranscript = false))
    }

    @Test
    fun `a started run times out after 5 minutes without a transcript`() {
        val started = record.copy(state = RunState.STARTED)
        assertFalse(startTimedOut(started, slot.plusSeconds(299), hasTranscript = false))
        assertTrue(startTimedOut(started, slot.plusSeconds(300), hasTranscript = false))
        assertFalse(startTimedOut(started, slot.plusSeconds(3000), hasTranscript = true))
        assertFalse(startTimedOut(record, slot.plusSeconds(3000), hasTranscript = false))
    }

    @Test
    fun `a working or waiting previous run blocks the next slot`() {
        assertTrue(blocksNext(SessionStatus.WORKING))
        assertTrue(blocksNext(SessionStatus.NEEDS_INPUT))
        assertFalse(blocksNext(SessionStatus.IDLE))
        assertFalse(blocksNext(null))
    }
}
