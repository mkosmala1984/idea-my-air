package dev.myair

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.UUID
import javax.swing.Icon
import javax.swing.JComponent
import kotlin.io.path.isDirectory

/**
 * A tab running `claude <claudeArgs>` in [cwd]. [sessionId] and [title] follow the session the process shows now, which
 * `/clear` or `/resume` can change; [ClaudeSessionsService] updates them by [pid], known once the process has started.
 */
class ClaudeSessionVirtualFile(var sessionId: String, val cwd: Path, var title: String, val claudeArgs: List<String>) :
    LightVirtualFile(title, FileTypes.PLAIN_TEXT, "") {
    @Volatile
    var pid: Long? = null

    /** The status the registry reports for [pid]; null until the process registers and after it ends. */
    @Volatile
    var status: SessionStatus? = null

    init {
        isWritable = false
    }
}

class ClaudeSessionTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) = (file as? ClaudeSessionVirtualFile)?.title
}

/** The tab icon shows the session's status, as the rows of the Claude Sessions tool window do. */
class ClaudeSessionFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? ClaudeSessionVirtualFile)?.let { it.status?.icon ?: AllIcons.Toolwindows.ToolWindowMessages }
}

val SessionStatus.icon: Icon
    get() = when (this) {
        SessionStatus.WORKING -> AllIcons.Actions.MoreHorizontal
        SessionStatus.NEEDS_INPUT -> AllIcons.General.Warning
        SessionStatus.IDLE -> AllIcons.Actions.Checked
    }

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

class NewClaudeSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = ClaudeSessionOpener.startNew(e.project!!)
}

class ClaudeSessionEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ClaudeSessionVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ClaudeSessionEditor(project, file as ClaudeSessionVirtualFile)
    override fun getEditorTypeId() = "claude-session"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Runs `claude` in the session's cwd; closing the tab disposes the widget and ends the process. */
class ClaudeSessionEditor(project: Project, private val file: ClaudeSessionVirtualFile) : UserDataHolderBase(), FileEditor {
    private val widget = run {
        val claude = checkNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude")) { "claude executable not found on PATH" }
        val options = ShellStartupOptions.Builder()
            .workingDirectory(file.cwd.toString())
            .shellCommand(listOf(claude.path) + file.claudeArgs)
            // Fullscreen renderer: no flicker, mouse support; otherwise claude asks on every start whether to try it.
            // Skip auto-install of claude's own JetBrains plugin into this IDE, as Air does.
            .envVariables(mapOf("CLAUDE_CODE_NO_FLICKER" to "1", "CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL" to "true"))
            .build()
        LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(this, options, true).also { widget ->
            widget.ttyConnectorAccessor.executeWithTtyConnector { tty ->
                file.pid = checkNotNull(ShellTerminalWidget.getProcessTtyConnector(tty)) { "claude tab without a process: $tty" }.process.pid()
                thisLogger().info("claude for session ${file.sessionId} runs as pid ${file.pid}")
            }
        }
    }

    override fun getComponent(): JComponent = widget.component
    override fun getPreferredFocusedComponent(): JComponent = widget.preferredFocusableComponent
    override fun getName() = "Claude"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
