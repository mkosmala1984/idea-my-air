package dev.myair.editor

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.terminal.frontend.session.TransferableTerminalSession
import com.intellij.terminal.frontend.session.TransferableTerminalSessionFactory
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.MyAirSettings
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.shownBy
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Homes
import dev.myair.store.PromptFiles
import dev.myair.tabs.TabsSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.TerminalEmulatorType
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.jvm.optionals.getOrNull
import kotlin.time.toKotlinDuration

/**
 * The `claude` process of every session tab, apart from the tab, as Air 263 keeps them: a tab binds a view of the process
 * ([attach]) and lets go of it when it closes ([release]). With [MyAirSettings.keepDetached] the process then runs on,
 * detached, until [retireIdle] finds it idle ([retires]); reopening the session binds the same process again. Touched on
 * the EDT only.
 */
@Service(Service.Level.APP)
class SessionTerminals(private val scope: CoroutineScope) : Disposable {
    // Every process lives here, so an exit cannot miss one: before this service there is none.
    init {
        ApplicationManager.getApplication().addApplicationListener(ExitConfirmation(), this)
    }

    /**
     * [session] is null while the process starts; [owner] the editor showing it, null while detached since [detachedAt].
     * [ended]: the process exited by itself while a tab showed it, which keeps showing the ended terminal until it closes.
     */
    private class Runtime(val promptFile: Path?, var project: Project, var owner: ClaudeSessionEditor?) {
        var session: TransferableTerminalSession? = null
        var detachedAt: Instant? = null
        var watch: Job? = null
        var ended = false
    }

    private val runtimes = HashMap<ClaudeSessionVirtualFile, Runtime>()

    /** Whether this run already said that a closed tab leaves its session working. */
    private var hinted = false

    /** Shows [file]'s process in [editor], starting it on first use; the editor that showed it before gets [ClaudeSessionEditor.showElsewhere]. */
    fun attach(file: ClaudeSessionVirtualFile, editor: ClaudeSessionEditor, project: Project) {
        ThreadingAssertions.assertEventDispatchThread()
        val runtime = runtimes[file]
        if (runtime == null) return start(file, editor, project)
        val previous = runtime.owner
        runtime.owner = editor
        runtime.project = project
        runtime.detachedAt = null
        if (previous != null && previous != editor) previous.showElsewhere()
        // Still starting: the view goes to the owner once the process is there.
        val session = runtime.session ?: return
        editor.show(session.bind(project))
    }

    /** [editor] closes: its process ends, runs on detached or is left to the tab now showing it ([releaseOf]). */
    fun release(file: ClaudeSessionVirtualFile, editor: ClaudeSessionEditor) {
        val runtime = runtimes[file] ?: return
        val started = runtime.session.let { it != null && it.sessionState.value != TerminalViewSessionState.NotStarted }
        val reopening = file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true
        when (releaseOf(runtime.owner == editor, started, runtime.ended, MyAirSettings.getInstance().keepDetached, reopening)) {
            Release.IGNORE -> return
            Release.CLOSE -> return close(file)
            Release.DETACH -> {}
        }
        runtime.owner = null
        runtime.detachedAt = Instant.now()
        thisLogger().info("claude for session ${file.sessionId} (pid ${file.pid}) runs on without a tab")
        if (file.status == SessionStatus.WORKING && !hinted) {
            hinted = true
            NotificationGroupManager.getInstance().getNotificationGroup("My Air")
                .createNotification("\"${file.title}\" keeps working. Open it again from Claude Sessions.", NotificationType.INFORMATION)
                .notify(runtime.project)
        }
    }

    /** Ends [file]'s process now; nothing when it has none. */
    fun close(file: ClaudeSessionVirtualFile) {
        val runtime = runtimes.remove(file) ?: return
        runtime.watch?.cancel()
        runtime.session?.close()
        runtime.promptFile?.deleteIfExists()
    }

    /** Sessions whose process runs without a tab, with the project it last showed in. */
    fun detached(): List<Pair<Project, ClaudeSessionVirtualFile>> =
        runtimes.filter { it.value.owner == null && it.value.detachedAt != null }.map { it.value.project to it.key }

    /** Ends every detached process that [retires] at [now], reading its status from [live] by pid and its tasks from [sessions]. */
    fun retireIdle(live: Map<String, LiveSession>, sessions: Map<String, ClaudeSession>, now: Instant) {
        val before = runtimes.size
        for ((project, file) in detached()) {
            val detachedAt = checkNotNull(runtimes.getValue(file).detachedAt)
            val (status, tasks) = activity(file, live, sessions)
            if (!retires(java.time.Duration.between(detachedAt, now).toKotlinDuration(), status, tasks)) continue
            thisLogger().info("claude for session ${file.sessionId} (pid ${file.pid}) ends: idle without a tab since $detachedAt in ${project.name}")
            close(file)
        }
        // Other IDEs and the taskbar open a detached session here; one gone sends them elsewhere.
        if (runtimes.size != before) TabsSync.getInstance().publish()
    }

