package dev.myair.editor

import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JPanel

/** Lays [terminal] out [columns] columns wide in the middle, or across the whole width when [columns] gives 0; [panel] is its text area. */
class CenteredTerminal(
    private val terminal: JComponent,
    private val panel: TerminalPanel,
    private val settings: SettingsProvider,
    private val columns: () -> Int,
) : JPanel(null) {
    init {
        add(terminal)
    }

    override fun doLayout() {
        val wanted = columns()
        if (wanted == 0) return terminal.setBounds(0, 0, width, height)
        // TerminalPanel counts columns as (width - getInsetX()) / charWidth('W') of the terminal font; getInsetX is a protected 4.
        val text = wanted * panel.getFontMetrics(settings.terminalFont).charWidth('W') + 4
        // The first pass lays the terminal out, so the second knows how much its scroll bar and borders take.
        repeat(2) {
            val w = minOf(width, text + terminal.width - panel.width)
            terminal.setBounds((width - w) / 2, 0, w, height)
            terminal.validate()
        }
    }

    override fun paintComponent(g: Graphics) {
        g.color = panel.background
        g.fillRect(0, 0, width, height)
    }
}
