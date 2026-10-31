package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.BadgeIcon
import com.intellij.ui.JBColor
import dev.myair.claude.SessionStatus
import javax.swing.Icon

/** Claude's orange asterisk. */
val CLAUDE_ICON: Icon = IconLoader.getIcon("/icons/claude.svg", SessionStatus::class.java)

/** The asterisk greyed, for a session without a process. */
val CLAUDE_GREY_ICON: Icon = IconLoader.getDisabledIcon(CLAUDE_ICON)

val SessionStatus.icon: Icon
    get() = when (this) {
        SessionStatus.WORKING -> AllIcons.Actions.MoreHorizontal
        SessionStatus.NEEDS_INPUT -> AllIcons.General.Warning
        SessionStatus.IDLE -> AllIcons.Actions.Checked
    }

/** An idle session with an unread reply: the idle icon with the taskbar's blue "Unread" dot (`taskbar/Theme.cs`). */
private val UNREAD_ICON: Icon = BadgeIcon(SessionStatus.IDLE.icon, JBColor(0x005FB8, 0x4CC2FF))

/** The icon of a session in [status], null without a process; [unread] marks an idle one whose reply the user has not seen. */
fun statusIcon(status: SessionStatus?, unread: Boolean): Icon? = if (status == SessionStatus.IDLE && unread) UNREAD_ICON else status?.icon
