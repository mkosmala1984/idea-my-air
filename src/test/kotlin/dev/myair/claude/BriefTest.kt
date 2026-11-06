package dev.myair.claude

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BriefTest {
    private fun task(content: String, status: String = "pending") = """{"content":"$content","status":"$status"}"""

    private fun json(text: String) = com.google.gson.JsonParser.parseString(text).asJsonObject

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
    fun `a plan task is an id such as T12, read from the call's input when given and not null`() {
        assertEquals(Brief("Goal", emptyList(), "T12"), Brief.input("Goal", "[]", "T12"))
        listOf("12", "t12", "T0", "M3").forEach { assertFailsWith<IllegalArgumentException>(it) { Brief.input("Goal", "[]", it) } }
        fun call(input: String) = Brief.next(null, "mcp__idea__my_air_set_brief", json(input), Brief.OK)
        assertEquals("T3", call("""{"goal":"g","tasks":"[]","plan_task":"T3"}""").planTask)
        assertEquals(null, call("""{"goal":"g","tasks":"[]","plan_task":null}""").planTask)
        assertEquals(null, call("""{"goal":"g","tasks":"[]"}""").planTask)
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

    @Test
    fun `a title is non-blank and at most 60 chars`() {
        assertEquals("Migracja AGC", Brief.input("g", "[]", title = "Migracja AGC").title)
        assertFailsWith<IllegalArgumentException> { Brief.input("g", "[]", title = " ") }
        assertFailsWith<IllegalArgumentException> { Brief.input("g", "[]", title = "x".repeat(61)) }
    }

    @Test
    fun `the folder comes from the result, and fields left out keep their previous value`() {
        val first = Brief.next(
            null, "mcp__idea__my_air_set_brief", json("""{"goal":"g","tasks":"[]","plan_task":"T3","title":"A","folder":"rtm/fixes"}"""),
            Brief.folderResult("f1", "rtm/fixes", created = true),
        )
        assertEquals(Brief("g", emptyList(), "T3", "A", "f1"), first)
        val second = Brief.next(first, "mcp__idea__my_air_set_brief", json("""{"goal":"h","tasks":"[]","plan_task":null}"""), Brief.OK)
        assertEquals(Brief("h", emptyList(), "T3", "A", "f1"), second)
        val third = Brief.next(second, "mcp__idea__my_air_set_brief", json("""{"goal":"h","tasks":"[]","plan_task":"T4","title":"B"}"""), Brief.folderResult("f2", "rtm/x", created = false))
        assertEquals(Brief("h", emptyList(), "T4", "B", "f2"), third)
    }

    @Test
    fun `an accepted call with an unknown result throws`() {
        assertFailsWith<IllegalStateException> { Brief.next(null, "mcp__idea__my_air_set_brief", json("""{"goal":"g","tasks":"[]"}"""), "done") }
    }

    @Test
    fun `a Desktop plan keeps title, folder and plan task`() {
        val brief = Brief("g", emptyList(), "T3", "A", "f1")
        assertEquals(
            Brief(null, listOf(BriefTask("x", TaskStatus.PENDING)), "T3", "A", "f1"),
            Brief.next(brief, Brief.UPDATE_PLAN, json("""{"items":[{"content":"x","status":"pending"}]}"""), "Plan shown (1 steps)."),
        )
    }
}
