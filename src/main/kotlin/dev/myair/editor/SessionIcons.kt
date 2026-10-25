package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.BadgeIcon
import com.intellij.ui.JBColor
import dev.myair.claude.SessionStatus
import javax.swing.Icon

val SessionStatus.icon: Icon
    get() = when (this) {
        SessionStatus.WORKING -> AllIcons.Actions.MoreHorizontal
        SessionStatus.NEEDS_INPUT -> AllIcons.General.Warning
        SessionStatus.IDLE -> AllIcons.Actions.Checked
    }

/** An idle session with an unread reply: the idle icon with the taskbar's blue "Unread" dot (`taskbar/Theme.cs`). */
val UNREAD_ICON: Icon = BadgeIcon(SessionStatus.IDLE.icon, JBColor(0x005FB8, 0x4CC2FF))
