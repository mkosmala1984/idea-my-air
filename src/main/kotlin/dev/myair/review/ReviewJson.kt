package dev.myair.review

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.myair.claude.SessionStatus
import dev.myair.folders.SessionFolders
import dev.myair.store.BranchReview
import dev.myair.store.toJson

private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

/**
 * What `my_air_review_get` returns: the review of folder [folderId] as `review.json` holds it, without `format`, `next`
 * and `blockers`, plus the folder's path in [structure], the linked plan as its folder's path (null without one or once
 * that folder is gone or holds no plan, as for [linkedPlan]), each finding's `line`, `severity` and derived `maybe_fixed`,
 * each run's derived `status`, and the git facts: `merge_base`, `range` (what a Whole branch run reviews) with its
 * `commits` and `files`, and `stored_base` (`branch.<branch>.myAirBase`).
 */
fun reviewJson(structure: SessionFolders, folderId: String, review: BranchReview, git: ReviewGit?, liveJobs: Map<String, SessionStatus>): String {
    val json = JsonParser.parseString(review.toJson()).asJsonObject
    listOf("format", "next", "blockers").forEach { json.remove(it) }
    json.addProperty("folder", structure.pathOf(folderId))
    json.addProperty("plan", review.plan?.takeIf { linkedPlan(review, structure) != null }?.let(structure::pathOf))
    json["findings"].asJsonArray.zip(review.findings).forEach { (element, finding) ->
        element.asJsonObject.apply {
            addProperty("line", finding.line)
            addProperty("severity", finding.severity.name)
            addProperty("maybe_fixed", maybeFixed(finding, git?.changed?.get(finding.reports.last().commit)))
        }
    }
    json["runs"].asJsonArray.zip(review.runs).forEach { (element, run) -> element.asJsonObject.addProperty("status", runStatus(run, liveJobs).name) }
    json.addProperty("reviewed", reviewedCommit(review))
    json.addProperty("head", git?.head)
    json.addProperty("since_reviewed", git?.sinceReviewed)
    json.addProperty("merge_base", git?.state?.mergeBase)
    json.addProperty("range", git?.range)
    json.addProperty("commits", git?.size?.commits)
    json.addProperty("files", git?.size?.files)
    json.addProperty("stored_base", git?.storedBase)
    json.addProperty("git_error", git?.error)
    return GSON.toJson(json)
}
