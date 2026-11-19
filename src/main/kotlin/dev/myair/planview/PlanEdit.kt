package dev.myair.planview

import dev.myair.folders.EvalKind
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import java.time.LocalDate

/**
 * A text being edited in place in the plan view; milestone, task, requirement, decision and blocker ids tell themselves
 * apart by their `M`/`T`/`R`/`D`/`B` prefix.
 */
sealed interface PlanEdit {
    /** The title of milestone or task [id], or the text of requirement, decision or blocker [id]. */
    data class Title(val id: String) : PlanEdit

    data class Next(val taskId: String) : PlanEdit

    /** The Markdown description of milestone or task [id]; null for the plan's own. */
    data class Description(val id: String?) : PlanEdit

    data class NewTask(val milestoneId: String) : PlanEdit

    data object NewMilestone : PlanEdit

    /** The plan's Markdown constraints. */
    data object Constraints : PlanEdit

    data object NewDecision : PlanEdit

    data object NewRequirement : PlanEdit

    data object NewBlocker : PlanEdit
}

/**
 * The change saving [text] makes to the plan of [folderId], applied by id to the structure as it is then, so an item
 * deleted meanwhile is left as it is; null for a blank title or new item, which is refused. An empty next step,
 * description or constraints clears it. A new decision is dated [today]; a new requirement is proven by code.
 */
fun PlanEdit.apply(folderId: String, text: String, today: LocalDate): ((SessionFolders) -> SessionFolders)? {
    val trimmed = text.trim()
    val value = trimmed.ifEmpty { null }
    return when (this) {
        is PlanEdit.Title -> if (value == null) null else when (kind(id)) {
            'M' -> { s -> s.editMilestone(id) { it.copy(title = value) } }
            'T' -> { s -> s.editTask(id) { it.copy(title = value) } }
            'R' -> { s -> s.editRequirement(folderId, id) { it.copy(text = value) } }
            'D' -> { s -> s.editDecision(folderId, id) { it.copy(text = value) } }
            else -> { s -> s.editBlocker(folderId, id) { it.copy(text = value) } }
        }
        is PlanEdit.Next -> { s -> s.editTask(taskId) { it.copy(next = value) } }
        is PlanEdit.Description -> when (id?.let(::kind)) {
            null -> { s -> s.setDescription(folderId, value) }
            'M' -> { s -> s.editMilestone(id) { it.copy(description = value) } }
            'T' -> { s -> s.editTask(id) { it.copy(description = value) } }
            else -> throw IllegalArgumentException("only a milestone or task has a description: $id")
        }
        is PlanEdit.NewTask -> if (value == null) null else { s -> s.addTask(milestoneId, value) }
        PlanEdit.NewMilestone -> if (value == null) null else { s -> s.addMilestone(folderId, value) }
        PlanEdit.Constraints -> { s -> s.setConstraints(folderId, value) }
        PlanEdit.NewDecision -> if (value == null) null else { s -> s.addDecision(folderId, value, today) }
        PlanEdit.NewRequirement -> if (value == null) null else { s -> s.addRequirement(folderId, value, EvalKind.CODE) }
        PlanEdit.NewBlocker -> if (value == null) null else { s -> s.addBlocker(folderId, value, today) }
    }
}

/** The text the edit opens with in [plan]: the item's, empty for a new one; null once its item is gone. */
fun PlanEdit.current(plan: Plan): String? {
    val milestone = { id: String -> plan.milestones.firstOrNull { it.id == id } }
    val task = { id: String -> plan.tasks().firstOrNull { it.id == id } }
    return when (this) {
        is PlanEdit.Title -> when (kind(id)) {
            'M' -> milestone(id)?.title
            'T' -> task(id)?.title
            'R' -> plan.requirements.firstOrNull { it.id == id }?.text
            'D' -> plan.decisions.firstOrNull { it.id == id }?.text
            else -> plan.review?.blockers.orEmpty().firstOrNull { it.id == id }?.text
        }
        is PlanEdit.Next -> task(taskId)?.let { it.next.orEmpty() }
        is PlanEdit.Description -> when (id?.let(::kind)) {
            null -> plan.description.orEmpty()
            'M' -> milestone(id)?.let { it.description.orEmpty() }
            'T' -> task(id)?.let { it.description.orEmpty() }
            else -> throw IllegalArgumentException("only a milestone or task has a description: $id")
        }
        is PlanEdit.NewTask -> milestone(milestoneId)?.let { "" }
        PlanEdit.NewMilestone, PlanEdit.NewDecision, PlanEdit.NewRequirement, PlanEdit.NewBlocker -> ""
        PlanEdit.Constraints -> plan.constraints.orEmpty()
    }
}

/** The section card an edit is about, kept open while it is; null for a milestone's or task's. */
val PlanEdit.section: Section?
    get() = when (this) {
        is PlanEdit.Title -> when (kind(id)) {
            'R' -> Section.REQUIREMENTS
            'D' -> Section.DECISIONS
            'B' -> Section.REVIEW
            else -> null
        }
        is PlanEdit.Description -> if (id == null) Section.IDEAL else null
        PlanEdit.Constraints -> Section.CONSTRAINTS
        PlanEdit.NewDecision -> Section.DECISIONS
        PlanEdit.NewRequirement -> Section.REQUIREMENTS
        PlanEdit.NewBlocker -> Section.REVIEW
        is PlanEdit.Next, is PlanEdit.NewTask, PlanEdit.NewMilestone -> null
    }

/** Adds an item: its field stays open and empty after a save, for the next. */
val PlanEdit.isNew get() = this is PlanEdit.NewTask || this == PlanEdit.NewMilestone || this == PlanEdit.NewDecision || this == PlanEdit.NewRequirement || this == PlanEdit.NewBlocker

/** Edited in the area, as its text may hold several lines (Markdown): a description, the constraints, a decision. */
val PlanEdit.multiline get() = this is PlanEdit.Description || this == PlanEdit.Constraints || this is PlanEdit.Title && kind(id) == 'D'

private fun kind(id: String) = id.first().also { require(it in "MTRDB") { "not a milestone, task, requirement, decision or blocker id: $id" } }
