package dev.myair.editor

import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.swing.JFrame
import javax.swing.border.EmptyBorder

class CenteredTerminalTest {
    private val settings = DefaultSettingsProvider()
    // The border stands for what the IDE's terminal adds around the text, which the layout has to measure.
    private val terminal = JediTermWidget(settings).apply { border = EmptyBorder(0, 7, 0, 13) }
    private var columns = 100
    private val centered = CenteredTerminal(terminal, terminal.terminalPanel, settings) { columns }

    // Swing validates only components with peers; addNotify makes them without showing the frame.
    private val frame = JFrame().apply {
        addNotify()
        contentPane.layout = null
    }

    @AfterEach
    fun dispose() = frame.dispose()

    /** The first call adds the terminal already sized, so its first layout is at that width, as in a new tab. */
    private fun layOut(width: Int) {
        centered.setSize(width, 800)
        if (centered.parent == null) frame.contentPane.add(centered)
        centered.validate()
    }

    private fun shownColumns() = terminal.terminalPanel.terminalSizeFromComponent!!.columns

    @Test
    fun `the terminal gets the set number of columns in the middle of a wider tab`() {
        layOut(3000)
        assertEquals(100, shownColumns())
        assertEquals((3000 - terminal.width) / 2, terminal.x)
    }

    @Test
    fun `a narrower tab gives the terminal all its width`() {
        layOut(400)
        assertEquals(0, terminal.x)
        assertEquals(400, terminal.width)
    }

    @Test
    fun `0 columns fill the tab and a new setting applies on the next layout`() {
        columns = 0
        layOut(3000)
        assertEquals(3000, terminal.width)
        columns = 80
        centered.invalidate()
        layOut(3000)
        assertEquals(80, shownColumns())
    }
}
