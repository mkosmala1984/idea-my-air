package dev.myair.review

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.myair.folders.Plan
import dev.myair.folders.ReviewStatus
import dev.myair.store.BranchReview
import dev.myair.store.FindingState
import dev.myair.store.Severity

/** [changes]: a `PlanChanges` list; [blockers]: finding id to plan blocker id after applying it, new ids predicted in order. */
data class Mirror(val changes: String, val blockers: Map<String, String>)

private fun op(name: String, vararg fields: Pair<String, Any?>) = JsonObject().apply {
    addProperty("op", name)
    fields.forEach { (k, v) ->
        when (v) {
            null -> add(k, JsonNull.INSTANCE)
            is String -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            else -> error("unsupported value for $k: $v")
        }
    }
}

/** The changes that make [plan]'s review and blockers follow [review], null when the plan already does. */
fun mirrorToPlan(review: BranchReview, plan: Plan, liveJobs: Set<String>): Mirror? {
    val runs = review.runs.map { runStatus(it, liveJobs) }
    val status = when {
        RunStatus.running in runs -> ReviewStatus.IN_PROGRESS
        RunStatus.reported in runs -> ReviewStatus.DONE
        else -> ReviewStatus.NOT_DONE
    }
    val commit = reviewedCommit(review)
    val ops = JsonArray()
    if (plan.review.let { it?.status != status || it.commit != commit }) ops.add(op("set_review", "status" to status.label, "commit" to commit))
    val existing = plan.review?.blockers.orEmpty().associateBy { it.id }
    var next = plan.next["B"] ?: 1
    val mapping = review.blockers.toMutableMap()
    for (f in review.findings.filter { it.severity == Severity.blocker }) {
        val text = "${f.id} ${f.file}:${f.line} ${f.reports.last().summary}"
        val resolved = f.state !in setOf(FindingState.open, FindingState.accepted)
        val id = mapping[f.id]?.takeIf { it in existing }
        if (id == null) {
            mapping[f.id] = "B${next++}"
            ops.add(op("add_blocker", "text" to text))
            if (resolved) ops.add(op("update", "id" to mapping.getValue(f.id), "resolved" to true))
        } else if (existing.getValue(id).text != text || existing.getValue(id).resolved != resolved) {
            ops.add(op("update", "id" to id, "text" to text, "resolved" to resolved))
        }
    }
    return if (ops.isEmpty) null else Mirror(ops.toString(), mapping)
}
