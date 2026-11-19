package dev.myair.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** Ordinal order is severity, highest first. */
enum class Severity { blocker, major, minor, nit }

enum class FindingState { open, accepted, rejected, fixed, posted }

/** What one reviewer run said about a finding, at [commit]. */
data class Report(
    val run: String,
    val reviewer: String,
    val commit: String,
    val line: Int,
    val severity: Severity,
    val summary: String,
    val failureScenario: String,
)

/** A problem in [file], reported by one or more runs (oldest report first); the state needs its companion field. */
data class Finding(
    val id: String,
    val file: String,
    val category: String,
    val reports: List<Report>,
    val state: FindingState = FindingState.open,
    val reason: String? = null,
    val fixCommit: String? = null,
    val comment: String? = null,
) {
    init {
        require(reports.isNotEmpty()) { "finding $id has no reports" }
        require(state != FindingState.rejected || reason != null) { "rejected finding $id has no reason" }
        require(state != FindingState.fixed || fixCommit != null) { "fixed finding $id has no fixCommit" }
        require(state != FindingState.posted || comment != null) { "posted finding $id has no comment" }
    }

    val line get() = reports.last().line
    val severity get() = reports.minOf { it.severity }
}

/** One reviewer job over [range]; [session] and [job] are learned after the start. Dates are ISO strings. */
data class Run(
    val id: String,
    val reviewer: String,
    val session: String?,
    val job: String?,
    val range: String,
    val started: String,
    val reported: String? = null,
    val commit: String? = null,
)

data class Chapter(val title: String, val text: String, val files: List<String>)

data class Question(val id: String, val text: String, val reviewer: String?, val resolved: Boolean = false, val run: String? = null)

/** The review of [branch] against [base] in [dir]; [next] counts ids per prefix and is never lowered. */
data class BranchReview(
    val branch: String,
    val base: String,
    val dir: Path,
    val pr: Int?,
    val plan: String?,
    val manager: PlanManager?,
    val summary: String?,
    val chapters: List<Chapter>,
    val runs: List<Run>,
    val findings: List<Finding>,
    val questions: List<Question>,
    val next: Map<String, Int>,
    val blockers: Map<String, String> = emptyMap(),
) {
    fun nextId(prefix: String): Pair<String, BranchReview> {
        val n = next[prefix] ?: 1
        return "$prefix$n" to copy(next = next + (prefix to n + 1))
    }
}

private const val FORMAT = 1

private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

/** A JSON object of [entries]; a null value is written as JSON null. */
private fun obj(entries: Map<String, Any?>) = JsonObject().also { o ->
    entries.forEach { (k, v) ->
        when (v) {
            null -> o.add(k, JsonNull.INSTANCE)
            is String -> o.addProperty(k, v)
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            is JsonElement -> o.add(k, v)
            else -> error("unsupported value for $k: $v")
        }
    }
}

private fun obj(vararg entries: Pair<String, Any?>) = obj(entries.toMap())

private fun <T> List<T>.array(f: (T) -> JsonElement) = JsonArray().also { a -> forEach { a.add(f(it)) } }

fun BranchReview.toJson(): String = GSON.toJson(
    obj(
        "format" to FORMAT,
        "branch" to branch,
        "base" to base,
        "dir" to dir.toString(),
        "pr" to pr,
        "plan" to plan,
        "manager" to manager?.let { obj("session" to it.session, "cwd" to it.cwd.toString(), "project" to it.project.toString()) },
        "summary" to summary,
        "chapters" to chapters.array { c -> obj("title" to c.title, "text" to c.text, "files" to c.files.array(::JsonPrimitive)) },
        "runs" to runs.array { r ->
            obj("id" to r.id, "reviewer" to r.reviewer, "session" to r.session, "job" to r.job, "range" to r.range, "started" to r.started, "reported" to r.reported, "commit" to r.commit)
        },
        "findings" to findings.array { f ->
            obj(
                "id" to f.id, "file" to f.file, "category" to f.category,
                "reports" to f.reports.array { r ->
                    obj(
                        "run" to r.run, "reviewer" to r.reviewer, "commit" to r.commit, "line" to r.line, "severity" to r.severity.name,
                        "summary" to r.summary, "failure_scenario" to r.failureScenario,
                    )
                },
                "state" to f.state.name, "reason" to f.reason, "fixCommit" to f.fixCommit, "comment" to f.comment,
            )
        },
        "questions" to questions.array { q -> obj("id" to q.id, "text" to q.text, "reviewer" to q.reviewer, "resolved" to q.resolved, "run" to q.run) },
        "next" to obj(next),
    ).also { if (blockers.isNotEmpty()) it.add("blockers", obj(blockers)) },
)

/** A JSON object of [source] read with an explicit key set: unknown and missing required keys throw naming file and key. */
internal class Keys(element: JsonElement, private val what: String, private val source: Path, required: Set<String>, optional: Set<String> = emptySet()) {
    private val json: JsonObject

    init {
        check(element.isJsonObject) { "$source: $what is not an object: $element" }
        json = element.asJsonObject
        val unknown = json.keySet() - required - optional
        check(unknown.isEmpty()) { "$source: unexpected keys in $what: $unknown" }
        val missing = required - json.keySet()
        check(missing.isEmpty()) { "$source: $what lacks keys $missing" }
    }

    fun has(key: String) = json.has(key)

    fun element(key: String): JsonElement = json[key] ?: error("$source: $what lacks key $key")

