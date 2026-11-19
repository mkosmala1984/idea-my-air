package dev.myair.review

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.myair.store.BranchReview
import dev.myair.store.toJson

private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

/**
 * What `my_air_review_get` returns: the review as `review.json` holds it, without `format`, `next` and `blockers`, plus
 * [path], each finding's `line`, `severity` and derived `maybe_fixed`, each run's derived `status`, and the git facts.
 */
fun reviewJson(path: String, review: BranchReview, git: ReviewGit?, liveJobs: Set<String>): String {
    val json = JsonParser.parseString(review.toJson()).asJsonObject
    listOf("format", "next", "blockers").forEach { json.remove(it) }
    json.addProperty("folder", path)
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
    json.addProperty("git_error", git?.error)
    return GSON.toJson(json)
}
