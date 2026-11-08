package dev.myair.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.editor.CLAUDE_ORANGE
import dev.myair.editor.projectColor
import dev.myair.editor.sessionIcon
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListCellRenderer
import kotlin.math.max
import kotlin.math.min

/** The background of a pinned row whose session needs input; the list UI paints it under the renderer. */
val NEEDS_INPUT_TINT = JBColor(0xFFF6E0, 0x3A3222)

private val NEEDS_INPUT_TEXT = JBColor(0x8A5A00, 0xE3B656)
private val BAR_TRACK = JBColor(0xE4E6EB, 0x45474D)

/**
 * Where a pinned row's two lines go for the list's [font] and [density]: [pad] above and below, the first line as high as
 * the icon or the font, the second in the [small] font. [height] is the whole row's.
 */
class PinnedLayout(font: Font, val density: Density, metrics: (Font) -> FontMetrics) {
    val small: Font = font.deriveFont(font.size2D - JBUIScale.scale(1f))
    val pad = JBUI.scale(if (density == Density.COMPACT) 5 else 8)
    val first = max(JBUI.scale(density.iconSize), metrics(font).height)
    val second = metrics(small).height
    val gap = JBUI.scale(2)
    val height = 2 * pad + first + gap + second

    /** The first line of the row at [r], where [SessionTrailer] paints the age and the buttons. */
    fun firstLine(r: Rectangle) = Rectangle(r.x, r.y + pad, r.width, first)
}

/** One line of text; as a renderer's part it must not ask for repaints or layout, which would repaint the list on every paint. */
private class Line : SimpleColoredComponent() {
    init {
        isOpaque = false
        ipad = JBUI.emptyInsets()
        border = JBUI.Borders.empty()
    }

    override fun repaint(tm: Long, x: Int, y: Int, width: Int, height: Int) {}

    override fun revalidate() {}
}

/**
 * A session in the pinned list, in two lines: its [sessionIcon] and title (the age [SessionTrailer] adds), then what
 * [secondLine] chooses by its status, the project in its colour and a bar of the brief's progress while it works.
 * A needing-input row's tint comes from the list ([TrailerList]), so selection and hover still paint over it.
 */
class PinnedRenderer(
    private val liveOf: (ClaudeSession) -> LiveSession?, private val settled: (ClaudeSession) -> Boolean, private val unread: (ClaudeSession) -> Boolean,
    private val branchOf: (ClaudeSession) -> String?, private val rows: () -> PinnedLayout, private val repaintList: () -> Unit,
) : JComponent(), ListCellRenderer<ClaudeSession> {
    private val title = Line()
    private val detail = Line()
    private var icon: Icon? = null
    private var progress: Double? = null
    private var current: PinnedLayout? = null

    init {
        add(title)
        add(detail)
    }

    override fun getListCellRendererComponent(list: JList<out ClaudeSession>, value: ClaudeSession, index: Int, selected: Boolean, hasFocus: Boolean): Component {
        val l = rows().also { current = it }
        val live = liveOf(value)
        val unread = unread(value)
        icon = l.density.grow(sessionIcon(live?.status, unread))
        title.clear()
        title.font = list.font
        title.append(value.title, titleAttributes(live, settled(value), unread))
        detail.clear()
        detail.font = l.small
        progress = null
        when (val line = secondLine(value, live, branchOf(value))) {
            is SecondLine.NeedsInput -> detail.append(line.text, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, NEEDS_INPUT_TEXT))
            is SecondLine.Place -> {
                val color = projectColor(line.project, repaintList) ?: SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
                detail.append(projectName(line.project), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
                line.rest.forEach { detail.append(" · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                progress = line.progress
            }
        }
        // The second line names the project.
        toolTipText = sessionTooltip(value, live, unread, settled(value), project = null)
        return this
    }

    override fun getPreferredSize() = Dimension(0, current!!.height)

    override fun repaint(tm: Long, x: Int, y: Int, width: Int, height: Int) {}

    override fun revalidate() {}

    override fun paintComponent(g: Graphics) {
        val l = current!!
        val left = JBUI.scale(LEFT)
        val x = left + JBUI.scale(l.density.iconSize + l.density.iconTextGap)
        val width = width - x - JBUI.scale(RIGHT)
        val secondY = l.pad + l.first + l.gap
        val bar = progress?.let { JBUI.scale(BAR_WIDTH) }
        title.setBounds(x, l.pad, width, l.first)
        detail.setBounds(x, secondY, max(0, min(detail.preferredSize.width, width - (bar?.plus(JBUI.scale(BAR_GAP)) ?: 0))), l.second)
        icon!!.let { it.paintIcon(this, g, left, l.pad + (l.first - it.iconHeight) / 2) }
        val done = progress ?: return
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val h = JBUI.scale(BAR_HEIGHT)
            val bx = x + detail.width + JBUI.scale(BAR_GAP)
            val by = secondY + (l.second - h) / 2
            g2.color = BAR_TRACK
            g2.fillRoundRect(bx, by, bar!!, h, h, h)
            g2.color = CLAUDE_ORANGE
            g2.fillRoundRect(bx, by, (bar * done).toInt(), h, h, h)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /** As [SessionTrailer]'s pad on the right. */
        const val LEFT = 12
        const val RIGHT = 12
        const val BAR_WIDTH = 40
        const val BAR_HEIGHT = 3
        const val BAR_GAP = 8
    }
}
