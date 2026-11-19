package dev.myair.review

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.myair.store.BranchReview
import dev.myair.store.Chapter
import dev.myair.store.FindingState
import dev.myair.store.Keys
import dev.myair.store.Question
import dev.myair.store.Run
import dev.myair.store.Severity
import java.nio.file.Path
import java.time.Instant

private fun BranchReview.finding(id: String) = requireNotNull(findings.firstOrNull { it.id == id }) { "no finding '$id'" }

private fun BranchReview.run(id: String) = requireNotNull(runs.firstOrNull { it.id == id }) { "no run '$id'" }

/** Replaces the state of [id] and its companion fields (a [dev.myair.store.Finding] throws when the state lacks its own). */
fun BranchReview.setState(id: String, state: FindingState, reason: String? = null, commit: String? = null, comment: String? = null): BranchReview {
    val f = finding(id)
    return copy(findings = findings.map { if (it === f) f.copy(state = state, reason = reason, fixCommit = commit, comment = comment) else it })
}

fun BranchReview.setSummary(text: String?) = copy(summary = text)

fun BranchReview.setChapters(chapters: List<Chapter>) = copy(chapters = chapters)

fun BranchReview.setPr(pr: Int?) = copy(pr = pr)

fun BranchReview.addQuestion(text: String, reviewer: String?, run: String? = null): BranchReview =
    nextId("Q").let { (id, next) -> next.copy(questions = next.questions + Question(id, text, reviewer, run = run)) }

fun BranchReview.resolveQuestion(id: String): BranchReview {
    val q = requireNotNull(questions.firstOrNull { it.id == id }) { "no question '$id'" }
    return copy(questions = questions.map { if (it === q) q.copy(resolved = true) else it })
}

fun BranchReview.addRun(run: Run): BranchReview {
    require(runs.none { it.id == run.id }) { "run '${run.id}' exists" }
    return copy(runs = runs + run)
}

private fun BranchReview.editRun(id: String, edit: (Run) -> Run): BranchReview = run(id).let { r -> copy(runs = runs.map { if (it === r) edit(r) else it }) }

fun BranchReview.setJob(runId: String, job: String?) = editRun(runId) { it.copy(job = job) }

fun BranchReview.setSession(runId: String, session: String?) = editRun(runId) { it.copy(session = session) }

fun BranchReview.removeRun(runId: String): BranchReview {
    require(run(runId).reported == null) { "run '$runId' has reported" }
    return copy(runs = runs.filter { it.id != runId })
}

/** An agent's change list for a review (a JSON array of `{"op": ...}` objects, all or none) and a reviewer's report. */
object ReviewChanges {
    /** The keys each op requires, besides `op`. */
    private val REQUIRED = mapOf(
        "set_state" to setOf("id", "state"),
        "set_summary" to setOf("text"),
        "set_chapters" to setOf("chapters"),
        "set_pr" to setOf("pr"),
        "add_question" to setOf("text"),
        "resolve_question" to setOf("id"),
    )
    private val OPTIONAL = mapOf("set_state" to setOf("reason", "commit", "comment"), "add_question" to setOf("reviewer"))
    private val SOURCE = Path.of("changes")
    private val FINDING_KEYS = setOf("file", "line", "category", "severity", "summary", "failure_scenario")

    private fun array(text: String, what: String): List<JsonElement> = try {
        JsonParser.parseString(text).also { require(it.isJsonArray) { "$what must be a JSON array, got $it" } }.asJsonArray.toList()
    } catch (e: JsonParseException) {
        throw IllegalArgumentException("$what are not JSON: ${e.message}", e)
    }

    /** [parse] of each element of [list]; a failure names its 1-based position and the element. */
    private fun <T> each(list: List<JsonElement>, what: String, parse: (JsonElement) -> T): List<T> = list.mapIndexed { i, e ->
        try {
            parse(e)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("$what ${i + 1} $e: ${ex.message}", ex)
        } catch (ex: IllegalStateException) {
            throw IllegalArgumentException("$what ${i + 1} $e: ${ex.message}", ex)
        }
    }

    private fun Keys.optionalString(key: String) = if (has(key)) stringOrNull(key) else null

    fun apply(review: BranchReview, changes: String): BranchReview =
        array(changes, "changes").withIndex().fold(review) { r, (i, change) ->
            try {
                applyOne(r, change)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            }
        }

    private fun applyOne(review: BranchReview, change: JsonElement): BranchReview {
        require(change.isJsonObject) { "not an object" }
        val op = change.asJsonObject["op"]?.takeIf { it.isJsonPrimitive }?.asString
        val required = requireNotNull(REQUIRED[op]) { "unknown op '$op', expected one of ${REQUIRED.keys}" }
        val k = Keys(change, "op $op", SOURCE, required + "op", OPTIONAL[op].orEmpty())
        return when (op) {
            "set_state" -> review.setState(k.string("id"), k.enum("state", FindingState.entries.toTypedArray()), k.optionalString("reason"), k.optionalString("commit"), k.optionalString("comment"))
            "set_summary" -> review.setSummary(k.stringOrNull("text"))
            "set_chapters" -> review.setChapters(k.array("chapters").map { Keys(it, "chapter", SOURCE, setOf("title", "text", "files")).run { Chapter(string("title"), string("text"), strings("files")) } })
            "set_pr" -> review.setPr(k.intOrNull("pr"))
            "add_question" -> review.addQuestion(k.string("text"), k.optionalString("reviewer"))
            "resolve_question" -> review.resolveQuestion(k.string("id"))
            else -> error("unreachable op $op")
        }
    }

    /** Merges a reviewer's [findings] and [questions] (JSON arrays; questions are strings) into [review] as the report of [run] at [commit]; nothing is recorded when any of it is invalid. */
    fun report(review: BranchReview, run: String, commit: String, findings: String, questions: String, now: Instant): BranchReview {
        val r = review.run(run)
        val incoming = each(array(findings, "findings"), "finding") {
            Keys(it, "finding", SOURCE, FINDING_KEYS).run {
                IncomingFinding(string("file"), int("line"), string("category"), enum("severity", Severity.entries.toTypedArray()), string("summary"), string("failure_scenario")).also { f ->
                    require(listOf(f.file, f.category, f.summary).none { s -> '\n' in s || '\r' in s }) { "file, category and summary must be one line" }
                }
            }
        }
        val asked = each(array(questions, "questions"), "question") { q ->
            require(q.isJsonPrimitive && q.asJsonPrimitive.isString) { "not a string" }
            q.asString
        }
        val merged = mergeFindings(review, r, commit, incoming)
        val marked = merged.copy(runs = merged.runs.map { if (it.id == run) it.copy(reported = now.toString(), commit = commit) else it }, questions = merged.questions.filter { it.run != run })
        return asked.fold(marked) { acc, q -> acc.addQuestion(q, r.reviewer, run) }
    }
}
