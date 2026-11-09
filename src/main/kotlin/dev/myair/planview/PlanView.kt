package dev.myair.planview

import dev.myair.claude.ClaudeSession
import dev.myair.folders.Folder
import dev.myair.folders.Milestone
import dev.myair.folders.PlanStatus
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders
import dev.myair.folders.progress

data class TaskView(val task: PlanTask, val sessions: List<ClaudeSession>)

data class MilestoneView(val milestone: Milestone, val progress: String, val tasks: List<TaskView>)

data class PlanView(val folder: Folder, val description: String?, val milestones: List<MilestoneView>)

/**
 * Which milestones and tasks the plan view shows open: by default every milestone not done, and the tasks in progress or
 * waiting for the user; [toggled] holds the ids the user flipped from that.
 */
data class Folding(val toggled: Set<String> = emptySet()) {
    fun open(milestone: Milestone) = byDefault(milestone) != (milestone.id in toggled)

    fun open(task: PlanTask) = byDefault(task) != (task.id in toggled)

    fun toggle(id: String) = Folding(if (id in toggled) toggled - id else toggled + id)

    companion object {
        /** Every milestone of [milestones] and every task in them [open], or every one folded. */
        fun all(open: Boolean, milestones: List<Milestone>) = Folding(
            milestones.filter { byDefault(it) != open }.map { it.id }.toSet() +
                milestones.flatMap { it.tasks }.filter { byDefault(it) != open }.map { it.id },
        )

        private fun byDefault(milestone: Milestone) = !milestone.done

        private fun byDefault(task: PlanTask) = task.status == PlanStatus.IN_PROGRESS || task.status == PlanStatus.WAITING
    }
}

/** The plan of [folderId] with the sessions under each task, [managerId] left out; null when the folder is gone or has no plan. */
fun planViewOf(structure: SessionFolders, folderId: String, sessions: List<ClaudeSession>, managerId: String?): PlanView? {
    val folder = structure.find(folderId) ?: return null
    val plan = folder.plan ?: return null
    val workers = sessions.filter { it.id != managerId }
    return PlanView(folder, plan.description, plan.milestones.map { m ->
        MilestoneView(m, progress(m.tasks), m.tasks.map { t -> TaskView(t, workers.filter { it.brief?.planTask == t.id }) })
    })
}
