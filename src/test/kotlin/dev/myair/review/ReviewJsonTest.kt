package dev.myair.review

import com.google.gson.JsonParser
import dev.myair.claude.SessionStatus
import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.Report
import dev.myair.store.Run
import dev.myair.store.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewJsonTest {
    private fun run(id: String, job: String?, reported: String? = null, commit: String? = null, session: String? = null) =
        Run(id, "a", session, job, "main..HEAD", "2026-11-19T10:00:00Z", reported, commit)

    private fun finding(id: String, line: Int, commit: String, severity: Severity = Severity.minor) =
        Finding(id, "a.kt", "bug", listOf(Report("W1", "a", commit, line, severity, "s", "x")))

    private val review = BranchReview(
        "b", "main", Path.of("E:/d"), 7, null, null, "sum", emptyList(),
        listOf(run("W1", "j1", "2026-11-19T10:05:00Z", "c1"), run("W2", "j2", session = "s2"), run("W3", "j3", session = "s3"), run("W4", "j4")),
        listOf(finding("F1", 10, "c1", Severity.blocker), finding("F2", 40, "c1"), finding("F3", 10, "gone")),
        emptyList(), mapOf("F" to 4, "W" to 5), mapOf("F1" to "B1"),
    )

    private val git = ReviewGit(null, "c2", 1, mapOf("c1" to mapOf("a.kt" to listOf(12..12)), "gone" to null), null, null)

    @Test fun `review json carries maybe fixed and run status`() {
        val json = JsonParser.parseString(reviewJson("rtm/Review: b", review, git, mapOf("j2" to SessionStatus.WORKING, "j3" to SessionStatus.NEEDS_INPUT, "j4" to SessionStatus.IDLE))).asJsonObject
        assertEquals("rtm/Review: b", json["folder"].asString)
        val findings = json["findings"].asJsonArray.associate { it.asJsonObject["id"].asString to it.asJsonObject }
        assertTrue(findings.getValue("F1")["maybe_fixed"].asBoolean)
        assertFalse(findings.getValue("F2")["maybe_fixed"].asBoolean)
        assertFalse(findings.getValue("F3")["maybe_fixed"].asBoolean, "a commit git does not know makes nothing maybe fixed")
        assertEquals("blocker", findings.getValue("F1")["severity"].asString)
        assertEquals(10, findings.getValue("F1")["line"].asInt)
        val runs = json["runs"].asJsonArray.associate { it.asJsonObject["id"].asString to it.asJsonObject["status"].asString }
        assertEquals(mapOf("W1" to "reported", "W2" to "running", "W3" to "waiting", "W4" to "stopped"), runs)
        assertEquals("c1", json["reviewed"].asString)
        assertEquals("c2", json["head"].asString)
        assertEquals(1, json["since_reviewed"].asInt)
        assertTrue(json["git_error"].isJsonNull)
        listOf("format", "next", "blockers").forEach { assertFalse(json.has(it), it) }
    }

    @Test fun `without git facts nothing is maybe fixed and the git keys are null`() {
        val json = JsonParser.parseString(reviewJson("r", review, null, emptyMap())).asJsonObject
        assertTrue(json["findings"].asJsonArray.none { it.asJsonObject["maybe_fixed"].asBoolean })
        listOf("head", "since_reviewed", "git_error").forEach { assertTrue(json[it].isJsonNull, it) }
    }
}
