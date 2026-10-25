package dev.myair

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFrame
import dev.myair.store.IdeTabs
import dev.myair.store.TabStore
import java.nio.file.Path

/** This IDE's side of [TabStore]: publishes its session tabs and shows a tab when another IDE asks. */
@Service(Service.Level.APP)
class TabsSync : Disposable {
    private val store = TabStore(Path.of(System.getProperty("user.home"), ".my-air"))
    private val self = ProcessHandle.current().pid()

    private val requests = store.receiveRequests { sessionId ->
        ApplicationManager.getApplication().invokeLater {
            val tab = SessionHosts.findTab(sessionId)
            if (tab != null) return@invokeLater SessionHosts.focus(tab)
            // The taskbar widget sends any running session here when no IDE shows it, and a tab may have closed since
            // the sender read the lists; opening goes where the session runs, as from the tool window.
            val session = ClaudeSessionsService.getInstance().sessions.firstOrNull { it.id == sessionId }
                ?: return@invokeLater thisLogger().warn("focus request for $sessionId, which has no transcript yet")
            val projects = ProjectManager.getInstance().openProjects
            val project = projects.firstOrNull { session.cwd.startsWith(projectBase(it)) } ?: projects.first()
            ProjectUtil.focusProjectWindow(project, true)
            ClaudeSessionOpener.open(project, session)
        }
    }

    /** Called on the EDT whenever tabs open, close or switch session. */
    fun publish() = store.publish(SessionHosts.openTabs())

    /** Session tabs of every IDE running this plugin, this one first. */
    fun allIdes(): List<IdeTabs> = store.all().sortedBy { it.pid != self }

    fun isSelf(ide: IdeTabs) = ide.pid == self

    /** Asks the other IDE whose tab shows [sessionId] to bring it up; false when no other IDE has one. */
    fun focusElsewhere(sessionId: String): Boolean {
        val ide = allIdes().firstOrNull { !isSelf(it) && it.tabs.any { tab -> sessionId in tab.sessionIds } } ?: return false
        focusIn(ide, sessionId)
        return true
    }

    fun focusIn(ide: IdeTabs, sessionId: String) {
        if (isSelf(ide)) return SessionHosts.focus(checkNotNull(SessionHosts.findTab(sessionId)) { "no tab here shows $sessionId" })
        SessionHosts.allowForeground(ide.pid)
        store.requestFocus(ide.pid, sessionId)
    }

    override fun dispose() {
        requests.close()
        store.withdraw()
    }

    companion object {
        fun getInstance(): TabsSync = ApplicationManager.getApplication().getService(TabsSync::class.java)
    }
}

/** Starts [TabsSync] with the first project, so other IDEs see this one's tabs restored on startup. */
class TabsSyncStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { TabsSync.getInstance().publish() }
    }
}

/**
 * Republishes after the editor manager has applied the change, so openFiles already reflects it. A selected tab is a
 * session the user looks at, which the taskbar widget no longer shows as unread.
 */
class TabsSyncListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) = republish()
    override fun fileClosed(source: FileEditorManager, file: VirtualFile) = republish()
    override fun selectionChanged(event: FileEditorManagerEvent) = ApplicationManager.getApplication().invokeLater { ClaudeSessionsService.getInstance().lookedAt() }

    private fun republish() = ApplicationManager.getApplication().invokeLater { TabsSync.getInstance().publish() }
}

/** Coming back to the IDE shows the selected tab again, so its session counts as seen, as on [TabsSyncListener.selectionChanged]. */
class SeenOnActivation : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) = ApplicationManager.getApplication().invokeLater { ClaudeSessionsService.getInstance().lookedAt() }
}
