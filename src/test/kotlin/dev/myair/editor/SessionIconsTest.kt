package dev.myair.editor

import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SessionIconsTest {
    @Test
    fun `one icon tells every status of a session`() {
        assertSame(OFFLINE_SESSION_ICON, sessionIcon(null, unread = true))
        assertSame(WORKING_ICON, sessionIcon(SessionStatus.WORKING, unread = true))
        assertSame(NEEDS_INPUT_ICON, sessionIcon(SessionStatus.NEEDS_INPUT, unread = true))
        assertSame(UNREAD_SESSION_ICON, sessionIcon(SessionStatus.IDLE, unread = true))
        assertSame(CLAUDE_ICON, sessionIcon(SessionStatus.IDLE, unread = false))
    }

    @Test
    fun `no two statuses and no two frames of the working spinner paint the same pixels`() {
        val icons = listOf(OFFLINE_SESSION_ICON, NEEDS_INPUT_ICON, UNREAD_SESSION_ICON, CLAUDE_ICON) + WORKING_FRAMES
        val pixels = icons.map { it.pixels() }
        assertEquals(icons.size, pixels.distinct().size)
    }

    private fun Icon.pixels(): List<Int> {
        val image = BufferedImage(iconWidth, iconHeight, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { paintIcon(null, it, 0, 0) }.dispose()
        return image.getRGB(0, 0, iconWidth, iconHeight, null, 0, iconWidth).toList()
    }
}
