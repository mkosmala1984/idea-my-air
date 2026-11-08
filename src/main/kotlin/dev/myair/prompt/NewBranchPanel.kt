package dev.myair.prompt

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.myair.MyAirSettings
import dev.myair.actions.suggestBranchName
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Names a new branch off [base] in the composer's look: the task, or the Jira key, in the card, Suggest letting Haiku
 * name the branch from it into the bar below, where the name may also be typed. Enter in the task suggests, Enter in
 * the name or the send button gives [chosen] the name and the task, the session's first message (null when blank).
 */
private class NewBranchPanel(private val dir: Path, base: String, private val chosen: (String, String?) -> Unit) : JPanel(VerticalLayout(0)) {
    val task = JBTextArea(3, 60).apply {
        font = JBUI.Fonts.label().biggerOn(1f)
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = "The session's first message, or TR-1234; Enter suggests a branch name"
    }
    private val branchField = JBTextField().apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = "TR-1234-short-description"
    }
    private val suggestChip = Chip(AllIcons.Actions.Lightning) { suggest() }.apply {
        text = "Suggest"
        chevron = false
        toolTipText = "Haiku names the branch from the task, reading the Jira issue it names"
    }
    private val send = SendButton("Create the branch (Enter in its name)") { create() }
    private val error = JBLabel().apply { foreground = UIUtil.getErrorForeground(); isVisible = false }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)
        val card = Rounded(CARD, JBUI.scale(16), squareTop = false).apply {
            layout = BorderLayout(0, JBUI.scale(8))
            border = JBUI.Borders.empty(12, 14, 8, 10)
            add(JBScrollPane(task).apply { isOpaque = false; viewport.isOpaque = false; border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(row(suggestChip), BorderLayout.WEST)
                add(send, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        val bar = Rounded(BAR, JBUI.scale(12), squareTop = true).apply {
            layout = BorderLayout(JBUI.scale(6), 0)
            border = JBUI.Borders.empty(6, 12)
            add(JBLabel(AllIcons.Vcs.Branch), BorderLayout.WEST)
            add(branchField, BorderLayout.CENTER)
            add(JBLabel("off $base").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }
        add(card)
        add(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(0, 24); add(bar) })
        add(error)
        task.addKeyListener(enter { if (it.isShiftDown) task.replaceSelection("\n") else suggest() })
        branchField.addKeyListener(enter { create() })
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = maxOf(width, JBUI.scale(720)) }

    private fun enter(pressed: (KeyEvent) -> Unit) = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode != KeyEvent.VK_ENTER) return
            e.consume()
            pressed(e)
        }
    }

    private fun suggest() {
        if (!suggestChip.isEnabled) return
        suggestChip.isEnabled = false
        suggestChip.text = "Naming..."
        show(null)
        suggestInBackground(this, dir, task.text) { result ->
            suggestChip.isEnabled = true
            suggestChip.text = "Suggest"
            result.onSuccess { branchField.text = it; branchField.requestFocusInWindow() }
            show(result.exceptionOrNull()?.message)
        }
    }

    private fun create() {
        val branch = branchField.text.trim()
        if (branch.isEmpty()) return show("Name the branch, or let Suggest name it from the task")
        chosen(branch, task.text.trim().ifEmpty { null })
    }

    private fun show(message: String?) {
        error.text = message.orEmpty()
        error.isVisible = message != null
        revalidate()
        repaint()
    }
}

/** [NewBranchPanel] in a popup centred in the project's window; [chosen] gets the name and the first message, and the popup closes. */
fun showNewBranchPopup(project: Project, dir: Path, base: String, chosen: (String, String?) -> Unit) {
    lateinit var popup: JBPopup
    val panel = NewBranchPanel(dir, base) { branch, prompt -> popup.closeOk(null); chosen(branch, prompt) }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.task)
        .setRequestFocus(true).setResizable(true).setMovable(true).setCancelOnClickOutside(true)
        .createPopup()
    popup.showCenteredInCurrentWindow(project)
}

/** [suggestBranchName] off the EDT, its result given to [done] on the EDT in [component]'s modality. */
fun suggestInBackground(component: JComponent, dir: Path, text: String, done: (Result<String>) -> Unit) {
    val modality = ModalityState.stateForComponent(component)
    val tools = MyAirSettings.getInstance().jiraTools
    ApplicationManager.getApplication().executeOnPooledThread {
        val result = runCatching { suggestBranchName(dir, text, tools) }
        ApplicationManager.getApplication().invokeLater({ done(result) }, modality)
    }
}
