package dev.myair.schedule

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.store.Homes
import dev.myair.store.Preset
import dev.myair.store.writeAtomically
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

/** When a task runs: at [time] (local, `HH:mm`) on each of [days]. */
data class Schedule(val days: Set<DayOfWeek>, val time: LocalTime) {
    init {
        require(days.isNotEmpty()) { "a schedule without days" }
    }
}

/**
 * A session started at its [schedule]'s slots: [preset] gives its name, model, effort, directory, added directories and
 * first message. [allowedTools] go to `--allowedTools`; [folder] files its runs. Slots before [since] never run.
 */
data class ScheduledTask(
    val id: String,
    val preset: Preset,
    val schedule: Schedule,
    val enabled: Boolean,
    val allowedTools: List<String>,
    val folder: String?,
    val since: Instant,
) {
    init {
        require(id.isNotBlank()) { "task ${preset.name} has a blank id" }
        require(preset.prompt != null) { "task ${preset.name} has no prompt" }
        require(!preset.worktree && !preset.pinned && !preset.askMore) { "task ${preset.name} has a worktree, a button or askMore" }
        require(allowedTools.none { it.isBlank() } && folder?.isBlank() != true) { "task ${preset.name} has a blank value" }
    }

    val name get() = preset.name

    /** The arguments of a run as session [sessionId]: the prompt last, after `--`. */
    fun claudeArgs(sessionId: String): List<String> = listOf("--session-id", sessionId) +
        (if (allowedTools.isEmpty()) emptyList() else listOf("--allowedTools", allowedTools.joinToString(","))) +
        preset.claudeArgs(preset.prompt)
}

/** [new] as saved over [old] at [now]: a changed schedule or a task enabled again runs no slot from before the edit. */
fun edited(old: ScheduledTask, new: ScheduledTask, now: Instant): ScheduledTask =
    new.copy(since = if (new.schedule != old.schedule || new.enabled && !old.enabled) now else old.since)

private const val VERSION = 1
private val TASK_KEYS = setOf("id", "name", "model", "effort", "project", "addDirs", "prompt", "days", "time", "enabled", "allowedTools", "folder", "since")

fun scheduledTasksJson(tasks: List<ScheduledTask>): String {
    checkIds(tasks)
    val array = JsonArray()
    tasks.forEach { task ->
        val p = task.preset
        array.add(JsonObject().apply {
            addProperty("id", task.id)
            addProperty("name", p.name)
            p.model?.let { addProperty("model", it) }
            p.effort?.let { addProperty("effort", it) }
            p.project?.let { addProperty("project", it) }
            if (p.addDirs.isNotEmpty()) add("addDirs", strings(p.addDirs))
            addProperty("prompt", p.prompt)
            add("days", strings(task.schedule.days.sorted().map { it.name }))
            addProperty("time", task.schedule.time.toString())
            addProperty("enabled", task.enabled)
            if (task.allowedTools.isNotEmpty()) add("allowedTools", strings(task.allowedTools))
            task.folder?.let { addProperty("folder", it) }
            addProperty("since", task.since.toString())
        })
    }
    return GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().apply {
        addProperty("version", VERSION)
        add("tasks", array)
    })
}

fun parseScheduledTasks(text: String): List<ScheduledTask> {
    val root = JsonParser.parseString(text).asJsonObject
    check(root.keySet() == setOf("version", "tasks")) { "unexpected keys in scheduled tasks: ${root.keySet()}" }
    check(root["version"].asInt == VERSION) { "scheduled tasks version ${root["version"]}, not $VERSION" }
    return root["tasks"].asJsonArray.map { element ->
        val o = element.asJsonObject
        check(TASK_KEYS.containsAll(o.keySet())) { "unexpected keys in a scheduled task: ${o.keySet() - TASK_KEYS}" }
        ScheduledTask(
            o.required("id"),
            Preset(o.required("name"), o.optional("model"), o.optional("effort"), o.optional("project"), addDirs = o.list("addDirs"), prompt = o.required("prompt")),
            Schedule(o.list("days").map(DayOfWeek::valueOf).toSet(), LocalTime.parse(o.required("time"))),
            o["enabled"].also { check(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "enabled is not a boolean: $it" } }.asBoolean,
            o.list("allowedTools"),
            o.optional("folder"),
            Instant.parse(o.required("since")),
        )
    }.also(::checkIds)
}

private fun checkIds(tasks: List<ScheduledTask>) {
    val ids = tasks.map { it.id }
    check(ids.toSet().size == ids.size) { "scheduled task ids repeat: $ids" }
}

private fun strings(values: List<String>) = JsonArray().apply { values.forEach(::add) }

private fun JsonObject.optional(key: String): String? = get(key)?.string()

private fun JsonObject.required(key: String): String = checkNotNull(optional(key)) { "a scheduled task has no $key" }

private fun JsonObject.list(key: String): List<String> = get(key)?.asJsonArray?.map { it.string() }.orEmpty()

private fun JsonElement.string(): String {
    check(isJsonPrimitive && asJsonPrimitive.isString) { "value in a scheduled task is not a string: $this" }
    return asString
}

/** `~/.my-air/scheduled-tasks.json`, shared by every IDE; parsed again only when the file's time or size changes. */
class ScheduledTaskStore(val file: Path) {
    private var cached: Pair<Pair<FileTime, Long>, Result<List<ScheduledTask>>>? = null

    /** A failure carries what is wrong with the file. */
    @Synchronized
    fun read(): Result<List<ScheduledTask>> {
        if (!file.exists()) return Result.success(emptyList())
        val stamp = file.getLastModifiedTime() to file.fileSize()
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return runCatching { parseScheduledTasks(file.readText()) }.also { cached = stamp to it }
    }

    fun write(tasks: List<ScheduledTask>) {
        file.parent.createDirectories()
        writeAtomically(file, scheduledTasksJson(tasks))
    }

    companion object {
        val INSTANCE = ScheduledTaskStore(Homes.myAir.resolve("scheduled-tasks.json"))
    }
}
