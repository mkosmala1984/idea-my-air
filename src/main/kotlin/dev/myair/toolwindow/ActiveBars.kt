package dev.myair.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle

/** Marks each of [rows], those of the session the window's active session tab shows, with a bar at the list's left edge. */
fun paintActiveBars(g: Graphics, rows: List<Rectangle>) {
    g.color = JBUI.CurrentTheme.Focus.focusColor()
    rows.forEach { g.fillRect(0, it.y, JBUI.scale(ACTIVE_BAR), it.height) }
}

private const val ACTIVE_BAR = 3
