package dev.myair.schedule

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.notification.NotificationAction
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
import dev.myair.schedule.ui.openScheduledTasks
import dev.myair.service.ClaudeSessionsService
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
 * reports the runs that fail ([RunRecords]): a tick every 30 s once [ClaudeSessionsService] has loaded, records read off
 * the EDT, starts on it.
 */
@Service(Service.Level.APP)
class ScheduledRuns(private val scope: CoroutineScope) {
    private val pid = ProcessHandle.current().pid()
    private val ideStart = ProcessHandle.current().info().startInstant().get()
    private val records = RunRecords.INSTANCE

    /** This IDE's runs by session id, while their process runs. */
    private val files = ConcurrentHashMap<String, ClaudeSessionVirtualFile>()

    /** This IDE's runs that have not written their first message yet. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** The broken files the user was told of, each once per error. */
    private val told = ConcurrentHashMap.newKeySet<String>()
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

    /**
     * Of this IDE's runs whose process runs, those [SessionTerminals.retireIdle] keeps: unread ones, so the result stays
     * in sight until seen, and those still before their first message, which the start deadline ends instead.
     */
    fun held(unread: Set<String>): Set<String> = files.keys.filterTo(HashSet()) { it in unread || it in pending }

    private suspend fun tick(now: Instant) {
        val service = ClaudeSessionsService.getInstance()
        // Before the first refresh no session or folder is known, and every run would look gone.
        if (!withContext(Dispatchers.EDT) { service.loaded }) return
        val all = withContext(Dispatchers.IO) { records.all() }
        withContext(Dispatchers.EDT) { check(all.filter { it.state == RunState.STARTING || it.state == RunState.STARTED }, now) }
        val read = withContext(Dispatchers.IO) { ScheduledTaskStore.INSTANCE.read() }
        val broken = records.broken().map { (file, error) -> "Run record $file is broken: $error" } +
            listOfNotNull(read.exceptionOrNull()?.let { "${ScheduledTaskStore.INSTANCE.file} is broken, so no task runs: ${it.message}" })
        val tasks = read.getOrDefault(emptyList())
        // A run needs an open project; another IDE with one may take the slot meanwhile.
        if (withContext(Dispatchers.EDT) { openProjects().isNotEmpty() }) {
            val zoned = now.atZone(ZoneId.systemDefault())
            for (task in tasks) {
                val slot = withContext(Dispatchers.IO) { dueSlot(task, zoned) { records.exists(task.id, it.toInstant()) } } ?: continue
                withContext(Dispatchers.EDT) { run(task, slot.toInstant(), manual = false, now) }
            }
        }
        val failed = withContext(Dispatchers.IO) { records.all().filter { it.ide == pid && it.state == RunState.FAILED && !it.notified } }
        withContext(Dispatchers.EDT) {
            notifyFailures(failed, tasks.associateBy { it.id })
            broken.filter(told::add).forEach(::notify)
        }
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        if (pruned != today) withContext(Dispatchers.IO) { records.prune(now) }.also { pruned = today }
    }

    /** Settles the [active] runs ([verdict]); the IDE that writes a failure tells the user. */
    private fun check(active: List<RunRecord>, now: Instant) {
        val terminals = SessionTerminals.getInstance()
        val scanned = ClaudeSessionsService.getInstance().sessions.mapTo(HashSet()) { it.id }
        files.entries.removeIf { !terminals.runs(it.value) }
        for (record in active) {
            val own = record.ide == pid && record.ideStart == ideStart
            val file = files[record.session]
            val verdict = verdict(record, own, ideAlive(record.ide, record.ideStart), record.session in scanned, file != null, now) ?: continue
            pending.remove(record.session)
            when (verdict) {
                Verdict.Ran -> records.update(record.copy(state = RunState.RAN))
                is Verdict.Failed -> {
                    if (verdict.close && file != null) terminals.close(file)
                    records.update(record.copy(ide = pid, ideStart = ideStart, state = RunState.FAILED, error = verdict.reason))
                }
            }
        }
    }

    /** Starts [taskId] now; null when it started, else why not. */
    fun runNow(taskId: String): String? {
        ThreadingAssertions.assertEventDispatchThread()
        val task = checkNotNull(ScheduledTaskStore.INSTANCE.read().getOrThrow().firstOrNull { it.id == taskId }) { "no scheduled task $taskId" }
        if (previousBlocks(task)) return PREVIOUS_RUNNING
        val now = Instant.now()
        val record = run(task, now, manual = true, now) ?: return "it was just started"
        return record.error
    }

    private fun previousBlocks(task: ScheduledTask): Boolean {
        val previous = records.of(task.id).firstOrNull { it.state != RunState.SKIPPED } ?: return false
        return blocksNext(ClaudeSessionsService.getInstance().live[previous.session]?.status)
    }

    private fun openProjects() = ProjectManager.getInstance().openProjects.filterNot { it.isDefault }

    /** Claims [slot] of [task] and starts it; the record as it ends up, or null when another IDE or an earlier tick has the slot. */
    private fun run(task: ScheduledTask, slot: Instant, manual: Boolean, now: Instant): RunRecord? {
        ThreadingAssertions.assertEventDispatchThread()
        val session = UUID.randomUUID().toString()
        val record = RunRecord(task.id, slot, manual, pid, ideStart, session, RunState.STARTING, null, false, now)
        if (!manual && previousBlocks(task)) {
            val skipped = record.copy(state = RunState.SKIPPED, error = PREVIOUS_RUNNING)
            return skipped.takeIf { records.claim(it) }
        }
        if (!records.claim(record)) return null
        // Whatever throws from here fails the record; one left starting would never be told.
        return try {
            val projects = openProjects()
            val dir = task.preset.project?.let(Path::of) ?: projects.firstOrNull()?.let(::projectBase)
            val project: Project? = dir?.let { pickProject(it, projects.map(::projectBase)) }?.let(projects::get)
            val service = ClaudeSessionsService.getInstance()
            val claude = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude") != null
            val error = preStartError(task, dir.takeIf { project != null }, task.folder?.let { service.folders.find(it) } != null, claude)
            if (error != null) return record.copy(state = RunState.FAILED, error = error).also(records::update)
            // Seen as of its start: the first refresh would otherwise mark it at its last message, maybe the reply itself.
            service.seenStore.mark(session, now.toEpochMilli())
            task.folder?.let { folder -> service.updateFolders(ChangeSource.AUTO) { it.assign(session, folder) } }
            val file = ClaudeSessionVirtualFile(session, dir!!, dir, task.name, task.claudeArgs(session))
            SessionTerminals.getInstance().startDetached(file, project!!)
            files[session] = file
            pending.add(session)
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
            notify("Scheduled task \"${tasks[record.taskId]?.name ?: "(deleted) ${record.taskId}"}\" failed: ${record.error}")
            records.update(record.copy(notified = true))
        }
    }

    private fun notify(text: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("My Air Scheduled Tasks")
            .createNotification(text, NotificationType.ERROR)
            .addAction(NotificationAction.createSimpleExpiring("Open Scheduled Tasks") { openScheduledTasks(null) })
            .notify(null)
    }

    companion object {
        private val TICK = 30.seconds
        const val PREVIOUS_RUNNING = "previous run still running"
        fun getInstance(): ScheduledRuns = ApplicationManager.getApplication().getService(ScheduledRuns::class.java)
    }
}
