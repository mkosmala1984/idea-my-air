package dev.myair

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.UUID
import javax.swing.JComponent
import kotlin.io.path.isDirectory

/** A tab running `claude <claudeArgs>` in [cwd]; [sessionId] identifies the conversation whether it is resumed or new. */
class ClaudeSessionVirtualFile(val sessionId: String, val cwd: Path, title: String, val claudeArgs: List<String>) :
    LightVirtualFile(title, FileTypes.PLAIN_TEXT, "") {
    init {
        isWritable = false
    }
}

object ClaudeSessionOpener {
    fun open(project: Project, session: ClaudeSession) {
        if (!session.cwd.isDirectory()) {
            Messages.showErrorDialog(project, "Session directory no longer exists:\n${session.cwd}", "Cannot Resume Session")
            return
        }
        show(project, session.id) { ClaudeSessionVirtualFile(session.id, session.cwd, session.title, listOf("--resume", session.id)) }
    }

    fun startNew(project: Project) {
        val cwd = Path.of(checkNotNull(project.basePath) { "project ${project.name} has no base path" })
        val id = UUID.randomUUID().toString()
        show(project, id) { ClaudeSessionVirtualFile(id, cwd, "New session", listOf("--session-id", id)) }
    }

    /** One tab per session across all open projects: two `claude` processes on one id would append to the same transcript. */
    private fun show(project: Project, sessionId: String, create: () -> ClaudeSessionVirtualFile) {
        val open = ProjectManager.getInstance().openProjects.firstNotNullOfOrNull { p ->
            FileEditorManager.getInstance(p).openFiles.firstOrNull { it is ClaudeSessionVirtualFile && it.sessionId == sessionId }?.let { p to it }
        }
        val (owner, file) = open ?: (project to create())
        FileEditorManager.getInstance(owner).openFile(file, true)
    }
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
        LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(this, options, true)
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
