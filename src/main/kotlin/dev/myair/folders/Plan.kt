package dev.myair.folders

import com.google.gson.annotations.SerializedName

/** Where a plan task stands; `structure.json` and the MCP tools spell it in lowercase. */
enum class PlanStatus {
    @SerializedName("pending") PENDING,
    @SerializedName("in_progress") IN_PROGRESS,
    /** For the user to act. */
    @SerializedName("waiting") WAITING,
    /** For someone else. */
    @SerializedName("blocked") BLOCKED,
    @SerializedName("done") DONE,
    @SerializedName("canceled") CANCELED;

    val label = name.lowercase()

    /** Nothing more is done on the task. */
    val closed get() = this == DONE || this == CANCELED

    companion object {
        fun of(label: String) = requireNotNull(entries.firstOrNull { it.label == label }) { "unknown status '$label', expected one of ${entries.map { it.label }}" }
    }
}

/** A task of a plan; its id `T<n>` is unique across `structure.json` and never reused, so a brief names it alone. */
data class PlanTask(val id: String, val title: String, val status: PlanStatus = PlanStatus.PENDING, val next: String? = null)

/** A milestone of a plan, `M<n>` like a task's id; [done] is set by hand, not derived from its tasks. */
data class Milestone(val id: String, val title: String, val done: Boolean = false, val tasks: List<PlanTask> = emptyList())

/** What makes a folder a plan folder: the project's goal and its milestones in order. */
data class Plan(val goal: String? = null, val milestones: List<Milestone> = emptyList()) {
    fun tasks() = milestones.flatMap { it.tasks }
}

/** "3/7": done tasks over the tasks not canceled. */
fun progress(tasks: List<PlanTask>) = "${tasks.count { it.status == PlanStatus.DONE }}/${tasks.count { it.status != PlanStatus.CANCELED }}"
