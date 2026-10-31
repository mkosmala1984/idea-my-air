package dev.myair.toolwindow

import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JComponent
import javax.swing.JFrame

class ContextCardsTest {
    // Swing validates only components with peers; addNotify makes them without showing the frame.
    private val frame = JFrame().apply {
        addNotify()
        contentPane.layout = null
    }

    @AfterEach
    fun dispose() = frame.dispose()

    private fun layOut(panel: JComponent, width: Int) {
        frame.contentPane.add(panel)
        panel.setSize(width, 400)
        panel.validate()
        // Wrapped text knows its height only once it has its width, so it takes a second layout, as in the IDE.
        panel.invalidate()
        panel.validate()
    }

    @Test
    fun `a shrinkable cell gives way so the cell after it stays inside a narrow pane`() {
        lateinit var long: JComponent
        lateinit var right: JComponent
        val panel = panel {
            row {
                label("TR-11866-reuse-steps-config-on-tenant-import")
                long = link("base: origin/release/highway (default)".repeat(5)) {}.shrinkable().component
                right = comment("Worktree").align(AlignX.RIGHT).component
            }
        }
        layOut(panel, 400)
        assertTrue(long.width < long.preferredSize.width)
        assertEquals(400, right.x + right.width)
    }

    @Test
    fun `a shrinkable cell shorter than the row keeps its width and the cell after it goes to the right edge`() {
        lateinit var path: JComponent
        lateinit var counts: JComponent
        val panel = panel {
            row {
                path = link("docs/README.md") {}.shrinkable().component
                counts = comment("+306 −0").component
            }
        }
        layOut(panel, 400)
        assertEquals(path.preferredSize.width, path.width)
        assertEquals(400, counts.x + counts.width)
    }

    @Test
    fun `word-wrapped text breaks into lines within the pane`() {
        lateinit var title: JComponent
        val panel = panel { row { title = text("Refaktor RtmStepsConfigApplier ".repeat(5), MAX_LINE_LENGTH_WORD_WRAP).component } }
        layOut(panel, 300)
        assertTrue(title.x + title.width <= 300)
        assertTrue(title.height > title.getFontMetrics(title.font).height * 2)
    }
}
