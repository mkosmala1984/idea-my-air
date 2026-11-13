package dev.myair.editor

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.myair.claude.ClaudeSession
import dev.myair.toolwindow.briefCount
import dev.myair.toolwindow.projectName
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.JPanel

/** What the bar above a session tab's terminal says: the session's [title], [project] and [branch], what it is for ([about]) and its brief's [count]. */
data class HeaderText(val title: String, val project: Path, val branch: String?, val about: String?, val count: String?)

/** [about] is the brief's goal, else its task in progress (a Desktop plan has no goal). */
fun headerText(session: ClaudeSession, branch: String?) = HeaderText(
    session.title, session.project, branch, session.brief?.let { it.goal ?: it.current?.content }, briefCount(session.brief),
)

/** The bar above a session tab's terminal, so the tab says which session it shows and what that session is for; [actions] are buttons at its right. */
class SessionHeader(actions: List<AnAction>, private val repaintLater: () -> Unit) : JPanel(BorderLayout()) {
    private val first = SimpleColoredComponent()
    private val second = SimpleColoredComponent()

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 12))
        listOf(first, second).forEach {
            it.isOpaque = false
            it.ipad = JBUI.emptyInsets()
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(first, BorderLayout.NORTH)
            add(second, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            actions.forEach { add(ActionButton(it, it.templatePresentation.clone(), PLACE, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)) }
        }
        // Kept at the top, beside the title, when the goal adds a second line.
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(buttons, BorderLayout.NORTH) }, BorderLayout.EAST)
    }

    private companion object {
        const val PLACE = "MyAirSessionHeader"
    }

    fun show(text: HeaderText) {
        first.clear()
        first.font = JBFont.label().biggerOn(1f).asBold()
        first.append(text.title)
        val color = projectColor(text.project, repaintLater) ?: SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
        first.append("   " + projectName(text.project), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
        listOfNotNull(text.branch, text.count).forEach { first.append(" · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        second.clear()
        second.isVisible = text.about != null
        text.about?.let { second.append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        toolTipText = text.about
        revalidate()
        repaint()
    }
}
