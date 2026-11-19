package dev.myair.store

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewProfilesTest {
    @Test
    fun `profiles parse and an unknown key throws`() {
        val ok = """{"profiles":[{"name":"deep","reviewers":[{"name":"a","prompt":"p","model":"opus","effort":"high"},{"name":"b","prompt":"q"}]}]}"""
        assertEquals(
            listOf(ReviewProfile("deep", listOf(Reviewer("a", "p", "opus", "high"), Reviewer("b", "q")))),
            parseProfiles(ok),
        )
        assertThrows(IllegalStateException::class.java) { parseProfiles(ok.replace("\"prompt\":\"q\"", "\"prompt\":\"q\",\"color\":1")) }
        assertThrows(IllegalArgumentException::class.java) { parseProfiles(ok.replace("high", "huge")) }
        assertThrows(IllegalStateException::class.java) { parseProfiles(ok.replace("\"name\":\"b\"", "\"name\":\"a\"")) }
    }

    @Test
    fun `a missing profiles file means no profiles`() {
        val dir = Files.createTempDirectory("profiles")
        val store = ReviewProfileStore(dir.resolve("review-profiles.json"))
        assertEquals(emptyList<ReviewProfile>(), store.read().getOrThrow())
        Files.writeString(store.file, "{}")
        assertTrue(store.read().isFailure)
    }
}
