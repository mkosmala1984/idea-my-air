package dev.myair.planview

import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanEditTest {
    /** Plan folder f: M1 holds T2 and T3. */
    private val structure = SessionFolders()
        .createFolder(null, "f", "Plan", plan = Plan()).addMilestone("f", "First").addTask("M1", "A").addTask("M1", "B")
        .editTask("T2") { it.copy(next = "go", description = "about A") }

    private fun PlanEdit.on(text: String) = checkNotNull(apply("f", text)) { "refused '$text'" }(structure)

    @Test
    fun `a title edit renames the task and refuses a blank title`() {
        assertEquals("Renamed", PlanEdit.Title("T2").on(" Renamed ").findTask("T2")!!.second.title)
        assertEquals("Stage", PlanEdit.Title("M1").on("Stage").findMilestone("M1")!!.second.title)
        assertNull(PlanEdit.Title("T2").apply("f", "  "))
        assertNull(PlanEdit.NewTask("M1").apply("f", ""))
    }

    @Test
    fun `an empty next step or description clears it`() {
        assertNull(PlanEdit.Next("T2").on(" ").findTask("T2")!!.second.next)
        assertNull(PlanEdit.Description("T2").on("").findTask("T2")!!.second.description)
        assertEquals("Plan text", PlanEdit.Description(null).on("Plan text\n").find("f")!!.plan!!.description)
        assertNull(PlanEdit.Description(null).on("").find("f")!!.plan!!.description)
    }

    @Test
    fun `a new task goes last in its milestone`() {
        val added = PlanEdit.NewTask("M1").on("C")
        assertEquals(listOf("A", "B", "C"), added.findMilestone("M1")!!.second.tasks.map { it.title })
        assertEquals(listOf("First", "Second"), PlanEdit.NewMilestone.on("Second").find("f")!!.plan!!.milestones.map { it.title })
    }

    @Test
    fun `an edit of a deleted task changes nothing`() {
        val deleted = structure.deletePlanItem("T2")
        for (edit in listOf(PlanEdit.Title("T2"), PlanEdit.Next("T2"), PlanEdit.Description("T2"))) {
            assertEquals(deleted, edit.apply("f", "x")!!(deleted))
        }
    }

    @Test
    fun `an edit starts from the item's current text and has none once the item is gone`() {
        val plan = structure.find("f")!!.plan!!
        assertEquals("A", PlanEdit.Title("T2").current(plan))
        assertEquals("go", PlanEdit.Next("T2").current(plan))
        assertEquals("", PlanEdit.Next("T3").current(plan))
        assertEquals("", PlanEdit.NewTask("M1").current(plan))
        assertNull(PlanEdit.Title("T9").current(plan))
        assertNull(PlanEdit.NewTask("M9").current(plan))
    }
}
