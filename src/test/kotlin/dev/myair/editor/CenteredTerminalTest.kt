package dev.myair.editor

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder

class CenteredTerminalTest {
    // A scroll pane with a scroll bar inside a border stands for the terminal's editor and what the IDE puts around its text.
    private val text = JScrollPane(JPanel(), ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    private val terminal = JPanel(BorderLayout()).apply {
        border = EmptyBorder(0, 7, 0, 13)
        add(text)
    }
    private val grid = object : TextGrid {
        override val visibleWidth get() = text.viewport.width
        override val charWidth = 7.7f
        override val background: Color = Color.BLACK
    }
    private var columns = 100
    private val centered = CenteredTerminal(terminal, { grid }) { columns }

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

    /** What the reworked terminal's editor counts (`CharacterGridImpl.getColumns`). */
    private fun shownColumns() = (grid.visibleWidth / grid.charWidth).toInt()

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
