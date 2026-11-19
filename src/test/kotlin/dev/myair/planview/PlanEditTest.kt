package dev.myair.planview

import dev.myair.folders.Blocker
import dev.myair.folders.Decision
import dev.myair.folders.EvalKind
import dev.myair.folders.Plan
import dev.myair.folders.Requirement
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanEditTest {
    /** Plan folder f: M1 holds T2 and T3. */
    private val structure = SessionFolders()
        .createFolder(null, "f", "Plan", plan = Plan()).addMilestone("f", "First").addTask("M1", "A").addTask("M1", "B")
        .editTask("T2") { it.copy(next = "go", description = "about A") }

    private val today = LocalDate.of(2026, 11, 18)

    private fun PlanEdit.on(text: String, on: SessionFolders = structure) = checkNotNull(apply("f", text, today)) { "refused '$text'" }(on)

    private fun SessionFolders.plan() = find("f")!!.plan!!

    @Test
    fun `a title edit renames the task and refuses a blank title`() {
        assertEquals("Renamed", PlanEdit.Title("T2").on(" Renamed ").findTask("T2")!!.second.title)
        assertEquals("Stage", PlanEdit.Title("M1").on("Stage").findMilestone("M1")!!.second.title)
        assertNull(PlanEdit.Title("T2").apply("f", "  ", today))
        assertNull(PlanEdit.NewTask("M1").apply("f", "", today))
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
            assertEquals(deleted, edit.apply("f", "x", today)!!(deleted))
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

    @Test
    fun `new decision, requirement and blocker edits add items, blank refused`() {
        assertEquals(listOf(Decision("D1", "2026-11-18", "Why")), PlanEdit.NewDecision.on(" Why ").plan().decisions)
        assertEquals(listOf(Requirement("R1", "Holds", EvalKind.CODE)), PlanEdit.NewRequirement.on("Holds").plan().requirements)
        assertEquals(listOf(Blocker("B1", "Race")), PlanEdit.NewBlocker.on("Race").plan().review!!.blockers)
        for (edit in listOf(PlanEdit.NewDecision, PlanEdit.NewRequirement, PlanEdit.NewBlocker)) {
            assertNull(edit.apply("f", " ", today))
            assertEquals("", edit.current(structure.plan()))
        }
    }

    @Test
    fun `a title edit of a requirement, decision or blocker changes its text`() {
        val filled = structure.addRequirement("f", "r", EvalKind.ASSERT).addDecision("f", "d", today).addBlocker("f", "b", today)
        assertEquals("r2", PlanEdit.Title("R1").on(" r2 ", filled).plan().requirements.single().text)
        assertEquals("d2", PlanEdit.Title("D1").on("d2", filled).plan().decisions.single().text)
        assertEquals("b2", PlanEdit.Title("B1").on("b2", filled).plan().review!!.blockers.single().text)
        assertEquals(listOf("r", "d", "b"), listOf("R1", "D1", "B1").map { PlanEdit.Title(it).current(filled.plan()) })
        assertNull(PlanEdit.Title("R1").apply("f", " ", today))
        val gone = filled.deleteEntry("f", "R1")
        assertEquals(gone, PlanEdit.Title("R1").on("x", gone))
        assertNull(PlanEdit.Title("R1").current(gone.plan()))
    }

    @Test
    fun `an empty constraints edit clears them`() {
        val set = PlanEdit.Constraints.on(" No new deps\n")
        assertEquals("No new deps", set.plan().constraints)
        assertEquals("No new deps", PlanEdit.Constraints.current(set.plan()))
        assertEquals("", PlanEdit.Constraints.current(structure.plan()))
        assertNull(PlanEdit.Constraints.on("  ", set).plan().constraints)
    }

    @Test
    fun `an edit of a section's text or item belongs to that section, a milestone's or task's to none`() {
        val sections = listOf(
            PlanEdit.Description(null), PlanEdit.Constraints, PlanEdit.NewDecision, PlanEdit.Title("D3"),
            PlanEdit.NewRequirement, PlanEdit.Title("R1"), PlanEdit.NewBlocker, PlanEdit.Title("B2"),
            PlanEdit.Title("M1"), PlanEdit.Title("T2"), PlanEdit.Description("T2"), PlanEdit.Next("T2"), PlanEdit.NewTask("M1"), PlanEdit.NewMilestone,
        ).map { it.section }
        assertEquals(listOf(
            Section.IDEAL, Section.CONSTRAINTS, Section.DECISIONS, Section.DECISIONS,
            Section.REQUIREMENTS, Section.REQUIREMENTS, Section.REVIEW, Section.REVIEW,
            null, null, null, null, null, null,
        ), sections)
    }

    @Test
    fun `descriptions, constraints, decisions, requirements and blockers are edited over several lines, titles and next steps on one`() {
        val multi = listOf(PlanEdit.Description(null), PlanEdit.Description("T2"), PlanEdit.Constraints, PlanEdit.Title("D1"), PlanEdit.Title("R1"), PlanEdit.Title("B1"))
        val single = listOf(PlanEdit.Title("T2"), PlanEdit.Title("M1"), PlanEdit.Next("T2"), PlanEdit.NewDecision, PlanEdit.NewRequirement)
        assertEquals(List(6) { true } + List(5) { false }, (multi + single).map { it.multiline })
    }

    @Test
    fun `only the edits adding an item are new`() {
        val new = listOf(PlanEdit.NewTask("M1"), PlanEdit.NewMilestone, PlanEdit.NewDecision, PlanEdit.NewRequirement, PlanEdit.NewBlocker)
        val old = listOf(PlanEdit.Title("T2"), PlanEdit.Title("R1"), PlanEdit.Next("T2"), PlanEdit.Description(null), PlanEdit.Constraints)
        assertEquals(List(5) { true } + List(5) { false }, (new + old).map { it.isNew })
    }
}
