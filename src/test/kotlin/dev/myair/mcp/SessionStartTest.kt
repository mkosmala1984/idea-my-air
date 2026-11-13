package dev.myair.mcp

import dev.myair.store.Preset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionStartTest {
    private val review = Preset("Review", "opus", "high", "E:/dev/rtm", worktree = true, base = "origin/main", addDirs = listOf("E:/dev/lib"))

    private fun start(
        preset: String? = null, dir: String? = null, model: String? = null, effort: String? = null, addDirs: String? = null,
        worktree: Boolean? = null, base: String? = null,
    ) = sessionStartPreset(listOf(review), preset, dir, model, effort, addDirs, worktree, base)

    @Test
    fun `without a configuration sets only what is given`() {
        val start = start(dir = "E:/dev/x", model = "haiku", addDirs = """["E:/a", "E:/b"]""")
        assertEquals(listOf("--model", "haiku", "--add-dir", "E:/a", "--add-dir", "E:/b"), start.claudeArgs())
        assertEquals("E:/dev/x", start.project)
        assertEquals(false, start.worktree)
    }

    @Test
    fun `takes the named configuration, each argument replacing its field`() {
        assertEquals(review.copy(effort = "low", addDirs = emptyList()), start(preset = "Review", effort = "low", addDirs = "[]").copy(name = "Review"))
        assertEquals(review.copy(base = "main"), start(preset = "Review", base = "main"))
        assertEquals(review.copy(worktree = false, base = null), start(preset = "Review", worktree = false))
    }

    @Test
    fun `refuses what it cannot start`() {
        assertTrue("configurations: Review" in assertThrows<IllegalArgumentException> { start(preset = "Nope") }.message!!)
        assertThrows<IllegalArgumentException> { start(worktree = true) }
        assertThrows<IllegalArgumentException> { start(base = "main") }
        assertThrows<IllegalArgumentException> { start(effort = "huge") }
        assertThrows<IllegalArgumentException> { start(addDirs = """"E:/a"""") }
        assertThrows<IllegalArgumentException> { start(addDirs = "[1]") }
        assertThrows<IllegalArgumentException> { start(addDirs = "[") }
    }

    @Test
    fun `the configuration's prompt leads the first message, the agent's prompt following it`() {
        val reviewing = review.copy(prompt = "/code-review")
        assertEquals("/code-review 7506", firstMessage(reviewing, "7506"))
        assertEquals("/code-review", firstMessage(reviewing, null))
        assertEquals("fix the test", firstMessage(review, "fix the test"))
        assertThrows<IllegalArgumentException> { firstMessage(review, null) }
        assertThrows<IllegalArgumentException> { firstMessage(reviewing, " ") }
    }
}
