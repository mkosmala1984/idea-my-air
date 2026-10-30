package dev.myair.toolwindow

import com.intellij.ide.ui.UISettings
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.myair.claude.ClaudeSession
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** The age of [at] at [now] as a session row shows it: now, 5m, 13h, 3d, 2w, 1y. */
fun shortAge(at: Instant, now: Instant): String {
    val age = Duration.between(at, now)
    return when {
        age < Duration.ofMinutes(1) -> "now"
        age < Duration.ofHours(1) -> "${age.toMinutes()}m"
        age < Duration.ofDays(1) -> "${age.toHours()}h"
        age < Duration.ofDays(7) -> "${age.toDays()}d"
        age < Duration.ofDays(365) -> "${age.toDays() / 7}w"
        else -> "${age.toDays() / 365}y"
    }
}

/** A toggle drawn at the end of a hovered session row; [on] gives it a pressed background. */
class TrailerButton(val icon: Icon, val on: (ClaudeSession) -> Boolean, val toggle: (ClaudeSession) -> Unit)

/**
 * The right end of each session row in [component]: the age of its last message, replaced by [buttons] while the pointer
 * is over the row; of each group row, its [summary]. It is painted over the rows, since a tree cell renderer is only as wide as its text, and covers a title
 * running underneath it with the row's [background]. The component calls [paint] after painting itself.
 */
class SessionTrailer(
    private val component: JComponent,
    private val rowAt: (Point) -> Int,
    private val bounds: (row: Int) -> Rectangle?,
    private val session: (row: Int) -> ClaudeSession?,
    private val summary: (row: Int) -> FolderSummary?,
    private val background: (row: Int) -> Color,
    private val buttons: List<TrailerButton>,
) : MouseAdapter() {
    private var pointer: Point? = null

    init {
        component.addMouseListener(this)
        component.addMouseMotionListener(this)
    }

    fun paint(g: Graphics) {
        val clip = g.clipBounds ?: component.visibleRect
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            UISettings.setupAntialiasing(g2)
            var row = rowAt(Point(0, clip.y))
            while (row >= 0) {
                val r = bounds(row) ?: break
                if (r.y > clip.maxY) break
                session(row)?.let { paintRow(g2, row, r, it) } ?: summary(row)?.let { paintSummary(g2, row, r, it) }
                row++
            }
        } finally {
            g2.dispose()
        }
    }

    /** True over a button, where a click must not also open the session. */
    fun isButton(p: Point) = buttonAt(p) != null

    override fun mouseMoved(e: MouseEvent) = movePointer(e.point)

    override fun mouseDragged(e: MouseEvent) = movePointer(null)

    override fun mouseExited(e: MouseEvent) = movePointer(null)

    override fun mouseClicked(e: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        val (button, session) = buttonAt(e.point) ?: return
        button.toggle(session)
        e.consume()
    }

    private fun movePointer(p: Point?) {
        listOfNotNull(pointer, p).forEach { at -> hoveredRow(at)?.let { bounds(it) }?.let { component.repaint(0, it.y, component.width, it.height) } }
        pointer = p
    }

    /** The session row under [p]; a tree row is only as wide as its text, so only the height counts. */
    private fun hoveredRow(p: Point): Int? {
        val row = rowAt(p)
        val r = bounds(row) ?: return null
        return row.takeIf { p.y >= r.y && p.y < r.y + r.height && session(it) != null }
    }

    private fun buttonAt(p: Point): Pair<TrailerButton, ClaudeSession>? {
        val row = hoveredRow(p) ?: return null
        val index = buttonRects(bounds(row)!!).indexOfFirst { it.contains(p) }
        return if (index < 0) null else buttons[index] to session(row)!!
    }

    private fun right() = component.visibleRect.let { it.x + it.width } - JBUI.scale(PAD)

    /** Right-aligned, in the order of [buttons]. */
    private fun buttonRects(r: Rectangle): List<Rectangle> {
        val size = JBUI.scale(BUTTON)
        val left = right() - buttons.size * size
        return buttons.indices.map { Rectangle(left + it * size, r.y + (r.height - size) / 2, size, size) }
    }

    private fun paintRow(g: Graphics2D, row: Int, r: Rectangle, session: ClaudeSession) {
        val hovered = pointer?.let(::hoveredRow) == row
        if (hovered) {
            val rects = buttonRects(r)
            cover(g, row, r, rects.first().x)
            val arc = JBUI.scale(6)
            buttons.zip(rects).forEach { (button, rect) ->
                val fill = when {
                    rect.contains(pointer!!) -> JBUI.CurrentTheme.ActionButton.hoverBackground()
                    button.on(session) -> JBUI.CurrentTheme.ActionButton.pressedBackground()
                    else -> null
                }
                fill?.let {
                    g.color = it
                    g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)
                }
                button.icon.paintIcon(component, g, rect.x + (rect.width - button.icon.iconWidth) / 2, rect.y + (rect.height - button.icon.iconHeight) / 2)
            }
        } else {
            val text = shortAge(session.lastMessage, Instant.now())
            val metrics = component.getFontMetrics(component.font)
            val x = right() - metrics.stringWidth(text)
            cover(g, row, r, x)
            g.font = component.font
            g.color = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            g.drawString(text, x, r.y + (r.height - metrics.height) / 2 + metrics.ascent)
        }
    }

    /** The session count, right-aligned, after the dot of the most urgent session. */
    private fun paintSummary(g: Graphics2D, row: Int, r: Rectangle, summary: FolderSummary) {
        val text = summary.sessions.toString()
        val metrics = component.getFontMetrics(component.font)
        val textX = right() - metrics.stringWidth(text)
        val dot = summary.urgent?.dot
        val x = dot?.let { textX - JBUI.scale(DOT_GAP) - it.iconWidth } ?: textX
        cover(g, row, r, x)
        dot?.paintIcon(component, g, x, r.y + (r.height - dot.iconHeight) / 2)
        g.font = component.font
        g.color = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
        g.drawString(text, textX, r.y + (r.height - metrics.height) / 2 + metrics.ascent)
    }

    /** Hides the part of the title that runs under the trailer starting at [x]. */
    private fun cover(g: Graphics2D, row: Int, r: Rectangle, x: Int) {
        val gap = JBUI.scale(GAP)
        g.color = background(row)
        g.fillRect(x - gap, r.y, right() - x + gap, r.height)
    }

    private companion object {
        /** Clear of the rounded corners of a selected or hovered row. */
        const val PAD = 12
        const val GAP = 8
        const val BUTTON = 22
        /** Beside the room the 16 px dot icon leaves around its dot. */
        const val DOT_GAP = 2
    }
}
