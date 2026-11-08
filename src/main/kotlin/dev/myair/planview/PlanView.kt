package dev.myair.planview

import dev.myair.claude.ClaudeSession
import dev.myair.folders.Folder
import dev.myair.folders.Milestone
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders
import dev.myair.folders.progress

data class TaskView(val task: PlanTask, val sessions: List<ClaudeSession>)

data class MilestoneView(val milestone: Milestone, val progress: String, val tasks: List<TaskView>)

data class PlanView(val folder: Folder, val description: String?, val milestones: List<MilestoneView>)

/** The plan of [folderId] with the sessions under each task, [managerId] left out; null when the folder is gone or has no plan. */
fun planViewOf(structure: SessionFolders, folderId: String, sessions: List<ClaudeSession>, managerId: String?): PlanView? {
    val folder = structure.find(folderId) ?: return null
    val plan = folder.plan ?: return null
    val workers = sessions.filter { it.id != managerId }
    return PlanView(folder, plan.description, plan.milestones.map { m ->
        MilestoneView(m, progress(m.tasks), m.tasks.map { t -> TaskView(t, workers.filter { it.brief?.planTask == t.id }) })
    })
}
