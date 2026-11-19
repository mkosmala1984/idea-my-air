package dev.myair.store

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewsTest {
    private val src = Path.of("E:/x/review.json")

    private fun report(run: String = "W1", line: Int = 10, severity: Severity = Severity.minor) =
        Report(run, "security", "abc1234", line, severity, "sum", "scenario")

    private fun review(blockers: Map<String, String> = emptyMap()) = BranchReview(
        "feature/x", "main", Path.of("E:/dev/a"), 12, "plans/p", PlanManager("s1", Path.of("E:/dev/a"), Path.of("E:/dev/a")), "summary",
        listOf(Chapter("c", "t", listOf("a.kt", "b.kt"))),
        listOf(Run("W1", "security", "s2", "j1", "main..HEAD", "2026-11-19T10:00:00Z", "2026-11-19T10:05:00Z"), Run("W2", "style", null, null, "main..HEAD", "2026-11-19T11:00:00Z")),
        listOf(
            Finding("F1", "a.kt", "bug", listOf(report()), FindingState.rejected, reason = "intended"),
            Finding("F2", "b.kt", "bug", listOf(report(), report("W2", 20, Severity.major)), FindingState.fixed, fixCommit = "def5678"),
            Finding("F3", "c.kt", "bug", listOf(report()), FindingState.posted, comment = "c1"),
        ),
        listOf(Question("Q1", "why?", "style", true, "W2"), Question("Q2", "how?", null)),
        mapOf("F" to 4, "W" to 3, "Q" to 3), blockers,
    )

    @Test fun `a review round-trips through json`() {
        val r = review(mapOf("B1" to "waits for ci"))
        assertEquals(r, parseReview(r.toJson(), src))
        val bare = BranchReview("b", "main", Path.of("E:/d"), null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
        assertEquals(bare, parseReview(bare.toJson(), src))
    }

    @Test fun `a review with every nullable field null round-trips and writes the keys`() {
        val r = BranchReview(
            "b", "main", Path.of("E:/d"), null, null, null, null, emptyList(),
            listOf(Run("W1", "x", null, null, "r", "2026-11-19T10:00:00Z")),
            listOf(Finding("F1", "a", "c", listOf(report()))), listOf(Question("Q1", "t", null)), emptyMap(),
        )
        val json = r.toJson()
        assertEquals(r, parseReview(json, src))
        assertTrue("\"pr\": null" in json && "\"reason\": null" in json && "\"reviewer\": null" in json, json)
    }

    @Test fun `a missing nullable key throws naming the file and the key`() {
        val json = JsonParser.parseString(review().toJson()).asJsonObject.also { it.remove("pr") }.toString()
        val e = assertThrows<IllegalStateException> { parseReview(json, src) }
        assertTrue(src.toString() in e.message!! && "pr" in e.message!!, e.message)
    }

    @Test fun `an unknown key throws naming the file`() {
        val json = JsonParser.parseString(review().toJson()).asJsonObject.also { it.addProperty("bogus", 1) }.toString()
        val e = assertThrows<IllegalStateException> { parseReview(json, src) }
        assertTrue(src.toString() in e.message!! && "bogus" in e.message!!, e.message)
    }

    @Test fun `a missing required key throws naming the file`() {
        val json = JsonParser.parseString(review().toJson()).asJsonObject.also { it.remove("base") }.toString()
        val e = assertThrows<IllegalStateException> { parseReview(json, src) }
        assertTrue(src.toString() in e.message!! && "base" in e.message!!, e.message)
    }

    @Test fun `a format other than 1 throws`() {
        val json = JsonParser.parseString(review().toJson()).asJsonObject.also { it.addProperty("format", 2) }.toString()
        assertThrows<IllegalStateException> { parseReview(json, src) }
    }

    @Test fun `a rejected finding needs a reason`() {
        assertThrows<IllegalArgumentException> { Finding("F1", "a", "c", listOf(report()), FindingState.rejected) }
        assertThrows<IllegalArgumentException> { Finding("F1", "a", "c", listOf(report()), FindingState.fixed) }
        assertThrows<IllegalArgumentException> { Finding("F1", "a", "c", listOf(report()), FindingState.posted) }
        assertThrows<IllegalArgumentException> { Finding("F1", "a", "c", emptyList()) }
        val json = JsonParser.parseString(review().toJson()).asJsonObject.also { it["findings"].asJsonArray[0].asJsonObject.remove("reason") }.toString()
        val e = assertThrows<IllegalStateException> { parseReview(json, src) }
        assertTrue(src.toString() in e.message!! && "reason" in e.message!!, e.message)
    }

    @Test fun `a finding takes its line from the newest report and the highest severity`() {
        val f = Finding("F1", "a", "c", listOf(report(line = 5, severity = Severity.major), report("W2", 9, Severity.nit)))
        assertEquals(9, f.line)
        assertEquals(Severity.major, f.severity)
    }

    @Test fun `ids are numbered per prefix and never reused`() {
        val r = review().copy(next = emptyMap())
        val (f1, r1) = r.nextId("F")
        val (f2, r2) = r1.nextId("F")
        assertEquals(listOf("F1", "F2"), listOf(f1, f2))
        assertEquals("Q1", r2.nextId("Q").first)
        assertEquals("F3", parseReview(r2.toJson(), src).nextId("F").first)
    }

    @Test fun `readAll keeps a broken review as a failure beside good ones`(@TempDir dir: Path) {
        val files = ReviewFiles(dir)
        files.write("good", review())
        dir.resolve("bad").createDirectories()
        dir.resolve("bad").resolve("review.json").writeText("""{"format":1}""")
        dir.resolve("empty").createDirectories()
        val all = files.readAll()
        assertEquals(setOf("good", "bad"), all.keys)
        assertEquals(review(), all.getValue("good").getOrThrow())
        assertTrue(all.getValue("bad").isFailure)
    }

    @Test fun `blockers is written only when not empty`() {
        assertFalse("blockers" in JsonParser.parseString(review().toJson()).asJsonObject.keySet())
        assertTrue("blockers" in JsonParser.parseString(review(mapOf("B1" to "x")).toJson()).asJsonObject.keySet())
    }
}
