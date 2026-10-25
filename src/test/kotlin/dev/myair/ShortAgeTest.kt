package dev.myair

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ShortAgeTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private fun age(ago: Duration) = shortAge(now - ago, now)

    @Test
    fun `each unit starts where the previous one ends`() {
        assertEquals("now", age(Duration.ofSeconds(59)))
        assertEquals("1m", age(Duration.ofMinutes(1)))
        assertEquals("59m", age(Duration.ofMinutes(60).minusSeconds(1)))
        assertEquals("1h", age(Duration.ofHours(1)))
        assertEquals("13h", age(Duration.ofHours(13).plusMinutes(59)))
        assertEquals("1d", age(Duration.ofDays(1)))
        assertEquals("6d", age(Duration.ofDays(7).minusSeconds(1)))
        assertEquals("1w", age(Duration.ofDays(7)))
        assertEquals("52w", age(Duration.ofDays(364)))
        assertEquals("1y", age(Duration.ofDays(365)))
    }

    @Test
    fun `a message dated after now reads as now`() {
        assertEquals("now", age(Duration.ofMinutes(-5)))
    }
}
