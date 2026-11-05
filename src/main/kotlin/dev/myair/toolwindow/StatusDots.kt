package dev.myair.toolwindow

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import dev.myair.editor.NEEDS_INPUT_AMBER
import dev.myair.editor.UNREAD_BLUE
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import javax.swing.Icon

/** A tree row's status: an 8 px filled circle, or with [ring] a 10 px circle with a quarter left open. */
class StatusDot(private val color: Color, private val ring: Boolean = false) : Icon {
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

/** The colours of the session icons ([dev.myair.editor.sessionIcon]), so a group's dot names the status its rows show. */
val NEEDS_INPUT_DOT = StatusDot(NEEDS_INPUT_AMBER)
val UNREAD_DOT = StatusDot(UNREAD_BLUE)
val WORKING_DOT = StatusDot(Color(0xD97757), ring = true)

val Urgency.dot: Icon get() = when (this) {
    Urgency.NEEDS_INPUT -> NEEDS_INPUT_DOT
    Urgency.UNREAD -> UNREAD_DOT
    Urgency.WORKING -> WORKING_DOT
}
