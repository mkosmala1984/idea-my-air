package dev.myair.editor

import dev.myair.store.Homes
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.util.ui.UIUtil
import java.awt.Color
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.TerminalEmulatorType
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.panel
import dev.myair.MyAirSettings
import dev.myair.claude.LiveSessionRegistry
import dev.myair.tabs.SessionHosts
import java.beans.PropertyChangeListener
import java.awt.BorderLayout
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.PromptFiles
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
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
/** The grid of the editor a reworked terminal shows now: its output, or the alternate buffer of a fullscreen program like `claude`. */
private class EditorGrid(view: TerminalView) : TextGrid {
    private val editor = UIUtil.findComponentsOfType(view.component, EditorComponentImpl::class.java).map { it.editor }
        .single { it.component.isVisible }
    override val visibleWidth get() = editor.scrollingModel.visibleArea.width
    override val charWidth get() = checkNotNull(editor.characterGrid) { "terminal editor without a character grid" }.charWidth
    override val background: Color get() = editor.backgroundColor
}

class ClaudeSessionEditor(private val project: Project, private val file: ClaudeSessionVirtualFile) : UserDataHolderBase(), FileEditor {
    private val content = JPanel(BorderLayout())
    private var tab: TerminalToolWindowTab? = null
    /** The plan part of the system prompt, while `claude` may read it again. */
    private var promptFile: Path? = null

    init {
        content.add((if (file.restored) restoreBlocker() else null) ?: start())
    }

    /** Starts `claude` and returns the component showing it. */
    private fun start(): JComponent {
        val claude = checkNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude")) { "claude executable not found on PATH" }
        val briefs = MyAirSettings.getInstance().briefs
        val service = ClaudeSessionsService.getInstance()
        val folders = service.folders
        promptFile = sessionPlan(folders, file.sessionId, service.sessions.firstOrNull { it.id == file.sessionId }, file.planStart)
            ?.let { (folder, task) -> PromptFiles.write(Homes.myAir.resolve("prompts"), planPrompt(folders, folder, task, briefs)) }
        val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .workingDirectory(file.cwd.toString())
            .shellCommand(
                listOf(claude.path, "--append-system-prompt", appendedSystemPrompt(briefs, MyAirSettings.getInstance().fileSessions)) +
                    (if (briefs) listOf("--settings", writeBriefReminderSettings(Homes.myAir.resolve("hooks")).toString()) else emptyList()) +
                    promptFile.let { if (it == null) emptyList() else listOf("--append-system-prompt-file", it.toString()) } + file.claudeArgs,
            )
            // Fullscreen renderer: no flicker, mouse support; otherwise claude asks on every start whether to try it.
            // Skip auto-install of claude's own JetBrains plugin into this IDE, as Air does.
            .envVariables(mapOf("CLAUDE_CODE_NO_FLICKER" to "1", "CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL" to "true"))
            .processType(TerminalProcessType.NON_SHELL)
            .emulatorType(TerminalEmulatorType.Ghostty)
            .shouldAddToToolWindow(false)
            .closeOnProcessTermination(false)
            .restoreOnProjectReopen(false)
            .requestFocus(false)
            .deferSessionStartUntilUiShown(true)
            .createTab()
        this.tab = tab
        tab.view.coroutineScope.launch {
            val session = tab.view.sessionDeferred.await()
            file.pid = session.processId
            thisLogger().info("claude for session ${file.sessionId} runs as pid ${file.pid} in ${session.javaClass.name}")
        }
        return CenteredTerminal(tab.content.component, { EditorGrid(tab.view) }) { MyAirSettings.getInstance().terminalColumns }
    }

    /** Types [text] into `claude`'s prompt; false while the tab shows a notice instead of `claude`. */
    fun type(text: String): Boolean {
        val tab = tab ?: return false
        tab.view.sendText(text)
        return true
    }

    /** What a restored tab shows instead of starting: its directory is gone, or its session already runs elsewhere. */
    private fun restoreBlocker(): JComponent? {
        if (!file.cwd.isDirectory()) return notice("Session directory no longer exists:\n${file.cwd}")
        val live = LiveSessionRegistry(Homes.claude.resolve("sessions")).read()[file.sessionId] ?: return null
        val running = RunningElsewhere(file.title, live, file.cwd)
        val switch = running.host?.let { "Switch to Window" to { SessionHosts.bringToFront(it) } }
        return notice(running.message, listOfNotNull(switch, "Open Anyway" to ::openAnyway))
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
    override fun getPreferredFocusedComponent(): JComponent = tab?.view?.preferredFocusableComponent ?: content
    override fun getName() = "Claude"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        tab?.let { Disposer.dispose(it.content) }
        promptFile?.deleteIfExists()
    }
}
