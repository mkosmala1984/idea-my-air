package dev.myair.planview

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.myair.prompt.CARD
import dev.myair.prompt.Rounded
import dev.myair.toolwindow.PaneFont
import dev.myair.toolwindow.commentLabel
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/* The parts the plan and review panels are built of, in the composer's look. */

/** The gap between the parts of a head. */
internal const val HEAD_GAP = 8
internal const val HEAD_INSET = 2
/** Where a head's id starts: after its inset, its lead (a button's width) and a gap. A milestone's description and a section's items start there too. */
internal const val ID_COLUMN = HEAD_INSET + 22 + HEAD_GAP
private const val CARD_ARC = 16
private const val PLACE = "MyAirPlanView"

internal val PAGE = JBColor.namedColor("Editor.background", JBColor(0xFFFFFF, 0x1E1F22))

/**
 * A page of cards in a scroll pane: it takes the width of its scroll pane, never scrolling sideways, and at least its
 * height, so its background fills it; it builds itself again, [render] forced, when the pane font or the theme changes.
 */
abstract class CardPage(parent: Disposable) : JPanel(BorderLayout()), Scrollable {
    internal var colors = MarkdownColors.current()
        private set

    init {
        background = PAGE
        border = JBUI.Borders.empty(12)
        PaneFont.subscribe(parent) { render(force = true) }
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            colors = MarkdownColors.current()
            render(force = true)
        })
    }

    protected abstract fun render(force: Boolean = false)

    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } == true
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
}

internal fun card(init: Panel.() -> Unit) = panel(init).apply { isOpaque = false }

/** A holder of one card, swapped in place: [com.intellij.ui.components.panels.VerticalLayout] puts a component added again last. */
internal fun holder(card: JComponent) = JPanel(BorderLayout()).apply { isOpaque = false; add(card) }

internal fun chevron(unfolded: Boolean) =
    JBLabel(if (unfolded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight).apply { preferredSize = ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE }

/** A section's or milestone's grey rounded card holding [rows]. */
internal fun greyCard(rows: JComponent) = Rounded(CARD, JBUI.scale(CARD_ARC), squareTop = false).apply {
    layout = BorderLayout()
    border = JBUI.Borders.empty(4, 8)
    add(rows)
}

/**
 * A head on one line: [lead] and [label], then [title] taking the width left, then [trail]; a click on what takes none
 * runs [click]. Plain Swing, not a DSL row, so it is as high as its tallest part, and a task's is alike in place and size
 * folded or not: a fold moves nothing but what comes after it.
 */
internal fun head(lead: JComponent, title: JComponent, trail: List<JComponent>, label: String?, click: () -> Unit): JComponent = Hover(click).apply {
    border = JBUI.Borders.empty(3, HEAD_INSET)
    add(group(listOfNotNull(lead, label?.let(::commentLabel))), BorderLayout.WEST)
    add(title)
    add(group(trail), BorderLayout.EAST)
}

/** [parts] side by side at their preferred widths, centred vertically. */
internal fun group(parts: List<JComponent>) = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    parts.forEachIndexed { i, part ->
        if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(HEAD_GAP)))
        add(part)
    }
}

/**
 * [actions] as buttons drawn at once. A toolbar would add its buttons on its first update, later, and so change the
 * width of a row just rebuilt: the row would jump.
 */
internal fun buttons(vararg actions: AnAction): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
    isOpaque = false
    actions.forEach { add(ActionButton(it, it.templatePresentation.clone(), PLACE, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)) }
}

/** Lets the text be selected and copied: a DSL text cell is not focusable, and a text component unfocused shows no selection. */
internal fun <T : JEditorPane> Cell<T>.selectable() = applyToComponent { isFocusable = true }

/** [markdown] rendered, else [empty] in grey (nothing when null); [area] with Save and Cancel while an edit of it is open. */
internal fun Panel.markdownOrArea(area: JTextComponent?, markdown: String?, empty: String?, colors: MarkdownColors, save: () -> Unit, cancel: () -> Unit) {
    if (area != null) {
        row { cell(JBScrollPane(area)).align(AlignX.FILL) }
        row {
            button("Save") { save() }
            button("Cancel") { cancel() }
        }
    } else if (markdown != null) row { text(markdownHtml(markdown, colors), MAX_LINE_LENGTH_WORD_WRAP).selectable() }
    else if (empty != null) row { comment(empty) }
}

/** The `⋯` menu. */
internal fun more(vararg actions: AnAction) = DefaultActionGroup("More", actions.toList()).apply {
    isPopup = true
    templatePresentation.icon = AllIcons.Actions.More
    // The dots say a menu opens; the button's corner arrow for a popup group would say it twice.
    templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
}

internal fun action(text: String, icon: Icon? = null, run: () -> Unit) = DumbAwareAction.create(text, icon) { run() }

/** An action enabled only while [enabled] says so, asked whenever its menu or button updates. */
internal fun action(text: String, enabled: () -> Boolean, run: () -> Unit): AnAction = object : DumbAwareAction(text) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
    override fun actionPerformed(e: AnActionEvent) = run()
}

/**
 * An action disabled while [problem] gives a reason, which is then its description: [TooltipDescriptionProvider] makes
 * an `ActionButton` show it in its tooltip.
 */
internal fun actionUnless(text: String, problem: () -> String?, run: () -> Unit): AnAction = object : DumbAwareAction(text), TooltipDescriptionProvider {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val reason = problem()
        e.presentation.isEnabled = reason == null
        e.presentation.description = reason
    }
    override fun actionPerformed(e: AnActionEvent) = run()
}

/** A row with a rounded hover background that runs [click]; it gets the clicks of the components in it that take none. */
internal class Hover(private val click: () -> Unit) : JPanel(BorderLayout(JBUI.scale(HEAD_GAP), 0)) {
    private var hover = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) = click()
        })
    }

    override fun paintComponent(g: Graphics) {
        if (!hover) return
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
        } finally {
            g2.dispose()
        }
    }
}

/**
 * A title whose text can be selected and copied, on one line: a text field, as an editor pane wraps it to any width it
 * is given, down to a letter a line. A click that selects nothing, and the pointer coming and going, are passed
 * to the [Hover] around it, so the row folds and lights up as over a plain label.
 */
internal class SelectableTitle(text: String) : JTextField(text) {
    init {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        font = JBUI.Fonts.label()
        foreground = JBUI.CurrentTheme.Label.foreground()
        putClientProperty("caretWidth", 0)
        // The caret starts at the end, so a title wider than its field would show its end and hide its start.
        caretPosition = 0
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = toHover(e)
            override fun mouseExited(e: MouseEvent) = toHover(e)
            override fun mouseClicked(e: MouseEvent) { if (selectionStart == selectionEnd) toHover(e) }
        })
    }

    private fun toHover(e: MouseEvent) {
        // None once a fold rebuilt the card under the pointer: AWT may still send the removed title its exit.
        val hover = SwingUtilities.getAncestorOfClass(Hover::class.java, this) ?: return
        hover.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, hover))
    }
}
