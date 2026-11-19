package dev.myair.schedule

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.claude.SessionStatus
import dev.myair.store.Homes
import dev.myair.store.writeAtomically
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

/** [RAN]: the run wrote its first message, so it got going; what it did is its session's to show. */
enum class RunState { STARTING, STARTED, RAN, FAILED, SKIPPED }

/** How long a run may go without its first message before it counts as failed. */
val START_DEADLINE: Duration = 5.minutes
private val KEPT: Duration = 90.days

/**
 * One run of task [taskId] at [slot] (a [manual] one: when Run now was pressed), by the IDE of pid [ide] started at [ideStart], as session
 * [session]. [error] says why it [RunState.FAILED] or was [RunState.SKIPPED]; [notified]: the user was told of the failure.
 */
data class RunRecord(
    val taskId: String,
    val slot: Instant,
    val manual: Boolean,
    val ide: Long,
    val ideStart: Instant,
    val session: String,
    val state: RunState,
    val error: String?,
    val notified: Boolean,
    val startedAt: Instant,
) {
    init {
        require((error != null) == (state == RunState.FAILED || state == RunState.SKIPPED)) { "run of $taskId at $slot is $state with error $error" }
    }
}

/**
 * `~/.my-air/scheduled-runs/<task id>/<slot>.json`, shared by every IDE. A record is created once ([claim]), so the IDE
 * whose create wins runs the slot; a slot is named by its minute in [zone], a manual run by its second.
 */
class RunRecords(val dir: Path, private val zone: ZoneId) {
    private fun fileOf(taskId: String, slot: Instant, manual: Boolean): Path {
        val name = (if (manual) MANUAL else SLOT).format(slot.atZone(zone)) + (if (manual) "-manual" else "")
        return dir.resolve(taskId).resolve("$name.json")
    }

    private fun fileOf(record: RunRecord) = fileOf(record.taskId, record.slot, record.manual)

    /** Creates [record]'s file; false when it exists, so another IDE (or an earlier tick) has the slot. */
    fun claim(record: RunRecord): Boolean {
        val target = fileOf(record)
        target.parent.createDirectories()
        // Written aside and renamed without replacing, so the create is atomic and no reader sees half a file.
        val temp = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
        temp.writeText(recordJson(record))
        return try {
            Files.move(temp, target)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } finally {
            temp.deleteIfExists()
        }
    }

    fun exists(taskId: String, slot: Instant): Boolean = fileOf(taskId, slot, manual = false).exists()

    fun update(record: RunRecord) {
        val file = fileOf(record)
        check(file.exists()) { "no run record $file to update" }
        writeAtomically(file, recordJson(record))
    }

    /** [taskId]'s runs, the latest started first. */
    fun of(taskId: String): List<RunRecord> = read(dir.resolve(taskId))

    fun all(): List<RunRecord> = taskDirs().flatMap(::read).sortedByDescending { it.startedAt }

    /** Deletes the records started over 90 days before [now], and task directories left empty. */
    fun prune(now: Instant) {
        for (taskDir in taskDirs()) {
            read(taskDir).filter { java.time.Duration.between(it.startedAt, now).toKotlinDuration() > KEPT }.forEach { fileOf(it).deleteIfExists() }
            if (taskDir.listDirectoryEntries().isEmpty()) taskDir.deleteIfExists()
        }
    }

    private fun taskDirs(): List<Path> = if (dir.exists()) dir.listDirectoryEntries().filter { it.isDirectory() } else emptyList()

    /** The record files the last reads could not parse, with why; they are left out of what the reads return. */
    private val unreadable = java.util.concurrent.ConcurrentHashMap<Path, String>()

    fun broken(): Map<Path, String> = unreadable.toMap()

    private fun read(taskDir: Path): List<RunRecord> =
        if (!taskDir.exists()) emptyList()
        else taskDir.listDirectoryEntries("*.json").mapNotNull { file ->
            runCatching { parseRecord(file.readText()) }
                .onSuccess { unreadable.remove(file) }
                .onFailure { unreadable[file] = it.message ?: it.javaClass.name }
                .getOrNull()
        }.sortedByDescending { it.startedAt }

    companion object {
        private val SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm")
        private val MANUAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
        val INSTANCE = RunRecords(Homes.myAir.resolve("scheduled-runs"), ZoneId.systemDefault())
    }
}

private val RECORD_KEYS = setOf("taskId", "slot", "manual", "ide", "ideStart", "session", "state", "error", "notified", "startedAt")

private fun recordJson(r: RunRecord): String = GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().apply {
    addProperty("taskId", r.taskId)
    addProperty("slot", r.slot.toString())
    addProperty("manual", r.manual)
    addProperty("ide", r.ide)
    addProperty("ideStart", r.ideStart.toString())
    addProperty("session", r.session)
    addProperty("state", r.state.name)
    r.error?.let { addProperty("error", it) }
    addProperty("notified", r.notified)
    addProperty("startedAt", r.startedAt.toString())
})

private fun parseRecord(text: String): RunRecord {
    val o = JsonParser.parseString(text).asJsonObject
    check(RECORD_KEYS.containsAll(o.keySet())) { "unexpected keys in a run record: ${o.keySet() - RECORD_KEYS}" }
    return RunRecord(
        o["taskId"].asString, Instant.parse(o["slot"].asString), o["manual"].asBoolean, o["ide"].asLong, Instant.parse(o["ideStart"].asString), o["session"].asString,
        RunState.valueOf(o["state"].asString), o["error"]?.asString, o["notified"].asBoolean, Instant.parse(o["startedAt"].asString),
    )
}

/** What a check makes of a run not settled yet: it [Ran], or it [Failed] (its process to be [Failed.close]d). */
sealed interface Verdict {
    data object Ran : Verdict
    data class Failed(val reason: String, val close: Boolean) : Verdict
}

/**
 * What [record] has come to at [now], seen by an IDE that wrote it ([own]) or not; [ideAlive]: the IDE that wrote it
 * runs; [hasTranscript]: its session wrote its first message; [processRuns]: this IDE still runs its process. Null: no
 * change. A start runs on the EDT in one go, so an own run still starting was cut short by an exception.
 */
fun verdict(record: RunRecord, own: Boolean, ideAlive: Boolean, hasTranscript: Boolean, processRuns: Boolean, now: Instant): Verdict? = when {
    record.state != RunState.STARTING && record.state != RunState.STARTED -> null
    record.state == RunState.STARTED && hasTranscript -> Verdict.Ran
    own && record.state == RunState.STARTING -> Verdict.Failed("the start did not finish", close = false)
    own && !processRuns -> Verdict.Failed("claude ended before its first message", close = false)
    own && java.time.Duration.between(record.startedAt, now).toKotlinDuration() >= START_DEADLINE ->
        Verdict.Failed("no first message within 5 minutes", close = true)
    !own && !ideAlive -> Verdict.Failed("the IDE running it ended", close = false)
    else -> null
}

/** Whether the process [pid] started at [start] still runs: a pid alone may since belong to another process. */
fun ideAlive(pid: Long, start: Instant): Boolean = ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null) == start

/** A previous run in [status] that the next slot must not overlap. */
fun blocksNext(status: SessionStatus?): Boolean = status == SessionStatus.WORKING || status == SessionStatus.NEEDS_INPUT
