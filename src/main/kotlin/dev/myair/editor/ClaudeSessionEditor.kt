package dev.myair.editor

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalWidget
import dev.myair.MyAirSettings
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.beans.PropertyChangeListener
import javax.swing.JComponent

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

    private val centered = checkNotNull(JBTerminalWidget.asJediTermWidget(widget)) { "not a JediTerm terminal: $widget" }.let {
        CenteredTerminal(widget.component, it.terminalPanel, it.settingsProvider) { MyAirSettings.getInstance().terminalColumns }
    }

    override fun getComponent(): JComponent = centered
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
