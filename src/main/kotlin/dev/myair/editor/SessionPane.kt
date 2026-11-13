package dev.myair.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.myair.MyAirSettings
import dev.myair.claude.LiveSessionRegistry
import dev.myair.store.Homes
import dev.myair.tabs.SessionHosts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.io.path.isDirectory

/** What embeds a [SessionPane]: a session tab, or a plan tab showing its manager session (none while loading or once its plan is gone). */
interface SessionPaneHost {
    val pane: SessionPane?
}

/** The grid of the editor a reworked terminal shows now: its output, or the alternate buffer of a fullscreen program like `claude`. */
private class EditorGrid(view: TerminalView) : TextGrid {
    private val editor = UIUtil.findComponentsOfType(view.component, EditorComponentImpl::class.java).map { it.editor }
        .single { it.component.isVisible }
    override val visibleWidth get() = editor.scrollingModel.visibleArea.width
    override val charWidth get() = checkNotNull(editor.characterGrid) { "terminal editor without a character grid" }.charWidth
    override val background: Color get() = editor.backgroundColor
}

/**
 * Shows the session's `claude`, which [SessionTerminals] runs apart from the pane: disposing it detaches it or ends it
 * ([SessionTerminals.release]). With [checkFirst] (a tab restored on startup) it starts only when its directory exists and
 * its session runs nowhere else, or when the user chooses Open Anyway.
 */
class SessionPane(private val project: Project, val file: ClaudeSessionVirtualFile, checkFirst: Boolean) : Disposable {
    private val content = JPanel(BorderLayout())
    /** The view of the process this pane shows; null while it starts, shows elsewhere or a notice stands instead. */
    private var view: TerminalView? = null

    val component: JComponent get() = content
    val preferredFocusedComponent: JComponent get() = view?.preferredFocusableComponent ?: content

    init {
        val blocker = if (checkFirst) restoreBlocker() else null
        if (blocker != null) content.add(blocker) else attach()
    }

    private fun attach() = SessionTerminals.getInstance().attach(file, this, project)

    /** Called by [SessionTerminals] with this pane's view of the process. */
    fun show(view: TerminalView) {
        this.view = view
        replace(CenteredTerminal(view.component, { EditorGrid(view) }) { MyAirSettings.getInstance().terminalColumns })
    }

    /** Called by [SessionTerminals] when another pane took the process, as a second split of this one does. */
    fun showElsewhere() {
        view = null
        replace(notice("This session shows in another tab.", listOf("Show Here" to ::attach)))
    }

    private fun replace(component: JComponent) {
        content.removeAll()
        content.add(component)
        content.revalidate()
        content.repaint()
    }

    /** Types [text] into `claude`'s prompt; false while the pane shows no view of it. */
    fun type(text: String): Boolean {
        val view = view ?: return false
        view.sendText(text)
        return true
    }

    /** Sends [text] to the process as a message, pasted whole (so its lines stay one message) and submitted; false when the pane shows no process. */
    fun send(text: String): Boolean {
        val view = view ?: return false
        view.createSendTextBuilder().useBracketedPasteMode().shouldExecute().send(text)
        return true
    }

    /** What a restored tab shows instead of starting: its directory is gone, or its session already runs elsewhere. */
    private fun restoreBlocker(): JComponent? {
        if (!file.cwd.isDirectory()) return notice("Session directory no longer exists:\n${file.cwd}")
        val live = LiveSessionRegistry(Homes.claude.resolve("sessions")).read()[file.sessionId]
        // A background job's tab attaches to it ([SessionTerminals.start]).
        if (live == null || live.job != null) return null
        val running = RunningElsewhere(file.title, live, file.cwd)
        val switch = running.host?.let { "Switch to Window" to { SessionHosts.bringToFront(it) } }
        return notice(running.message, listOfNotNull(switch, "Open Anyway" to ::openAnyway))
    }

    private fun openAnyway() {
        attach()
        IdeFocusManager.getInstance(project).requestFocus(preferredFocusedComponent, true)
    }

    private fun notice(message: String, buttons: List<Pair<String, () -> Unit>> = emptyList()) = JPanel(GridBagLayout()).apply {
        add(panel {
            row { text(StringUtil.escapeXmlEntities(message).replace("\n", "<br>")) }
            if (buttons.isNotEmpty()) row { for ((label, action) in buttons) button(label) { action() } }
        })
    }

    override fun dispose() = SessionTerminals.getInstance().release(file, this)
}
