package dev.myair.planview

import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders

/** A text being edited in place in the plan view; milestone and task ids tell themselves apart by their `M`/`T` prefix. */
sealed interface PlanEdit {
    /** The title of milestone or task [id]. */
    data class Title(val id: String) : PlanEdit

    data class Next(val taskId: String) : PlanEdit

    /** The Markdown description of milestone or task [id]; null for the plan's own. */
    data class Description(val id: String?) : PlanEdit

    data class NewTask(val milestoneId: String) : PlanEdit

    data object NewMilestone : PlanEdit
}

/**
 * The change saving [text] makes to the plan of [folderId], applied by id to the structure as it is then, so an item
 * deleted meanwhile is left as it is; null for a blank title, which is refused. An empty next step or description clears it.
 */
fun PlanEdit.apply(folderId: String, text: String): ((SessionFolders) -> SessionFolders)? {
    val trimmed = text.trim()
    val value = trimmed.ifEmpty { null }
    return when (this) {
        is PlanEdit.Title -> if (value == null) null else when (kind(id)) {
            'M' -> { s -> s.editMilestone(id) { it.copy(title = value) } }
            else -> { s -> s.editTask(id) { it.copy(title = value) } }
        }
        is PlanEdit.Next -> { s -> s.editTask(taskId) { it.copy(next = value) } }
        is PlanEdit.Description -> when (id?.let(::kind)) {
            null -> { s -> s.setDescription(folderId, value) }
            'M' -> { s -> s.editMilestone(id) { it.copy(description = value) } }
            else -> { s -> s.editTask(id) { it.copy(description = value) } }
        }
        is PlanEdit.NewTask -> if (value == null) null else { s -> s.addTask(milestoneId, value) }
        PlanEdit.NewMilestone -> if (value == null) null else { s -> s.addMilestone(folderId, value) }
    }
}

/** The text the edit opens with in [plan]: the item's, empty for a new one; null once its item is gone. */
fun PlanEdit.current(plan: Plan): String? {
    val milestone = { id: String -> plan.milestones.firstOrNull { it.id == id } }
    val task = { id: String -> plan.tasks().firstOrNull { it.id == id } }
    return when (this) {
        is PlanEdit.Title -> if (kind(id) == 'M') milestone(id)?.title else task(id)?.title
        is PlanEdit.Next -> task(taskId)?.let { it.next.orEmpty() }
        is PlanEdit.Description -> when (id?.let(::kind)) {
            null -> plan.description.orEmpty()
            'M' -> milestone(id)?.let { it.description.orEmpty() }
            else -> task(id)?.let { it.description.orEmpty() }
        }
        is PlanEdit.NewTask -> milestone(milestoneId)?.let { "" }
        PlanEdit.NewMilestone -> ""
    }
}

private fun kind(id: String) = id.first().also { require(it == 'M' || it == 'T') { "not a milestone or task id: $id" } }
