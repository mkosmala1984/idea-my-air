package dev.myair.toolwindow

import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel

private val CARD_BACKGROUND = JBColor.namedColor("Editor.background", JBColor(0xFFFFFF, 0x1E1F22))
private val CHIP_BACKGROUND = JBColor(0xEBECF0, 0x393B40)

/** A white rounded card without an outline holding [content], as the context pane lays out each of its sections. */
class ContextCard(content: JComponent) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        add(content, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(ARC)
            g2.color = CARD_BACKGROUND
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val ARC = 16
    }
}

/**
 * A cell that gives up width when its row is wider than the pane, down to nothing, so the cells after it stay in view;
 * a label or link then ends in an ellipsis. It takes the row's spare width, so the cells after it go to the right edge.
 */
fun <T : JComponent> Cell<T>.shrinkable(): Cell<T> =
    resizableColumn().align(AlignX.LEFT).applyToComponent { minimumSize = Dimension(0, minimumSize.height) }

/** A small grey rounded label: the model and effort under a session's title. */
class Chip(text: String) : JBLabel(text) {
    init {
        isOpaque = false
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(1, 6)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(8)
            g2.color = CHIP_BACKGROUND
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** The share of added and removed lines, as one bar: added green from the left, removed red after it. */
class ChangeBar(private val added: Int, private val removed: Int) : JComponent() {
    init {
        require(added + removed > 0) { "No lines changed" }
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(120), JBUI.scale(6))

    override fun getMinimumSize() = Dimension(0, JBUI.scale(6))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val h = JBUI.scale(6)
            val y = (height - h) / 2
            g2.clip(RoundRectangle2D.Float(0f, y.toFloat(), width.toFloat(), h.toFloat(), h.toFloat(), h.toFloat()))
            val split = width * added / (added + removed)
            g2.color = FileStatus.ADDED.color
            g2.fillRect(0, y, split, h)
            g2.color = FileStatus.DELETED.color
            g2.fillRect(split, y, width - split, h)
        } finally {
            g2.dispose()
        }
    }
}
