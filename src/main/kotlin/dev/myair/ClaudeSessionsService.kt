package dev.myair

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The one watcher over `~/.claude`, shared by every project: session list, live statuses, and the session each tab shows.
 * [sessions], [live] and the listeners are touched on the EDT only.
 */
@Service(Service.Level.APP)
class ClaudeSessionsService : Disposable {
    private val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
    private val scanner = ClaudeSessionScanner(claudeHome.resolve("projects"))
    private val registry = LiveSessionRegistry(claudeHome.resolve("sessions"))

    var sessions = emptyList<ClaudeSession>()
        private set

    /** Session id to its running `claude` process; a session absent here has no process. */
    var live = emptyMap<String, LiveSession>()
        private set

    private val listeners = ArrayList<() -> Unit>()

    /** One refresh at a time, so an older result never lands after a newer one. */
    private val refresher = AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude sessions refresh", 1)

    // File events drive refreshes; the 60 s rescan covers events the OS dropped, as Air's REREAD_INTERVAL does.
    private val watcher = ClaudeHomeWatcher(claudeHome.resolve("sessions"), claudeHome.resolve("projects")) { refresh() }
    private val safetyNet = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::refresh, 60, 60, TimeUnit.SECONDS)

    init {
        refresh()
    }

    /** Calls [listener] on the EDT after every change, until [parent] is disposed. */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun refresh() {
        // Nobody reads the Future, so an exception would vanish with it and freeze the list; log it as an IDE error instead.
        refresher.execute {
            val (scanned, current) = try {
                scanner.scan() to registry.read()
            } catch (e: Throwable) {
                thisLogger().error("Claude session refresh failed", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                if (scanned == sessions && current == live) return@invokeLater
                sessions = scanned
                live = current
                retargetTabs()
                listeners.forEach { it() }
            }
        }
    }

    private fun retargetTabs() {
        val titles = sessions.associate { it.id to it.title }
        var changed = false
        for (project in ProjectManager.getInstance().openProjects) {
            val editors = FileEditorManagerEx.getInstanceEx(project)
            for (file in editors.openFiles.filterIsInstance<ClaudeSessionVirtualFile>()) {
                val (sessionId, title) = retarget(file.sessionId, file.title, file.pid, live, titles)
                if (sessionId == file.sessionId && title == file.title) continue
                file.sessionId = sessionId
                file.title = title
                editors.updateFilePresentation(file)
                changed = true
            }
        }
        if (changed) TabsSync.getInstance().publish()
    }

    override fun dispose() {
        safetyNet.cancel(false)
        watcher.close()
    }

    companion object {
        fun getInstance(): ClaudeSessionsService = ApplicationManager.getApplication().getService(ClaudeSessionsService::class.java)

        /**
         * The session and title a tab running process [pid] shows now. `/clear` and `/resume` keep the process but switch its
         * session, which the registry reports under the same pid. A process not (or no longer) registered keeps its session.
         */
        fun retarget(sessionId: String, title: String, pid: Long?, live: Map<String, LiveSession>, titles: Map<String, String>): Pair<String, String> {
            val current = live.entries.firstOrNull { it.value.pid == pid }?.key ?: sessionId
            return current to (titles[current] ?: if (current == sessionId) title else NEW_SESSION_TITLE)
        }

        const val NEW_SESSION_TITLE = "New session"
    }
}
