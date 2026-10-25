package dev.myair.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.myair.ClaudeSessionsService
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.folders.ChangeSource
import dev.myair.tabs.SessionHosts
import dev.myair.tabs.TabsSync
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory

fun projectBase(project: Project): Path = Path.of(checkNotNull(project.basePath) { "project ${project.name} has no base path" })

/** Opening a session goes where it already runs, since two `claude` processes on one id would append to one transcript. */
object ClaudeSessionOpener {
    fun open(project: Project, session: ClaudeSession) {
        val tab = SessionHosts.findTab(session.id)
        if (tab != null) return SessionHosts.focus(tab)
        if (TabsSync.getInstance().focusElsewhere(session.id)) return
        val live = ClaudeSessionsService.getInstance().live[session.id]
        if (live != null && !confirmRunningElsewhere(project, session, live)) return
        if (!session.cwd.isDirectory()) {
            Messages.showErrorDialog(project, "Session directory no longer exists:\n${session.cwd}", "Cannot Resume Session")
            return
        }
        openTab(project, ClaudeSessionVirtualFile(session.id, session.cwd, session.title, listOf("--resume", session.id)))
    }

    /**
     * The session runs with no tab in any IDE running this plugin: in an IDE without it, Claude Desktop, a terminal, or a
     * part of this IDE that is not an editor tab. Offers that window when it belongs to another program; returns whether
     * to open a second process.
     */
    private fun confirmRunningElsewhere(project: Project, session: ClaudeSession, live: LiveSession): Boolean {
        val host = SessionHosts.hostWindow(live.pid, session.cwd)?.takeIf { it.pid != ProcessHandle.current().pid() }
        val where = host?.let { "in ${it.executable} (\"${it.title}\")" } ?: "outside an editor tab of this IDE"
        val message = "\"${session.title}\" is already running $where: claude pid ${live.pid}, started from ${live.entrypoint}.\n" +
            "Opening it here starts a second process that writes to the same transcript."
        val options = listOfNotNull(if (host != null) "Switch to Window" else null, "Open Anyway", "Cancel")
        // Escape closes the dialog with -1, which means Cancel.
        return when (options.getOrNull(Messages.showDialog(project, message, "Session Already Running", options.toTypedArray(), 0, Messages.getWarningIcon()))) {
            "Switch to Window" -> false.also { SessionHosts.bringToFront(host!!) }
            "Open Anyway" -> true
            else -> false
        }
    }

    fun startNew(project: Project, cwd: Path = projectBase(project), id: String = UUID.randomUUID().toString()) {
        openTab(project, ClaudeSessionVirtualFile(id, cwd, ClaudeSessionsService.NEW_SESSION_TITLE, listOf("--session-id", id)))
        val service = ClaudeSessionsService.getInstance()
        val target = project.service<FolderTarget>().folderId
        if (target != null && service.foldersError == null) service.updateFolders(ChangeSource.AUTO) { it.assign(id, target) }
    }

    private fun openTab(project: Project, file: ClaudeSessionVirtualFile) {
        ClaudeSessionsService.getInstance() // starts tracking which session each tab shows
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}

/** The folder new sessions of this project window go to: set by the last selection in Folders mode, none until then. */
@Service(Service.Level.PROJECT)
class FolderTarget {
    var folderId: String? = null
}
