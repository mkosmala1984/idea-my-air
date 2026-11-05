package dev.myair.editor

import java.awt.Color
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.ceil

/** The text area of a terminal, which shows as many columns as [charWidth] fits into [visibleWidth], rounded down. */
interface TextGrid {
    val visibleWidth: Int
    val charWidth: Float
    val background: Color
}

/** Lays [terminal] out [columns] columns wide in the middle, or across the whole width when [columns] gives 0. */
class CenteredTerminal(private val terminal: JComponent, private val grid: () -> TextGrid, private val columns: () -> Int) : JPanel(null) {
    init {
        add(terminal)
    }

    override fun doLayout() {
        val wanted = columns()
        if (wanted == 0) return terminal.setBounds(0, 0, width, height)
        // Half a pixel over the exact width keeps float rounding in the terminal's division from losing the last column.
        val text = ceil(wanted * grid().charWidth + 0.5f).toInt()
        // The first pass lays the terminal out, so the second knows how much its scroll bar and borders take.
        repeat(2) {
            val w = minOf(width, text + terminal.width - grid().visibleWidth)
            terminal.setBounds((width - w) / 2, 0, w, height)
            terminal.validate()
        }
    }

    override fun paintComponent(g: Graphics) {
        g.color = grid().background
        g.fillRect(0, 0, width, height)
    }
}
