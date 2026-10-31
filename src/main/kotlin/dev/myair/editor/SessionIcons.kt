package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.BadgeDotProvider
import com.intellij.ui.BadgeIcon
import com.intellij.ui.JBColor
import dev.myair.claude.SessionStatus
import javax.swing.Icon

/** Claude's orange asterisk. */
val CLAUDE_ICON: Icon = IconLoader.getIcon("/icons/claude.svg", SessionStatus::class.java)

/** The asterisk greyed, for a session without a process. */
val CLAUDE_GREY_ICON: Icon = IconLoader.getDisabledIcon(CLAUDE_ICON)

/** The blue of an unread reply and of a working session's dot. */
val UNREAD_BLUE = JBColor(0x3574F0, 0x548AF7)

/** An idle session with an unread reply: the asterisk with a blue dot, inside its 16 px so the title stays put. */
val UNREAD_SESSION_ICON: Icon = BadgeIcon(CLAUDE_ICON, UNREAD_BLUE, BadgeDotProvider(x = 0.75, y = 0.25, radius = 0.15))

/**
 * A session's one icon, its status, on its tab and its rows: the grey asterisk without a process ([status] null), the spinner
 * while it works, a warning when it needs input, the asterisk with a blue dot when its reply is [unread], else the orange asterisk.
 */
fun sessionIcon(status: SessionStatus?, unread: Boolean): Icon = when (status) {
    null -> CLAUDE_GREY_ICON
    SessionStatus.WORKING -> AnimatedIcon.Default.INSTANCE
    SessionStatus.NEEDS_INPUT -> AllIcons.General.Warning
    SessionStatus.IDLE -> if (unread) UNREAD_SESSION_ICON else CLAUDE_ICON
}
