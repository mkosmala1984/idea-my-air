package dev.myair.editor

import com.intellij.mcpserver.annotations.McpTool
import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.mcp.FoldersMcpToolset
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionSystemPromptTest {
    @Test
    fun `the brief is asked for only while briefs are on`() {
        assertTrue("my_air_set_brief" in appendedSystemPrompt(briefs = true))
        assertFalse("my_air_set_brief" in appendedSystemPrompt(briefs = false))
    }

    @Test
    fun `both prompts point at the stored base`() {
        listOf(true, false).forEach { assertTrue("branch.NAME.myAirBase" in appendedSystemPrompt(it)) }
    }

    @Test
    fun `both prompts name every folder tool`() {
        val tools = FoldersMcpToolset::class.java.declaredMethods.filter { it.isAnnotationPresent(McpTool::class.java) }.map { it.name }.toSet()
        assertEquals(setOf("my_air_sessions_find", "my_air_folders_get", "my_air_folders_apply", "my_air_plan_get", "my_air_plan_apply", "my_air_history_list", "my_air_history_restore"), tools)
        listOf(true, false).forEach { briefs -> tools.forEach { assertTrue(it in appendedSystemPrompt(briefs), it) } }
    }

    @Test
    fun `the prompt is one line a Windows command line passes whole`() {
        listOf(true, false).forEach { briefs ->
            assertEquals(emptyList<Char>(), appendedSystemPrompt(briefs).filter { it in "\"<>\r\n" }.toList())
        }
    }

    /** Folder rtm bound to /rtm holds plan folders a (M1 with T2, T3) and b; folder x is no plan. */
    private val structure = SessionFolders()
        .createFolder(null, "rtm", "rtm", dirs = listOf(Path.of("/rtm").toString()))
        .createFolder("rtm", "a", "a", plan = Plan("Plan **a**"))
        .createFolder("rtm", "b", "b", plan = Plan())
        .createFolder(null, "x", "x")
        .addMilestone("a", "Export").addTask("M1", "Fields").addTask("M1", "Checks")
        .editTask("T2") { it.copy(description = "Map \"every\" <field>\n\n- dates") }

    private fun session(id: String, planTask: String? = null) =
        ClaudeSession(id, Path.of("/rtm"), id, Instant.EPOCH, emptyList(), emptyList(), Brief("g", emptyList(), planTask))

    private fun SessionFolders.planOf(id: String, session: ClaudeSession?, start: PlanStart?) = sessionPlan(this, id, session, start)?.let { it.first.id to it.second?.id }

    @Test
    fun `a session's plan is its brief's task, else the task it started on, else the plan folder it is filed in`() {
        val filed = structure.assign("s", "b").assign("new", "a")
        assertEquals("a" to "T2", filed.planOf("s", session("s", "T2"), null))
        assertEquals("b" to null, filed.planOf("s", session("s", "T99"), null))
        assertEquals("a" to "T3", filed.planOf("new", null, PlanStart("a", "T3")))
        assertEquals("a" to null, filed.planOf("new", null, null))
        assertEquals("a" to "T2", filed.planOf("new", session("new", "T2"), PlanStart("a", "T3")))
        // A directory with two plans names neither.
        assertEquals(null, filed.planOf("other", session("other"), null))
        assertEquals(null, filed.assign("other", "x").planOf("other", session("other"), null))
    }

    @Test
    fun `the plan prompt carries names, the path and descriptions as written, but no status`() {
        val folder = structure.find("a")!!
        val task = structure.findTask("T2")!!.second
        val prompt = planPrompt(structure, folder, task, briefs = true)
        listOf("rtm/a", "Plan **a**", "Task T2: Fields", "Map \"every\" <field>\n\n- dates", "Milestone M1: Export", "plan_task").forEach { assertTrue(it in prompt, it) }
        assertFalse("pending" in prompt)
        assertFalse("plan_task" in planPrompt(structure, folder, task, briefs = false))
        assertFalse("Task" in planPrompt(structure, structure.find("b")!!, null, briefs = true))
    }
}
