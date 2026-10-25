package dev.myair.claude

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BriefTest {
    private fun task(content: String, status: String = "pending") = """{"content":"$content","status":"$status"}"""

    private fun rejected(goal: String, tasks: String) = assertFailsWith<IllegalArgumentException> { Brief.input(goal, tasks) }.message!!

    @Test
    fun `a valid input becomes the brief`() {
        assertEquals(
            Brief("Migrate AGC", listOf(BriefTask("export", TaskStatus.COMPLETED), BriefTask("map", TaskStatus.IN_PROGRESS))),
            Brief.input("Migrate AGC", "[${task("export", "completed")},${task("map", "in_progress")}]"),
        )
        assertEquals(Brief("Goal", emptyList()), Brief.input("Goal", "[]"))
    }

    @Test
    fun `the goal must be one non-blank sentence of at most 200 chars`() {
        assertTrue("goal" in rejected(" ", "[]"))
        assertTrue("goal" in rejected("x".repeat(201), "[]"))
    }

    @Test
    fun `tasks must be a JSON array of at most 15 items`() {
        assertTrue("JSON array" in rejected("Goal", "{}"))
        assertTrue("JSON array" in rejected("Goal", "[oops"))
        assertTrue("15" in rejected("Goal", (1..16).joinToString(",", "[", "]") { task("t$it") }))
    }

    @Test
    fun `each task needs non-blank content of at most 120 chars and a known status`() {
        assertTrue("content" in rejected("Goal", "[${task(" ")}]"))
        assertTrue("content" in rejected("Goal", "[${task("x".repeat(121))}]"))
        assertTrue("done" in rejected("Goal", "[${task("x", "done")}]"))
    }

    @Test
    fun `at most one task is in progress`() {
        assertTrue("in_progress" in rejected("Goal", "[${task("a", "in_progress")},${task("b", "in_progress")}]"))
    }
}
