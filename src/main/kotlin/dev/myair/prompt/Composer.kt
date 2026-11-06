package dev.myair.prompt

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/* The parts of Air's composer look: a rounded card, the bar under it, flat chips and the round send button. */

internal val CARD = JBColor(0xEEF0F4, 0x2F3134)
internal val BAR = JBColor(0xE6E8EC, 0x292B2E)
internal val SEND = JBColor(0xD3D5DB, 0x4A4D52)

internal fun row(vararg chips: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
    isOpaque = false
    chips.forEach(::add)
}

/** A flat dropdown: icon, text and chevron, with a hover background; a click runs [click]. */
internal class Chip(icon: Icon?, private val click: (Chip) -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {
    private val label = JBLabel(icon)
    private val down = JBLabel(AllIcons.General.ChevronDown)
    private var hover = false
    var text: String
        get() = label.text
        set(value) { label.text = value }
    var icon: Icon?
        get() = label.icon
        set(value) { label.icon = value }
    var chevron: Boolean
        get() = down.isVisible
        set(value) { down.isVisible = value }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 6)
        add(label)
        add(down)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) click(this@Chip) }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        label.isEnabled = enabled
        down.isEnabled = enabled
    }

    override fun paintComponent(g: Graphics) {
        if (!hover || !isEnabled) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
        g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
        g2.dispose()
    }
}

/** A panel filled with [fill] and rounded, at the bottom only when [squareTop]. */
internal class Rounded(private val fill: JBColor, private val arc: Int, private val squareTop: Boolean) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // A square top is drawn above the panel, outside the clip.
        val y = if (squareTop) -arc else 0
        g2.color = fill
        g2.fillRoundRect(0, y, width - 1, height - 1 - y, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(0, y, width - 1, height - 1 - y, arc, arc)
        g2.dispose()
    }
}

/** The round send button with an up arrow. */
internal class SendButton(tooltip: String, click: () -> Unit) : JButton() {
    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        toolTipText = tooltip
        addActionListener { click() }
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(32), JBUI.scale(32))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = SEND
        g2.fillOval(0, 0, width - 1, height - 1)
        val (x, y, r) = Triple(width / 2, height / 2, JBUI.scale(6))
        g2.color = UIUtil.getLabelForeground()
        g2.stroke = BasicStroke(JBUI.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(x, y + r, x, y - r)
        g2.drawPolyline(intArrayOf(x - r + 1, x, x + r - 1), intArrayOf(y - 1, y - r, y - 1), 3)
        g2.dispose()
    }
}
