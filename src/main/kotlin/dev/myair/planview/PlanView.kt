package dev.myair.planview

import dev.myair.claude.ClaudeSession
import dev.myair.folders.Blocker
import dev.myair.folders.Decision
import dev.myair.folders.Folder
import dev.myair.folders.Milestone
import dev.myair.folders.Plan
import dev.myair.folders.PlanStatus
import dev.myair.folders.PlanTask
import dev.myair.folders.Requirement
import dev.myair.folders.RequirementStatus
import dev.myair.folders.Review
import dev.myair.folders.ReviewStatus
import dev.myair.folders.SessionFolders
import dev.myair.folders.progress

data class TaskView(val task: PlanTask, val sessions: List<ClaudeSession>)

data class MilestoneView(val milestone: Milestone, val progress: String, val tasks: List<TaskView>)

/** [plan] is the folder's, read by the sections between the header and the milestones. */
data class PlanView(val folder: Folder, val plan: Plan, val milestones: List<MilestoneView>)

/** The cards between the plan's header and its milestones; [id] is the one [Folding.toggle] takes. */
enum class Section(val id: String) { IDEAL("ideal"), CONSTRAINTS("constraints"), DECISIONS("decisions"), REQUIREMENTS("requirements"), REVIEW("review") }

/**
 * Which sections, milestones and tasks the plan view shows open: by default Requirements, Code review while it has an
 * unresolved blocker, the other sections while empty, every milestone not done, and the tasks in progress or waiting for
 * the user; [toggled] holds the ids the user flipped from that.
 */
data class Folding(val toggled: Set<String> = emptySet()) {
    fun open(milestone: Milestone) = byDefault(milestone) != (milestone.id in toggled)

    fun open(task: PlanTask) = byDefault(task) != (task.id in toggled)

    fun open(section: Section, plan: Plan) = byDefault(section, plan) != (section.id in toggled)

    fun toggle(id: String) = Folding(if (id in toggled) toggled - id else toggled + id)

    companion object {
        /** Every section, milestone and task of [plan] [open], or every one folded. */
        fun all(open: Boolean, plan: Plan) = Folding(
            Section.entries.filter { byDefault(it, plan) != open }.map { it.id }.toSet() +
                plan.milestones.filter { byDefault(it) != open }.map { it.id } +
                plan.tasks().filter { byDefault(it) != open }.map { it.id },
        )

        private fun byDefault(section: Section, plan: Plan) = when (section) {
            Section.IDEAL -> plan.description == null
            Section.CONSTRAINTS -> plan.constraints == null
            Section.DECISIONS -> plan.decisions.isEmpty()
            Section.REQUIREMENTS -> true
            Section.REVIEW -> plan.review?.blockers.orEmpty().any { !it.resolved }
        }

        private fun byDefault(milestone: Milestone) = !milestone.done

        private fun byDefault(task: PlanTask) = task.status == PlanStatus.IN_PROGRESS || task.status == PlanStatus.WAITING
    }
}

/** How many of [tasks] have each status, the canceled left out: the segments of a progress bar. */
fun statusCounts(tasks: List<PlanTask>): Map<PlanStatus, Int> = tasks.filter { it.status != PlanStatus.CANCELED }.groupingBy { it.status }.eachCount()

/** The tasks of [counts] someone works on or waits for, in words: "2 in progress · 1 waiting for you"; empty without any. */
fun openWork(counts: Map<PlanStatus, Int>): String =
    listOf(PlanStatus.IN_PROGRESS to "in progress", PlanStatus.WAITING to "waiting for you", PlanStatus.BLOCKED to "blocked")
        .mapNotNull { (status, text) -> counts[status]?.let { "$it $text" } }.joinToString(" · ")

/** "R 4/6": proven requirements over all of them; null without any. */
fun requirementsSummary(requirements: List<Requirement>): String? =
    if (requirements.isEmpty()) null else "R ${requirements.count { it.status == RequirementStatus.PROVEN }}/${requirements.size}"

/** The review in the plan's header: its unresolved blockers whenever any is, else its status. */
fun reviewSummary(review: Review?): String {
    val blockers = review?.blockers.orEmpty().count { !it.resolved }
    return when {
        blockers > 0 -> "review: $blockers blocker${if (blockers == 1) "" else "s"}"
        review == null || review.status == ReviewStatus.NOT_DONE -> "review: not done"
        review.status == ReviewStatus.IN_PROGRESS -> "review: in progress"
        else -> "review: done"
    }
}

/** [decisions] newest first: by date, then by number, so `D10` comes before `D9`. */
fun newestFirst(decisions: List<Decision>): List<Decision> =
    decisions.sortedWith(compareByDescending<Decision> { it.date }.thenByDescending { it.id.drop(1).toInt() })

/** The mark before a requirement: `○` unproven, `✗` not done, `✓` proven. */
val RequirementStatus.glyph
    get() = when (this) {
        RequirementStatus.UNPROVEN -> "○"
        RequirementStatus.NOT_DONE -> "✗"
        RequirementStatus.PROVEN -> "✓"
    }

/** The mark before a blocker: `✓` resolved, else `✗`. */
val Blocker.glyph get() = if (resolved) RequirementStatus.PROVEN.glyph else RequirementStatus.NOT_DONE.glyph

/** The plan of [folderId] with the sessions under each task, [managerId] left out; null when the folder is gone or has no plan. */
fun planViewOf(structure: SessionFolders, folderId: String, sessions: List<ClaudeSession>, managerId: String?): PlanView? {
    val folder = structure.find(folderId) ?: return null
    val plan = folder.plan ?: return null
    val workers = sessions.filter { it.id != managerId }
    return PlanView(folder, plan, plan.milestones.map { m ->
        MilestoneView(m, progress(m.tasks), m.tasks.map { t -> TaskView(t, workers.filter { it.brief?.planTask == t.id }) })
    })
}
