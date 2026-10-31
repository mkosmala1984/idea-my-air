package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertSame

class SessionIconsTest {
    @Test
    fun `one icon tells every status of a session`() {
        assertSame(CLAUDE_GREY_ICON, sessionIcon(null, unread = true))
        assertSame(AnimatedIcon.Default.INSTANCE, sessionIcon(SessionStatus.WORKING, unread = true))
        assertSame(AllIcons.General.Warning, sessionIcon(SessionStatus.NEEDS_INPUT, unread = true))
        assertSame(UNREAD_SESSION_ICON, sessionIcon(SessionStatus.IDLE, unread = true))
        assertSame(CLAUDE_ICON, sessionIcon(SessionStatus.IDLE, unread = false))
    }
}
