package dev.myair.editor

import com.intellij.mcpserver.annotations.McpTool
import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import dev.myair.folders.EvalKind
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.mcp.FoldersMcpToolset
import dev.myair.mcp.ReviewMcpToolset
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionSystemPromptTest {
    /** The settings that can occur: filing works only with briefs on. */
    private val settings = listOf(true to true, true to false, false to false)

    @Test
    fun `the brief is asked for only while briefs are on`() {
        assertTrue("my_air_set_brief" in appendedSystemPrompt(briefs = true, filing = false))
        assertFalse("my_air_set_brief" in appendedSystemPrompt(briefs = false, filing = false))
    }

    @Test
    fun `filing is asked for only with briefs and filing on`() {
        assertTrue("my_air_folders_get first" in appendedSystemPrompt(briefs = true, filing = true))
        assertFalse("my_air_folders_get first" in appendedSystemPrompt(briefs = true, filing = false))
        assertFalse("my_air_folders_get first" in appendedSystemPrompt(briefs = false, filing = false))
    }

    @Test
    fun `both prompts point at the stored base`() {
        settings.forEach { (briefs, filing) -> assertTrue("branch.NAME.myAirBase" in appendedSystemPrompt(briefs, filing)) }
    }

    @Test
    fun `both prompts name every folder tool`() {
        fun toolsOf(toolset: Class<*>) = toolset.declaredMethods.filter { it.isAnnotationPresent(McpTool::class.java) }.map { it.name }.toSet()
        val folderTools = toolsOf(FoldersMcpToolset::class.java)
        assertEquals(setOf("my_air_sessions_find", "my_air_session_start", "my_air_folders_get", "my_air_folders_apply", "my_air_plan_get", "my_air_plan_apply", "my_air_plan_set_manager", "my_air_history_list", "my_air_history_restore"), folderTools)
        val reviewTools = toolsOf(ReviewMcpToolset::class.java)
        assertEquals(setOf("my_air_review_get", "my_air_review_apply", "my_air_review_report"), reviewTools)
        val tools = folderTools + reviewTools
        settings.forEach { (briefs, filing) -> tools.forEach { assertTrue(it in appendedSystemPrompt(briefs, filing), it) } }
    }

    @Test
    fun `the prompt is one line a Windows command line passes whole`() {
        settings.forEach { (briefs, filing) ->
            assertEquals(emptyList<Char>(), appendedSystemPrompt(briefs, filing).filter { it in "\"<>\r\n" }.toList())
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
    fun `a plan's manager works in its plan on no task, wherever it is filed and whatever task its brief names`() {
        val moved = structure.assign("m", "x")
        assertEquals("b" to null, sessionPlan(moved, "m", session("m", "T2"), PlanStart("b", null), managed = "b")?.let { it.first.id to it.second?.id })
        assertEquals("b" to null, sessionPlan(structure, "m", null, null, managed = "b")?.let { it.first.id to it.second?.id })
    }

    @Test
    fun `the plan prompt carries names, the path and descriptions as written, but no status`() {
        val folder = structure.find("a")!!
        val task = structure.findTask("T2")!!.second
        val prompt = planPrompt(structure, folder, task, briefs = true)
        listOf("rtm/a", "## Ideal state", "Plan **a**", "Task T2: Fields", "Map \"every\" <field>\n\n- dates", "Milestone M1: Export", "plan_task").forEach { assertTrue(it in prompt, it) }
        assertFalse("pending" in prompt)
        assertFalse("plan_task" in planPrompt(structure, folder, task, briefs = false))
        assertFalse("Task" in planPrompt(structure, structure.find("b")!!, null, briefs = true))
    }

    private val sectioned = structure
        .setConstraints("a", "No new dependencies")
        .addRequirement("a", "Fields map", EvalKind.CODE, listOf("T2"))
        .addRequirement("a", "Checks run", EvalKind.ASSERT, listOf("T3"))

    @Test
    fun `a task session gets the ideal state, constraints and only the requirements of its task`() {
        val prompt = planPrompt(sectioned, sectioned.find("a")!!, sectioned.findTask("T2")!!.second, briefs = true)
        listOf("## Ideal state", "## Constraints", "No new dependencies", "- **R1** (code) Fields map").forEach { assertTrue(it in prompt, it) }
        assertFalse("R2" in prompt)
        assertTrue("add_evidence" in prompt, prompt)
        assertFalse("add_evidence" in planPrompt(structure, structure.find("a")!!, structure.findTask("T2")!!.second, briefs = true))
    }

    @Test
    fun `a manager gets every requirement and the rules for decisions, evidence and review`() {
        val prompt = planPrompt(sectioned, sectioned.find("a")!!, null, briefs = true, manager = true)
        listOf("- **R1** (code) Fields map", "- **R2** (assert) Checks run", "add_decision", "add_evidence", "set_review", "add_blocker").forEach { assertTrue(it in prompt, it) }
    }

    @Test
    fun `a plan without sections has no empty headings`() {
        val prompt = planPrompt(structure, structure.find("a")!!, null, briefs = true)
        assertFalse("## Constraints" in prompt)
        assertFalse("## Requirements" in prompt)
        assertFalse("## Requirements" in planPrompt(sectioned, sectioned.find("a")!!, null, briefs = true))
    }

    @Test
    fun `a plan's manager is told it manages the plan`() {
        val folder = structure.find("a")!!
        val manager = planPrompt(structure, folder, null, briefs = true, manager = true)
        listOf("manages this plan", "my_air_plan_apply", "beside", "agreed").forEach { assertTrue(it in manager, it) }
        // The plan's path is the plan argument of my_air_plan_apply for every session, so only the manager section is checked.
        assertFalse("manages this plan" in planPrompt(structure, folder, null, briefs = true, manager = false))
        assertTrue(manager.startsWith(planPrompt(structure, folder, null, briefs = true)))
    }
}
