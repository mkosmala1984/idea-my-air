package dev.myair.planview

import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import dev.myair.folders.Plan
import dev.myair.folders.PlanChanges
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanViewTest {
    /** Plan folder f: M1 holds T2 and T3, M4 holds T5. */
    private val structure = SessionFolders()
        .createFolder(null, "f", "Plan", plan = Plan()).createFolder(null, "g", "Plain")
        .addMilestone("f", "First").addTask("M1", "A").addTask("M1", "B")
        .addMilestone("f", "Second").addTask("M4", "C")

    private fun session(id: String, task: String?) =
        ClaudeSession(id, Path.of("/app"), id, Instant.EPOCH, emptyList(), emptyList(), brief = Brief("g", emptyList(), planTask = task))

    @Test
    fun `a deleted folder or a folder without a plan has no view`() {
        assertNull(planViewOf(structure, "nope", emptyList(), null))
        assertNull(planViewOf(structure, "g", emptyList(), null))
    }

    @Test
    fun `milestones and tasks come in plan order with their progress`() {
        val done = PlanChanges.apply(structure, "f", """[{"op":"update","id":"T2","status":"done"}]""")
        val view = planViewOf(done, "f", emptyList(), null)!!
        assertEquals(listOf("First", "Second"), view.milestones.map { it.milestone.title })
        assertEquals(listOf("A", "B"), view.milestones[0].tasks.map { it.task.title })
        assertEquals(listOf("1/2", "0/1"), view.milestones.map { it.progress })
    }

    @Test
    fun `a task lists the sessions whose brief names it, in the order given`() {
        val s1 = session("s1", "T2")
        val s2 = session("s2", "T2")
        val view = planViewOf(structure, "f", listOf(s2, session("s3", null), s1), null)!!
        assertEquals(listOf(s2, s1), view.milestones[0].tasks[0].sessions)
        assertEquals(emptyList(), view.milestones[0].tasks[1].sessions)
    }

    @Test
    fun `the manager is not listed under a task`() {
        val view = planViewOf(structure, "f", listOf(session("m", "T2")), "m")!!
        assertEquals(emptyList(), view.milestones[0].tasks[0].sessions)
    }
}
