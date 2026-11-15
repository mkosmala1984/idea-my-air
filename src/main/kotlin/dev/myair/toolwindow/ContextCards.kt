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
        border = JBUI.Borders.empty(2, 4)
        add(content, BorderLayout.CENTER)
    }

    // The card never asks for more width than the pane gives it: what does not fit inside is the content's to wrap or shorten.
    override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)

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

/** A label in the grey of a DSL comment. */
fun commentLabel(text: String) = JBLabel(text).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }

/**
 * [parts] side by side at their preferred widths. Short of width, the last gives way first, down to nothing, then the
 * one before it; a label or link that gives way ends in an ellipsis.
 */
class ShrinkingLine(parts: List<JComponent>) : JPanel(null) {
    init {
        isOpaque = false
        parts.forEach(::add)
    }

    override fun getPreferredSize() = Dimension(components.sumOf { it.preferredSize.width }, components.maxOf { it.preferredSize.height })

    override fun getMinimumSize() = Dimension(0, preferredSize.height)

    override fun doLayout() {
        val widths = components.map { it.preferredSize.width }.toIntArray()
        var excess = widths.sum() - width
        for (i in widths.indices.reversed()) {
            val cut = minOf(maxOf(excess, 0), widths[i])
            widths[i] -= cut
            excess -= cut
        }
        var x = 0
        components.forEachIndexed { i, part ->
            val h = part.preferredSize.height
            part.setBounds(x, (height - h) / 2, widths[i], h)
            x += widths[i]
        }
    }
}

/**
 * The indexes of [widths] grouped into rows of at most [available] wide with [gap] between neighbours, each row filled before
 * the next is started; a part wider than [available] stands alone in a row.
 */
fun wrapRows(widths: List<Int>, available: Int, gap: Int): List<List<Int>> {
    val rows = ArrayList<MutableList<Int>>()
    var used = 0
    widths.forEachIndexed { i, w ->
        if (rows.isEmpty() || used + gap + w > available) {
            rows.add(mutableListOf(i))
            used = w
        } else {
            rows.last().add(i)
            used += gap + w
        }
    }
    return rows
}

/**
 * [parts] side by side, wrapping to the next row when the line is too narrow, so nothing is cut off at the right; a part wider
 * than the line is shortened to it (a label ends in an ellipsis). It has no minimum width, only a height that follows its width.
 */
class WrapLine(parts: List<JComponent>, private val hgap: Int = JBUI.scale(10), private val vgap: Int = JBUI.scale(2)) : JPanel(null) {
    private var laidOutHeight = -1

    init {
        isOpaque = false
        parts.forEach(::add)
    }

    private fun rows(available: Int) = wrapRows(components.map { it.preferredSize.width }, available, hgap)

    private fun heightOf(rows: List<List<Int>>) =
        rows.sumOf { row -> row.maxOf { components[it].preferredSize.height } } + vgap * (rows.size - 1)

    override fun getPreferredSize() = Dimension(
        components.sumOf { it.preferredSize.width } + hgap * (componentCount - 1),
        heightOf(rows(if (width > 0) width else Int.MAX_VALUE)),
    )

    override fun getMinimumSize() = Dimension(0, preferredSize.height)

    override fun doLayout() {
        val rows = rows(width)
        var y = 0
        for (row in rows) {
            val rowHeight = row.maxOf { components[it].preferredSize.height }
            var x = 0
            for (i in row) {
                val part = components[i]
                val w = minOf(part.preferredSize.width, width)
                part.setBounds(x, y + (rowHeight - part.preferredSize.height) / 2, w, part.preferredSize.height)
                x += w + hgap
            }
            y += rowHeight + vgap
        }
        // The parent's layout asked for the height of the width it had before; a different one means it must ask again.
        val height = heightOf(rows)
        if (height != laidOutHeight) {
            laidOutHeight = height
            revalidate()
        }
    }
}

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
