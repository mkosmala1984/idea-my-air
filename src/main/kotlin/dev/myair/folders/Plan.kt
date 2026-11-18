package dev.myair.folders

import com.google.gson.annotations.SerializedName
import java.time.LocalDate

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

/**
 * A task of a plan; its id `T<n>` is unique across `structure.json` and never reused, so a brief names it alone. [title] is
 * its name; [description], like a milestone's and a plan's, is Markdown.
 */
data class PlanTask(val id: String, val title: String, val status: PlanStatus = PlanStatus.PENDING, val next: String? = null, val description: String? = null)

/** A milestone of a plan, `M<n>` like a task's id; [done] is set by hand, not derived from its tasks. */
data class Milestone(val id: String, val title: String, val done: Boolean = false, val tasks: List<PlanTask> = emptyList(), val description: String? = null)

/** How a requirement is proven. */
enum class EvalKind {
    @SerializedName("assert") ASSERT,
    @SerializedName("code") CODE,
    @SerializedName("questions") QUESTIONS,
    @SerializedName("judge") JUDGE;

    val label = name.lowercase()

    companion object {
        fun of(label: String) = requireNotNull(entries.firstOrNull { it.label == label }) { "unknown eval kind '$label', expected one of ${entries.map { it.label }}" }
    }
}

enum class ReviewStatus {
    @SerializedName("not_done") NOT_DONE,
    @SerializedName("in_progress") IN_PROGRESS,
    @SerializedName("done") DONE;

    val label = name.lowercase()

    companion object {
        fun of(label: String) = requireNotNull(entries.firstOrNull { it.label == label }) { "unknown review status '$label', expected one of ${entries.map { it.label }}" }
    }
}

enum class RequirementStatus { UNPROVEN, NOT_DONE, PROVEN }

private val EVIDENCE = Regex("""(test \S+ › .+|commit [0-9a-f]{7,40}|checked: .+|not done: .+)""")

/** Throws unless [item] is exactly one evidence format, with no surrounding whitespace. */
fun requireEvidence(item: String) = require(EVIDENCE.matches(item) && item == item.trim()) {
    "bad evidence '$item', expected one of 'test <Class> › <name>', 'commit <7-40 lowercase hex>', 'checked: <text>', 'not done: <text>'"
}

/** A checkable requirement `R<n>` of a plan, the [tasks] of that plan delivering it, and the [evidence] proving it. */
data class Requirement(val id: String, val text: String, val eval: EvalKind, val tasks: List<String> = emptyList(), val evidence: List<String> = emptyList()) {
    /** Unproven without evidence, not done with any `not done:` item, else proven. */
    val status get() = when {
        evidence.isEmpty() -> RequirementStatus.UNPROVEN
        evidence.any { it.startsWith("not done: ") } -> RequirementStatus.NOT_DONE
        else -> RequirementStatus.PROVEN
    }
}

/** A decision `D<n>` taken on [date] (ISO local date), in [session] when one is named. */
data class Decision(val id: String, val date: String, val text: String, val session: String? = null)

data class Blocker(val id: String, val text: String, val resolved: Boolean = false)

/** The plan's code review: its status at [commit] as of [date] (ISO local date), and what it found blocking. */
data class Review(val status: ReviewStatus, val commit: String?, val date: String, val blockers: List<Blocker> = emptyList())

/**
 * What makes a folder a plan folder, the folder's name being the plan's: its description and milestones in order, the
 * [constraints] work in it follows, its [decisions], [requirements] and [review]. [next] numbers the next requirement,
 * decision and blocker per prefix (`R`, `D`, `B`), so their ids are never reused within the plan.
 */
data class Plan(
    val description: String? = null, val milestones: List<Milestone> = emptyList(),
    val constraints: String? = null, val decisions: List<Decision> = emptyList(),
    val requirements: List<Requirement> = emptyList(), val review: Review? = null,
    val next: Map<String, Int> = emptyMap(),
) {
    init {
        require(next.keys.all { it in ENTRY_PREFIXES } && next.values.all { it > 1 }) { "bad next $next" }
        val tasks = tasks().map { it.id }.toSet()
        val ids = requirements.map { it.id } + decisions.map { it.id } + review?.blockers.orEmpty().map { it.id }
        require(ids.toSet().size == ids.size) { "duplicate plan entry ids $ids" }
        ids.forEach { id -> require(id.drop(1).toIntOrNull()?.let { it in 1 until (next[id.take(1)] ?: 1) } == true) { "bad entry id '$id' with next $next" } }
        requirements.forEach { r ->
            require(r.id.startsWith('R') && r.text.isNotBlank()) { "bad requirement $r" }
            require(r.tasks.all { it in tasks }) { "requirement ${r.id} names tasks ${r.tasks - tasks} not of its plan" }
            r.evidence.forEach(::requireEvidence)
        }
        decisions.forEach { require(it.id.startsWith('D') && it.text.isNotBlank() && it.session?.isBlank() != true) { "bad decision $it" }; LocalDate.parse(it.date) }
        review?.let { r ->
            LocalDate.parse(r.date)
            require(r.commit?.isBlank() != true) { "blank review commit" }
            r.blockers.forEach { require(it.id.startsWith('B') && it.text.isNotBlank()) { "bad blocker $it" } }
        }
    }

    fun tasks() = milestones.flatMap { it.tasks }

    /** Whether the plan holds anything `structure.json` version 5 brought, so the file must be written as version 5. */
    val usesVersion5 get() = constraints != null || decisions.isNotEmpty() || requirements.isNotEmpty() || review != null || next.isNotEmpty()

    companion object {
        val ENTRY_PREFIXES = setOf("R", "D", "B")
    }
}

/** "3/7": done tasks over the tasks not canceled. */
fun progress(tasks: List<PlanTask>) = "${tasks.count { it.status == PlanStatus.DONE }}/${tasks.count { it.status != PlanStatus.CANCELED }}"
