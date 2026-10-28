package dev.myair.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.GraphicsUtil
import dev.myair.claude.LiveSession
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import javax.swing.Icon

/** A tree row's status: an 8 px filled circle, or with [ring] a 10 px circle with a quarter left open. */
class StatusDot(private val color: JBColor, private val ring: Boolean = false) : Icon {
    override fun getIconWidth() = JBUIScale.scale(16)

    override fun getIconHeight() = JBUIScale.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = color
            val cx = x + iconWidth / 2.0
            val cy = y + iconHeight / 2.0
            if (ring) {
                val r = JBUIScale.scale(5f).toDouble()
                g2.stroke = BasicStroke(JBUIScale.scale(1.6f))
                g2.draw(Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90.0, 270.0, Arc2D.OPEN))
            } else {
                val r = JBUIScale.scale(4f).toDouble()
                g2.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
            }
        } finally {
            g2.dispose()
        }
    }
}

val NEEDS_INPUT_DOT = StatusDot(JBColor(0xE56D17, 0xF0883E))
val UNREAD_DOT = StatusDot(JBColor(0x3574F0, 0x548AF7))
val WORKING_DOT = StatusDot(JBColor(0x3574F0, 0x548AF7), ring = true)
val IDLE_DOT = StatusDot(JBColor(0xC9CCD6, 0x5A5D63))

val Urgency.dot: Icon get() = when (this) {
    Urgency.NEEDS_INPUT -> NEEDS_INPUT_DOT
    Urgency.UNREAD -> UNREAD_DOT
    Urgency.WORKING -> WORKING_DOT
}

/** A session without a live process: no dot, but its room, so the titles of a folder line up. */
private val NO_DOT: Icon = EmptyIcon.create(16)

/** The dot of a session row: the idle one for a live session that calls for nothing. */
fun dotOf(live: LiveSession?, unread: Boolean): Icon = live?.let { urgencyOf(it, unread)?.dot ?: IDLE_DOT } ?: NO_DOT
