package dev.myair.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/** [text], a background task's command or prompt, in full, to read, select and copy. */
class CommandDialog(project: Project, title: String, private val text: String) : DialogWrapper(project) {
    init {
        this.title = title
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            // The area's font is already scaled; JBUI.Fonts.create would scale it again.
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size + JBUI.scale(FONT_GROWTH))
            caretPosition = 0
        }
        return JBScrollPane(area).apply { preferredSize = JBUI.size(720, 400) }
    }

    override fun createActions(): Array<Action> = arrayOf(object : AbstractAction("Copy") {
        override fun actionPerformed(e: ActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(text))
    }, okAction)

    private companion object {
        /** Points added to the UI font: a command reads like code, not like a label. */
        const val FONT_GROWTH = 3
    }
}
