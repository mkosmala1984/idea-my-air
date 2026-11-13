package dev.myair.dashboard

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalysesTest {
    @TempDir
    lateinit var dir: Path

    private val t = Instant.parse("2026-11-13T10:00:00Z")

    @Test
    fun `a written analysis reads back by session id`() {
        val store = AnalysisStore(dir.resolve("analysis"))
        val written = mapOf(
            "a" to Analysis(t, Verdict.OPEN, "tests for the handshake, PR not created", t.plusSeconds(60)),
            "b" to Analysis(t.minusSeconds(3600), Verdict.DONE, "", t.plusSeconds(120)),
        )
        written.forEach { (id, analysis) -> store.write(id, analysis) }
        assertEquals(written, store.readAll())
    }

    @Test
    fun `a missing store reads as empty`() {
        assertEquals(emptyMap(), AnalysisStore(dir.resolve("none")).readAll())
    }

    @Test
    fun `a malformed analysis file throws naming it`() {
        val store = AnalysisStore(dir)
        dir.resolve("broken.json").writeText("""{"lastMessage":"2026-11-13T10:00:00Z","verdict":"open"}""")
        val e = assertFailsWith<IllegalStateException> { store.readAll() }
        assertTrue("broken.json" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `an open verdict needs what is left`() {
        assertFailsWith<IllegalArgumentException> { Analysis(t, Verdict.OPEN, " ", t) }
    }
}
