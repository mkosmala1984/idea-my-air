package dev.myair.schedule

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.SessionTerminals
import dev.myair.editor.projectBase
import dev.myair.folders.ChangeSource
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Homes
import dev.myair.store.SeenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Runs scheduled tasks ([ScheduledTaskStore]) at their slots as detached sessions ([SessionTerminals.startDetached]) and
 * reports the runs that fail ([RunRecords]): a tick every 30 s, records read off the EDT, starts on it.
 */
@Service(Service.Level.APP)
class ScheduledRuns(private val scope: CoroutineScope) {
    private val pid = ProcessHandle.current().pid()
    private val records = RunRecords.INSTANCE
    private val seen = SeenStore(Homes.myAir.resolve("seen"))

    /** This IDE's runs by session id, while their process runs. */
    private val files = ConcurrentHashMap<String, ClaudeSessionVirtualFile>()

    /** The last error the task file gave, logged once. */
    private var fileError: String? = null
    private var pruned: LocalDate? = null

    init {
        scope.launch {
            while (true) {
                try {
                    tick(Instant.now())
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    thisLogger().error("Scheduled tasks tick failed", e)
                }
                delay(TICK)
            }
        }
    }

    /** Of [unread], the sessions of this IDE's runs whose process still runs: [SessionTerminals.retireIdle] keeps them. */
    fun held(unread: Set<String>): Set<String> = files.keys.intersect(unread)

    private suspend fun tick(now: Instant) {
        val active = withContext(Dispatchers.IO) { records.all().filter { it.state == RunState.STARTING || it.state == RunState.STARTED } }
        withContext(Dispatchers.EDT) { check(active, now) }
        val read = ScheduledTaskStore.INSTANCE.read()
        val tasks = read.getOrElse {
            if (it.message != fileError) thisLogger().warn("Scheduled tasks are not run: ${ScheduledTaskStore.INSTANCE.file} is broken", it)
            fileError = it.message
            emptyList()
        }
        if (read.isSuccess) fileError = null
        val zoned = now.atZone(ZoneId.systemDefault())
        for (task in tasks) {
            val slot = withContext(Dispatchers.IO) { dueSlot(task, zoned) { records.exists(task.id, it.toInstant()) } } ?: continue
            withContext(Dispatchers.EDT) { run(task, slot.toInstant(), manual = false, now) }
        }
        val failed = withContext(Dispatchers.IO) { records.all().filter { it.ide == pid && it.state == RunState.FAILED && !it.notified } }
        withContext(Dispatchers.EDT) { notifyFailures(failed, tasks.associateBy { it.id }) }
        if (pruned != zoned.toLocalDate()) withContext(Dispatchers.IO) { records.prune(now) }.also { pruned = zoned.toLocalDate() }
    }

    /** Fails the [active] runs that will never get going: stuck before their first message, ended, or left by an IDE gone. */
    private fun check(active: List<RunRecord>, now: Instant) {
        val terminals = SessionTerminals.getInstance()
        val scanned = ClaudeSessionsService.getInstance().sessions.mapTo(HashSet()) { it.id }
        files.entries.removeIf { !terminals.runs(it.value) }
        for (record in active) {
            val hasTranscript = record.session in scanned
            val error = if (record.ide == pid) {
                val file = files[record.session]
                when {
                    file != null && startTimedOut(record, now, hasTranscript) -> "no first message within 5 minutes".also { terminals.close(file); files.remove(record.session) }
                    record.state == RunState.STARTED && file == null && !hasTranscript -> "claude ended before its first message"
                    else -> null
                }
            } else if (orphaned(record, ProcessHandle.of(record.ide).isPresent, hasTranscript)) "the IDE running it ended" else null
            // The IDE that writes the failure tells the user.
            error?.let { records.update(record.copy(ide = pid, state = RunState.FAILED, error = it)) }
        }
    }

    /** Starts [taskId] now; null when it started, else why not. */
    fun runNow(taskId: String): String? {
        ThreadingAssertions.assertEventDispatchThread()
        val task = checkNotNull(ScheduledTaskStore.INSTANCE.read().getOrThrow().firstOrNull { it.id == taskId }) { "no scheduled task $taskId" }
        if (previousBlocks(task)) return PREVIOUS_RUNNING
        val now = Instant.now()
        // The failure is also notified by the next tick.
        val record = run(task, now, manual = true, now) ?: return "it was just started"
        return record.error
    }

    private fun previousBlocks(task: ScheduledTask): Boolean {
        val previous = records.of(task.id).firstOrNull { it.state != RunState.SKIPPED } ?: return false
        return blocksNext(ClaudeSessionsService.getInstance().live[previous.session]?.status)
    }

    /** Claims [slot] of [task] and starts it; the record as it ends up, or null when another IDE or an earlier tick has the slot. */
    private fun run(task: ScheduledTask, slot: Instant, manual: Boolean, now: Instant): RunRecord? {
        ThreadingAssertions.assertEventDispatchThread()
        val session = UUID.randomUUID().toString()
        val record = RunRecord(task.id, slot, manual, pid, session, RunState.STARTING, null, false, now)
        if (!manual && previousBlocks(task)) {
            val skipped = record.copy(state = RunState.SKIPPED, error = PREVIOUS_RUNNING)
            return skipped.takeIf { records.claim(it) }
        }
        if (!records.claim(record)) return null
        val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDefault }
        val dir = task.preset.project?.let(Path::of) ?: projects.firstOrNull()?.let(::projectBase)
        val project: Project? = dir?.let { pickProject(it, projects.map(::projectBase)) }?.let(projects::get)
        val service = ClaudeSessionsService.getInstance()
        val claude = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude") != null
        val error = preStartError(task, dir.takeIf { project != null }, task.folder?.let { service.folders.find(it) } != null, claude)
        if (error != null) return record.copy(state = RunState.FAILED, error = error).also(records::update)
        return try {
            // Seen as of its start: the first refresh would otherwise mark it at its last message, maybe the reply itself.
            seen.mark(session, now.toEpochMilli())
            task.folder?.let { folder -> service.updateFolders(ChangeSource.AUTO) { it.assign(session, folder) } }
            val file = ClaudeSessionVirtualFile(session, dir!!, dir, task.name, task.claudeArgs(session))
            SessionTerminals.getInstance().startDetached(file, project!!)
            files[session] = file
            thisLogger().info("Scheduled task ${task.name} runs as session $session")
            record.copy(state = RunState.STARTED).also(records::update)
        } catch (e: Exception) {
            thisLogger().error("Scheduled task ${task.name} did not start", e)
            record.copy(state = RunState.FAILED, error = e.message ?: e.javaClass.name).also(records::update)
        }
    }

    /** Tells the user of the [failed] runs of this IDE not told yet, once. */
    private fun notifyFailures(failed: List<RunRecord>, tasks: Map<String, ScheduledTask>) {
        for (record in failed) {
            val name = tasks[record.taskId]?.name ?: record.taskId
            NotificationGroupManager.getInstance().getNotificationGroup("My Air Scheduled Tasks")
                .createNotification("Scheduled task \"$name\" failed: ${record.error}", NotificationType.ERROR)
                .notify(null)
            records.update(record.copy(notified = true))
        }
    }

    companion object {
        private val TICK = 30.seconds
        const val PREVIOUS_RUNNING = "previous run still running"
        fun getInstance(): ScheduledRuns = ApplicationManager.getApplication().getService(ScheduledRuns::class.java)
    }
}
