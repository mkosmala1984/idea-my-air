package dev.myair.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.KotlinUiDslService
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
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

    // The DSL's `panel` looks its implementation up as an application service, and these tests run without an application.
    // The implementation is Kotlin-internal, hence the reflection.
    private val dsl = Class.forName("com.intellij.ui.dsl.builder.impl.KotlinUiDslServiceImpl")
        .getDeclaredConstructor().newInstance() as KotlinUiDslService

    private fun panel(init: Panel.() -> Unit) = dsl.panel(init)

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
    fun `a shrinking line gives way from its last part, then the one before, keeping the links after it in the pane`() {
        val branch = JBLabel("TR-11866-reuse-steps-config-on-tenant-import")
        val arrow = JBLabel(" → ")
        val base = JBLabel("origin/release/agc-migration")
        lateinit var terminal: JComponent
        val panel = panel {
            row {
                cell(ShrinkingLine(listOf(branch, arrow, base))).shrinkable()
                link("IDEA") {}
                terminal = link("Terminal") {}.component
            }
        }
        val full = branch.preferredSize.width + arrow.preferredSize.width + base.preferredSize.width
        layOut(panel, full)
        assertTrue(base.width in 1 until base.preferredSize.width)
        assertEquals(branch.preferredSize.width, branch.width)
        assertEquals(full, terminal.x + terminal.width)
        layOut(panel, branch.preferredSize.width / 2)
        assertEquals(0, base.width)
        assertEquals(0, arrow.width)
        assertTrue(branch.width in 1 until branch.preferredSize.width)
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
