package dev.myair.toolwindow

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.BadgeDotProvider
import com.intellij.ui.BadgeIcon
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.editor.CLAUDE_GREY_ICON
import dev.myair.editor.CLAUDE_ICON
import dev.myair.editor.icon
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

private val BLUE = JBColor(0x3574F0, 0x548AF7)

val NEEDS_INPUT_DOT = StatusDot(JBColor(0xE56D17, 0xF0883E))
val UNREAD_DOT = StatusDot(BLUE)
val WORKING_DOT = StatusDot(BLUE, ring = true)

val Urgency.dot: Icon get() = when (this) {
    Urgency.NEEDS_INPUT -> NEEDS_INPUT_DOT
    Urgency.UNREAD -> UNREAD_DOT
    Urgency.WORKING -> WORKING_DOT
}

/** An idle session with an unread reply: the asterisk with the blue dot of [UNREAD_DOT], inside its 16 px so the title stays put. */
val UNREAD_SESSION_ICON: Icon = BadgeIcon(CLAUDE_ICON, BLUE, BadgeDotProvider(x = 0.75, y = 0.25, radius = 0.15))

/**
 * A session row's one icon, its status: grey asterisk without a process, the spinner while it works, the tabs' warning
 * when it needs input, the asterisk with a blue dot when its reply is unread, else the orange asterisk.
 */
fun sessionIcon(live: LiveSession?, unread: Boolean): Icon = when (live?.status) {
    null -> CLAUDE_GREY_ICON
    SessionStatus.WORKING -> AnimatedIcon.Default.INSTANCE
    SessionStatus.NEEDS_INPUT -> SessionStatus.NEEDS_INPUT.icon
    SessionStatus.IDLE -> if (unread) UNREAD_SESSION_ICON else CLAUDE_ICON
}
