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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.dsl.builder.panel
import dev.myair.MyAirSettings
import dev.myair.claude.LiveSessionRegistry
import dev.myair.tabs.SessionHosts
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.beans.PropertyChangeListener
import java.awt.BorderLayout
import java.awt.GridBagLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.io.path.isDirectory

class ClaudeSessionEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ClaudeSessionVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ClaudeSessionEditor(project, file as ClaudeSessionVirtualFile)
    override fun getEditorTypeId() = "claude-session"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * Runs `claude` in the session's cwd; closing the tab disposes the widget and ends the process. A tab restored on startup
 * starts only when its directory exists and its session runs nowhere else, or when the user chooses Open Anyway.
 */
class ClaudeSessionEditor(private val project: Project, private val file: ClaudeSessionVirtualFile) : UserDataHolderBase(), FileEditor {
    private val content = JPanel(BorderLayout())
    private var widget: TerminalWidget? = null

    init {
        content.add((if (file.restored) restoreBlocker() else null) ?: start())
    }

    /** Starts `claude` and returns the component showing it. */
    private fun start(): JComponent {
        val claude = checkNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude")) { "claude executable not found on PATH" }
        val options = ShellStartupOptions.Builder()
            .workingDirectory(file.cwd.toString())
            .shellCommand(listOf(claude.path) + file.claudeArgs)
            // Fullscreen renderer: no flicker, mouse support; otherwise claude asks on every start whether to try it.
            // Skip auto-install of claude's own JetBrains plugin into this IDE, as Air does.
            .envVariables(mapOf("CLAUDE_CODE_NO_FLICKER" to "1", "CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL" to "true"))
            .build()
        val widget = LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(this, options, true)
        widget.ttyConnectorAccessor.executeWithTtyConnector { tty ->
            file.pid = checkNotNull(ShellTerminalWidget.getProcessTtyConnector(tty)) { "claude tab without a process: $tty" }.process.pid()
            thisLogger().info("claude for session ${file.sessionId} runs as pid ${file.pid}")
        }
        this.widget = widget
        val jediTerm = checkNotNull(JBTerminalWidget.asJediTermWidget(widget)) { "not a JediTerm terminal: $widget" }
        return CenteredTerminal(widget.component, jediTerm.terminalPanel, jediTerm.settingsProvider) { MyAirSettings.getInstance().terminalColumns }
    }

    /** Types [text] into `claude`'s prompt; false while the tab shows a notice instead of `claude`. */
    fun type(text: String): Boolean {
        val widget = widget ?: return false
        widget.ttyConnectorAccessor.executeWithTtyConnector { it.write(text) }
        return true
    }

    /** What a restored tab shows instead of starting: its directory is gone, or its session already runs elsewhere. */
    private fun restoreBlocker(): JComponent? {
        if (!file.cwd.isDirectory()) return notice("Session directory no longer exists:\n${file.cwd}")
        val live = LiveSessionRegistry(Path.of(System.getProperty("user.home"), ".claude", "sessions")).read()[file.sessionId] ?: return null
        val host = ClaudeSessionOpener.hostElsewhere(live, file.cwd)
        val switch = host?.let { "Switch to Window" to { SessionHosts.bringToFront(it) } }
        return notice(ClaudeSessionOpener.runningElsewhereMessage(file.title, live, host), listOfNotNull(switch, "Open Anyway" to ::openAnyway))
    }

    private fun openAnyway() {
        content.removeAll()
        content.add(start())
        content.revalidate()
        IdeFocusManager.getInstance(project).requestFocus(preferredFocusedComponent, true)
    }

    private fun notice(message: String, buttons: List<Pair<String, () -> Unit>> = emptyList()) = JPanel(GridBagLayout()).apply {
        add(panel {
            row { text(StringUtil.escapeXmlEntities(message).replace("\n", "<br>")) }
            if (buttons.isNotEmpty()) row { for ((label, action) in buttons) button(label) { action() } }
        })
    }

    override fun getComponent(): JComponent = content
    override fun getPreferredFocusedComponent(): JComponent = widget?.preferredFocusableComponent ?: content
    override fun getName() = "Claude"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
