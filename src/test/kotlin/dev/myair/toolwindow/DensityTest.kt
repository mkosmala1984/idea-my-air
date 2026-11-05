package dev.myair.toolwindow

import com.intellij.ui.scale.JBUIScale
import dev.myair.editor.CLAUDE_ICON
import dev.myair.editor.NEEDS_INPUT_ICON
import dev.myair.editor.UNREAD_SESSION_ICON
import dev.myair.editor.WORKING_ICON
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DensityTest {
    @Test
    fun `each density draws the session icons, the spinner too, at its own size`() {
        for (density in Density.entries) for (icon in listOf(CLAUDE_ICON, UNREAD_SESSION_ICON, NEEDS_INPUT_ICON, WORKING_ICON)) {
            assertEquals(JBUIScale.scale(density.iconSize), density.grow(icon).iconWidth, "$density $icon")
        }
    }
}
