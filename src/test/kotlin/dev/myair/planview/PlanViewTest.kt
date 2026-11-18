package dev.myair.planview

import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import dev.myair.folders.Blocker
import dev.myair.folders.EvalKind
import dev.myair.folders.Milestone
import dev.myair.folders.Plan
import dev.myair.folders.PlanChanges
import dev.myair.folders.PlanStatus
import dev.myair.folders.PlanTask
import dev.myair.folders.Requirement
import dev.myair.folders.Review
import dev.myair.folders.ReviewStatus
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

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
        val done = PlanChanges.apply(structure, "f", """[{"op":"update","id":"T2","status":"done"}]""", java.time.LocalDate.of(2026, 11, 18))
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

    @Test
    fun `milestones not done and tasks in progress or waiting start open, and a toggle flips one`() {
        val done = Milestone("M1", "a", done = true)
        val pending = PlanTask("T2", "b")
        val working = PlanTask("T3", "c", status = PlanStatus.IN_PROGRESS)
        val folding = Folding()
        assertEquals(listOf(false, true, false, true), listOf(folding.open(done), folding.open(done.copy(done = false)), folding.open(pending), folding.open(working)))
        val flipped = folding.toggle("M1").toggle("T2").toggle("T3")
        assertEquals(listOf(true, true, false), listOf(flipped.open(done), flipped.open(pending), flipped.open(working)))
        assertEquals(folding, flipped.toggle("M1").toggle("T2").toggle("T3"))
    }

    @Test
    fun `fold all opens or folds every milestone and task, whatever their defaults`() {
        val milestones = listOf(
            Milestone("M1", "a", done = true, tasks = listOf(PlanTask("T2", "b"), PlanTask("T3", "c", status = PlanStatus.WAITING))),
            Milestone("M4", "d", tasks = listOf(PlanTask("T5", "e", status = PlanStatus.DONE))),
        )
        val all = { f: Folding -> milestones.map(f::open) + milestones.flatMap { it.tasks }.map(f::open) }
        val plan = Plan(milestones = milestones)
        assertEquals(List(5) { true }, all(Folding.all(true, plan)))
        assertEquals(List(5) { false }, all(Folding.all(false, plan)))
    }

    @Test
    fun `the view carries the folder's plan`() {
        assertSame(structure.find("f")!!.plan, planViewOf(structure, "f", emptyList(), null)!!.plan)
    }

    @Test
    fun `requirements start open, review open while a blocker is unresolved, other sections folded when they hold anything`() {
        val today = LocalDate.of(2026, 11, 18)
        val filled = structure.setConstraints("f", "No new deps").addDecision("f", "Use Gson", today).addBlocker("f", "Race", today)
        val plan = filled.find("f")!!.plan!!
        val folding = Folding()
        assertEquals(true, folding.open(Section.REQUIREMENTS, plan))
        assertEquals(true, folding.open(Section.REVIEW, plan))
        assertEquals(false, folding.open(Section.CONSTRAINTS, plan))
        assertEquals(false, folding.open(Section.DECISIONS, plan))
        assertEquals(false, folding.open(Section.IDEAL, plan.copy(description = "Ideal")))
        assertEquals(true, folding.open(Section.IDEAL, plan))
        val resolved = filled.editBlocker("f", "B1") { it.copy(resolved = true) }.find("f")!!.plan!!
        assertEquals(false, folding.open(Section.REVIEW, resolved))
        assertEquals(true, folding.toggle("constraints").open(Section.CONSTRAINTS, plan))
        val empty = structure.find("f")!!.plan!!
        assertEquals(listOf(true, true, true, false), listOf(Section.CONSTRAINTS, Section.DECISIONS, Section.REQUIREMENTS, Section.REVIEW).map { folding.open(it, empty) })
    }

    @Test
    fun `fold all opens or folds every section too`() {
        val today = LocalDate.of(2026, 11, 18)
        val plan = structure.setConstraints("f", "c").addBlocker("f", "b", today).find("f")!!.plan!!.copy(description = "d")
        assertEquals(List(5) { true }, Section.entries.map { Folding.all(true, plan).open(it, plan) })
        assertEquals(List(5) { false }, Section.entries.map { Folding.all(false, plan).open(it, plan) })
    }

    @Test
    fun `the header counts proven requirements and names the review`() {
        val proven = Requirement("R1", "r", EvalKind.CODE, evidence = listOf("commit abcdef1"))
        val requirements = List(4) { proven.copy(id = "R${it + 1}") } +
            proven.copy(id = "R5", evidence = emptyList()) + proven.copy(id = "R6", evidence = listOf("not done: later"))
        assertEquals("R 4/6", requirementsSummary(requirements))
        assertNull(requirementsSummary(emptyList()))
        assertEquals("review: not done", reviewSummary(null))
        assertEquals("review: not done", reviewSummary(Review(ReviewStatus.NOT_DONE, null, "2026-11-18")))
        assertEquals("review: in progress", reviewSummary(Review(ReviewStatus.IN_PROGRESS, null, "2026-11-18")))
        val blockers = listOf(Blocker("B1", "a"), Blocker("B2", "b"), Blocker("B3", "c", resolved = true))
        assertEquals("review: 2 blockers", reviewSummary(Review(ReviewStatus.DONE, "abcdef1", "2026-11-18", blockers)))
        assertEquals("review: done", reviewSummary(Review(ReviewStatus.DONE, "abcdef1", "2026-11-18", blockers.map { it.copy(resolved = true) })))
    }

    @Test
    fun `status counts leave canceled tasks out and open work names what is not pending or done`() {
        val tasks = listOf(PlanStatus.DONE, PlanStatus.DONE, PlanStatus.IN_PROGRESS, PlanStatus.WAITING, PlanStatus.CANCELED, PlanStatus.PENDING)
            .mapIndexed { i, status -> PlanTask("T$i", "t", status) }
        val counts = statusCounts(tasks)
        assertEquals(mapOf(PlanStatus.DONE to 2, PlanStatus.IN_PROGRESS to 1, PlanStatus.WAITING to 1, PlanStatus.PENDING to 1), counts)
        assertEquals("1 in progress · 1 waiting for you", openWork(counts))
        assertEquals("3 blocked", openWork(mapOf(PlanStatus.BLOCKED to 3, PlanStatus.DONE to 1)))
        assertEquals("", openWork(statusCounts(emptyList())))
    }
}