    /**
     * The titles of the sessions that ending the processes of [project] (with a tab or detached), or all when null, would
     * interrupt ([interrupts]), read from [ClaudeSessionsService]'s last refresh.
     */
    fun interrupted(project: Project?): List<String> {
        ThreadingAssertions.assertEventDispatchThread()
        val service = ClaudeSessionsService.getInstance()
        val sessions = service.sessions.associateBy { it.id }
        return runtimes.filter { project == null || it.value.project == project }.keys
            .filter { file -> activity(file, service.live, sessions).let { (status, tasks) -> interrupts(status, tasks) } }
            .map { it.title }
    }

    /** [file]'s registry status ([shownBy]) and the number of its own background tasks still running ([runningTasks]). */
    private fun activity(file: ClaudeSessionVirtualFile, live: Map<String, LiveSession>, sessions: Map<String, ClaudeSession>): Pair<SessionStatus?, Int> {
        val status = live.shownBy(file.pid, file.job)?.value?.status
        val started = file.pid?.let { ProcessHandle.of(it).flatMap { process -> process.info().startInstant() }.getOrNull() }
        return status to runningTasks(sessions[file.sessionId]?.background.orEmpty(), started)
    }

    /** Ends the processes that last showed in [project], detached ones included. */
    private fun closeAll(project: Project) {
        runtimes.filterValues { it.project == project }.keys.forEach(::close)
    }

    private fun start(file: ClaudeSessionVirtualFile, editor: ClaudeSessionEditor, project: Project) {
        val claude = checkNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude")) { "claude executable not found on PATH" }
        val settings = MyAirSettings.getInstance()
        val service = ClaudeSessionsService.getInstance()
        val folders = service.folders
        val promptFile = sessionPlan(folders, file.sessionId, service.sessions.firstOrNull { it.id == file.sessionId }, file.planStart)
            ?.let { (folder, task) -> PromptFiles.write(Homes.myAir.resolve("prompts"), planPrompt(folders, folder, task, settings.briefs)) }
        val options = ShellStartupOptions.Builder()
            .workingDirectory(file.cwd.toString())
            .shellCommand(
                listOf(claude.path, "--append-system-prompt", appendedSystemPrompt(settings.briefs, settings.fileSessions)) +
                    (if (settings.briefs) listOf("--settings", writeBriefReminderSettings(Homes.myAir.resolve("hooks")).toString()) else emptyList()) +
                    promptFile.let { if (it == null) emptyList() else listOf("--append-system-prompt-file", it.toString()) } + file.claudeArgs,
            )
            // Fullscreen renderer: no flicker, mouse support; otherwise claude asks on every start whether to try it.
            // Skip auto-install of claude's own JetBrains plugin into this IDE, as Air does.
            .envVariables(mapOf("CLAUDE_CODE_NO_FLICKER" to "1", "CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL" to "true"))
            .processType(TerminalProcessType.NON_SHELL)
            .emulatorType(TerminalEmulatorType.Ghostty)
            .build()
        val runtime = Runtime(promptFile, project, editor)
        runtimes[file] = runtime
        scope.launch(Dispatchers.EDT) {
            val session = TransferableTerminalSessionFactory.getInstance().create(project, options, null, deferSessionStartUntilUiShown = true)
            // Closed while starting (Close and Settle, its project closed): nobody wants this process any more.
            if (runtimes[file] !== runtime) return@launch session.close()
            runtime.session = session
            // A view exists from the start, in the project the process started for; one bound elsewhere meanwhile needs a new one.
            runtime.owner?.show(if (runtime.project == project) session.view else session.bind(runtime.project))
            runtime.watch = scope.launch {
                session.sessionState.first { it == TerminalViewSessionState.Terminated }
                ApplicationManager.getApplication().invokeLater { if (runtimes[file] === runtime) ended(file, runtime) }
            }
            file.pid = session.processId()
            thisLogger().info("claude for session ${file.sessionId} runs as pid ${file.pid}")
        }
    }

    /** The process ended by itself (`/exit`, a crash): a tab showing it keeps the ended terminal, as before; a detached one goes. */
    private fun ended(file: ClaudeSessionVirtualFile, runtime: Runtime) {
        thisLogger().info("claude for session ${file.sessionId} (pid ${file.pid}) ended by itself")
        if (runtime.owner == null) return close(file)
        runtime.ended = true
        runtime.promptFile?.deleteIfExists()
    }

    /** Ends the processes of a project that closes; [projectClosed] covers a close that skipped [projectClosing]. */
    class ProjectClose : ProjectCloseListener {
        override fun projectClosing(project: Project) = getInstance().closeAll(project)
        override fun projectClosed(project: Project) = getInstance().closeAll(project)
    }

    override fun dispose() {}

    companion object {
        fun getInstance(): SessionTerminals = ApplicationManager.getApplication().getService(SessionTerminals::class.java)
    }
}