    private fun primitive(key: String, kind: String, ok: (JsonPrimitive) -> Boolean): JsonPrimitive = element(key).let {
        check(it.isJsonPrimitive && ok(it.asJsonPrimitive)) { "$source: $key of $what is not $kind: $it" }
        it.asJsonPrimitive
    }

    fun string(key: String): String = primitive(key, "a string") { it.isString }.asString

    fun stringOrNull(key: String): String? = if (element(key).isJsonNull) null else string(key)

    fun int(key: String): Int = primitive(key, "a number") { it.isNumber }.asInt

    fun intOrNull(key: String): Int? = if (element(key).isJsonNull) null else int(key)

    fun boolean(key: String): Boolean = primitive(key, "a boolean") { it.isBoolean }.asBoolean

    fun array(key: String): List<JsonElement> = element(key).let {
        check(it.isJsonArray) { "$source: $key of $what is not an array: $it" }
        it.asJsonArray.toList()
    }

    fun strings(key: String): List<String> = array(key).map {
        check(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$source: $key of $what holds a non-string: $it" }
        it.asString
    }

    fun <E : Enum<E>> enum(key: String, values: Array<E>): E = string(key).let { s ->
        values.firstOrNull { it.name == s } ?: error("$source: $key of $what is $s, not one of ${values.map { it.name }}")
    }

    fun map(key: String): Map<String, JsonElement> = element(key).let {
        check(it.isJsonObject) { "$source: $key of $what is not an object: $it" }
        it.asJsonObject.asMap()
    }
}

fun parseReview(text: String, source: Path): BranchReview {
    val root = Keys(
        JsonParser.parseString(text), "review", source,
        required = setOf("format", "branch", "base", "dir", "pr", "plan", "manager", "summary", "chapters", "runs", "findings", "questions", "next"),
        optional = setOf("blockers"),
    )
    check(root.int("format") == FORMAT) { "$source: format is ${root.element("format")}, not $FORMAT" }
    return BranchReview(
        branch = root.string("branch"),
        base = root.string("base"),
        dir = Path.of(root.string("dir")),
        pr = root.intOrNull("pr"),
        plan = root.stringOrNull("plan"),
        manager = if (!root.element("manager").isJsonNull) Keys(root.element("manager"), "manager", source, setOf("session", "cwd", "project")).run {
            PlanManager(string("session"), Path.of(string("cwd")), Path.of(string("project")))
        } else null,
        summary = root.stringOrNull("summary"),
        chapters = root.array("chapters").map {
            Keys(it, "chapter", source, setOf("title", "text", "files")).run { Chapter(string("title"), string("text"), strings("files")) }
        },
        runs = root.array("runs").map {
            Keys(it, "run", source, setOf("id", "reviewer", "session", "job", "range", "started", "reported", "commit")).run {
                Run(string("id"), string("reviewer"), stringOrNull("session"), stringOrNull("job"), string("range"), string("started"), stringOrNull("reported"), stringOrNull("commit"))
            }
        },
        findings = root.array("findings").map {
            Keys(it, "finding", source, setOf("id", "file", "category", "reports", "state", "reason", "fixCommit", "comment")).run {
                val id = string("id")
                val reports = array("reports").map { r ->
                    Keys(r, "report of finding $id", source, setOf("run", "reviewer", "commit", "line", "severity", "summary", "failure_scenario")).run {
                        Report(string("run"), string("reviewer"), string("commit"), int("line"), enum("severity", Severity.entries.toTypedArray()), string("summary"), string("failure_scenario"))
                    }
                }
                try {
                    Finding(id, string("file"), string("category"), reports, enum("state", FindingState.entries.toTypedArray()), stringOrNull("reason"), stringOrNull("fixCommit"), stringOrNull("comment"))
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("$source: ${e.message}", e)
                }
            }
        },
        questions = root.array("questions").map {
            Keys(it, "question", source, setOf("id", "text", "reviewer", "resolved", "run")).run {
                Question(string("id"), string("text"), stringOrNull("reviewer"), boolean("resolved"), stringOrNull("run"))
            }
        },
        next = root.map("next").mapValues { (k, v) ->
            check(v.isJsonPrimitive && v.asJsonPrimitive.isNumber) { "$source: next.$k is not a number: $v" }
            v.asInt
        },
        blockers = if (root.has("blockers")) root.map("blockers").mapValues { (k, v) ->
            check(v.isJsonPrimitive && v.asJsonPrimitive.isString) { "$source: blockers.$k is not a string: $v" }
            v.asString
        } else emptyMap(),
    )
}

/** `<root>/<folderId>/review.json` of every folder with a review; [readAll] keeps a broken one as a failure. */
class ReviewFiles(val root: Path) {
    private fun file(folderId: String) = root.resolve(folderId).resolve("review.json")

    fun readAll(): Map<String, Result<BranchReview>> {
        if (!root.exists()) return emptyMap()
        return root.listDirectoryEntries().filter { it.isDirectory() && file(it.name).exists() }.associate {
            val f = file(it.name)
            it.name to runCatching { parseReview(f.readText(), f) }
        }
    }

    fun exists(folderId: String) = file(folderId).exists()

    fun read(folderId: String): BranchReview = file(folderId).let { parseReview(it.readText(), it) }

    fun write(folderId: String, review: BranchReview) {
        file(folderId).parent.createDirectories()
        writeAtomically(file(folderId), review.toJson())
    }
}
