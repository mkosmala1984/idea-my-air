package dev.myair.toolwindow

import com.intellij.ui.AnimatedIcon
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.editor.CLAUDE_GREY_ICON
import dev.myair.editor.CLAUDE_ICON
import dev.myair.editor.icon
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertSame

class StatusDotsTest {
    private fun live(status: SessionStatus) = LiveSession(1, "cli", status, null, Instant.EPOCH)

    @Test
    fun `one icon tells every status of a session`() {
        assertSame(CLAUDE_GREY_ICON, sessionIcon(null, unread = true))
        assertSame(AnimatedIcon.Default.INSTANCE, sessionIcon(live(SessionStatus.WORKING), unread = true))
        assertSame(SessionStatus.NEEDS_INPUT.icon, sessionIcon(live(SessionStatus.NEEDS_INPUT), unread = true))
        assertSame(UNREAD_SESSION_ICON, sessionIcon(live(SessionStatus.IDLE), unread = true))
        assertSame(CLAUDE_ICON, sessionIcon(live(SessionStatus.IDLE), unread = false))
    }
}
