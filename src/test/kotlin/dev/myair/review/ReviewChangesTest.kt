package dev.myair.review

import dev.myair.store.BranchReview
import dev.myair.store.FindingState
import dev.myair.store.Run
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewChangesTest {
    private val run = Run("W1", "security", null, null, "main..HEAD", "2026-11-19T10:00:00Z")
    private val base = BranchReview("b", "main", Path.of("E:/d"), null, null, null, null, emptyList(), listOf(run), emptyList(), emptyList(), emptyMap())
    private val one = """[{"file":"a.kt","line":10,"category":"bug","severity":"major","summary":"s","failure_scenario":"x"}]"""
    private val now = Instant.parse("2026-11-19T10:05:00Z")
    private val reported = ReviewChanges.report(base, "W1", "abc1234", one, "[]", now)

    private fun apply(changes: String) = ReviewChanges.apply(reported, changes)

    @Test fun `ops apply in order and all or none`() {
        val ok = apply("""[{"op":"set_summary","text":"hi"},{"op":"set_pr","pr":7},{"op":"add_question","text":"why?"},{"op":"resolve_question","id":"Q1"},{"op":"set_state","id":"F1","state":"accepted"}]""")
        assertEquals("hi", ok.summary)
        assertEquals(7, ok.pr)
        assertTrue(ok.questions.single().resolved)
        assertEquals(FindingState.accepted, ok.findings.single().state)
        assertThrows<IllegalArgumentException> { apply("""[{"op":"set_summary","text":"hi"},{"op":"resolve_question","id":"Q9"}]""") }
        assertNull(reported.summary)
    }

    @Test fun `an unknown op or field throws naming the change`() {
        assertTrue(assertThrows<IllegalArgumentException> { apply("""[{"op":"nope"}]""") }.message!!.startsWith("change 1 "))
        assertTrue(assertThrows<IllegalArgumentException> { apply("""[{"op":"set_pr","pr":1},{"op":"set_pr","pr":1,"x":2}]""") }.message!!.startsWith("change 2 "))
    }

    @Test fun `set_state rejected without a reason throws`() {
        listOf("rejected", "fixed", "posted").forEach { state ->
            assertThrows<IllegalArgumentException> { apply("""[{"op":"set_state","id":"F1","state":"$state"}]""") }
        }
        assertEquals("abc1234", apply("""[{"op":"set_state","id":"F1","state":"fixed","commit":"abc1234"}]""").findings.single().fixCommit)
    }

    @Test fun `a report naming no run of the review is refused`() {
        assertThrows<IllegalArgumentException> { ReviewChanges.report(base, "W9", "abc1234", one, "[]", now) }
    }

    @Test fun `a report finding without a severity or with an unknown one records nothing`() {
        assertThrows<IllegalArgumentException> { ReviewChanges.report(base, "W1", "c", """[{"file":"a.kt","line":1,"category":"bug","summary":"s","failure_scenario":"x"}]""", "[]", now) }
        assertThrows<IllegalArgumentException> { ReviewChanges.report(base, "W1", "c", one.replace("major", "huge"), "[]", now) }
        assertThrows<IllegalArgumentException> { ReviewChanges.report(base, "W1", "c", one, "[1]", now) }
    }

    @Test fun `set_state replaces the companion fields, null clearing them`() {
        val rejected = apply("""[{"op":"set_state","id":"F1","state":"rejected","reason":"intended"}]""")
        assertEquals("intended", rejected.findings.single().reason)
        val accepted = ReviewChanges.apply(rejected, """[{"op":"set_state","id":"F1","state":"accepted"}]""")
        assertNull(accepted.findings.single().reason)
    }

    @Test fun `a second report of the same run replaces its questions`() {
        val manual = ReviewChanges.apply(reported, """[{"op":"add_question","text":"mine"}]""")
        val first = ReviewChanges.report(manual, "W1", "c", one, """["a?","b?"]""", now)
        val second = ReviewChanges.report(first, "W1", "c", one, """["a?"]""", now)
        assertEquals(listOf("mine", "a?"), second.questions.map { it.text })
        assertEquals(listOf(null, "W1"), second.questions.map { it.run })
    }

    @Test fun `a report sets reported on its run and adds its questions`() {
        val r = ReviewChanges.report(base, "W1", "abc1234", one, """["a?","b?"]""", now)
        assertEquals("2026-11-19T10:05:00Z", r.runs.single().reported)
        assertEquals(listOf(Triple("Q1", "security", "a?"), Triple("Q2", "security", "b?")), r.questions.map { Triple(it.id, it.reviewer, it.text) })
        assertEquals(1, r.findings.size)
    }
}
