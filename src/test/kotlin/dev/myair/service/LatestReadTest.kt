package dev.myair.service

import kotlin.test.Test
import kotlin.test.assertEquals

class LatestReadTest {
    /** Runs the reads when told to, in any order; lands at once, as on one thread. */
    private val queued = ArrayList<Runnable>()
    private val landed = ArrayList<Pair<String, Result<Int>>>()
    private val reads = LatestRead<String, Int>({ queued += it }, Runnable::run, { it.toInt() }) { key, value -> landed += key to value }

    @Test
    fun `only the read of the key asked for last lands, whichever finishes first`() {
        reads.request("1")
        reads.request("2")
        queued[1].run()
        queued[0].run()
        assertEquals(listOf("2" to Result.success(2)), landed)
        assertEquals("2", reads.asked)
    }

    @Test
    fun `a read forgotten before it finishes never lands`() {
        reads.request("1")
        reads.forget()
        queued.single().run()
        assertEquals(emptyList(), landed)
        assertEquals(null, reads.asked)
    }

    @Test
    fun `a failed read lands as a failure`() {
        reads.request("x")
        queued.single().run()
        assertEquals("x", landed.single().first)
        assert(landed.single().second.exceptionOrNull() is NumberFormatException)
    }
}
