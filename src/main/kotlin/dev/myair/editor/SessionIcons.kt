package dev.myair.editor

import com.intellij.ide.ProjectIconPalette
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import dev.myair.claude.SessionStatus
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Line2D
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

private val projectIcons = RecentProjectIconHelper()

/**
 * The icon the Welcome screen and Air give the project at [dir]: its `.idea/icon.svg`, else [name]'s initials on a colour
 * picked from the path ([projectColor]). The helper caches it per path.
 */
fun projectIcon(dir: Path, name: String, size: Int): Icon = projectIcons.getProjectIcon(dir, true, size, name)

private val projectColors = ConcurrentHashMap<Path, Color>()

/**
 * The first colour of the gradient [projectIcon] paints [dir]'s initials on (`FullProjectAvatarPresentation` picks it so).
 * The palette reads the project's colour storage on the EDT only, while tab colours are asked for off it: the first call
 * for [dir] gives null, reads the colour on the EDT and then runs [onRead]. A colour changed later shows after a restart.
 */
fun projectColor(dir: Path, onRead: () -> Unit): Color? = projectColors[dir] ?: run {
    ApplicationManager.getApplication().invokeLater {
        projectColors.computeIfAbsent(dir) { ProjectIconPalette.gradient(it).first }
        onRead()
    }
    null
}

/** Claude's orange asterisk: an idle session. */
val CLAUDE_ICON: Icon = IconLoader.getIcon("/icons/claude.svg", SessionStatus::class.java)

/** A thin grey asterisk: a session without a process. */
val OFFLINE_SESSION_ICON: Icon = IconLoader.getIcon("/icons/claudeOffline.svg", SessionStatus::class.java)

/** The blue of an unread reply. */
val UNREAD_BLUE = JBColor(0x3574F0, 0x548AF7)

/** An idle session with an unread reply: the asterisk in [UNREAD_BLUE]. */
val UNREAD_SESSION_ICON: Icon = IconLoader.getIcon("/icons/claudeUnread.svg", SessionStatus::class.java)

/** The amber of a session waiting for input, as [NEEDS_INPUT_ICON] fills its disc. */
val NEEDS_INPUT_AMBER = JBColor(0xF2A50C, 0xF2C55C)

/** An amber disc with an exclamation mark: a session waiting for input. */
val NEEDS_INPUT_ICON: Icon = IconLoader.getIcon("/icons/needsInput.svg", SessionStatus::class.java)

/** Claude's orange, as [WORKING_ICON] paints its rays. */
val CLAUDE_ORANGE = Color(0xD97757)

/** One frame of [WORKING_ICON]: eight half-rays of the asterisk fading around the circle, the brightest at [step] × 45°. */
private class WorkingFrame(private val step: Int) : Icon {
    override fun getIconWidth() = JBUIScale.scale(16)

    override fun getIconHeight() = JBUIScale.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.translate(x, y)
            g2.scale(iconWidth / 16.0, iconHeight / 16.0)
            g2.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (i in 0 until 8) {
                val ray = g2.create() as Graphics2D
                ray.rotate(Math.toRadians(45.0 * (step - i)), 8.0, 8.0)
                ray.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - i * 0.11f)
                ray.color = CLAUDE_ORANGE
                ray.draw(Line2D.Double(8.0, 1.6, 8.0, 6.0))
                ray.dispose()
            }
        } finally {
            g2.dispose()
        }
    }
}

internal val WORKING_FRAMES: List<Icon> = List(8) { WorkingFrame(it) }

/** A working session: the asterisk as a spinner, its brightest ray turning one ray every 100 ms. */
val WORKING_ICON: Icon = AnimatedIcon(100, *WORKING_FRAMES.toTypedArray())

/**
 * A session's one icon, its status, on its tab and its rows: [OFFLINE_SESSION_ICON] without a process ([status] null),
 * [WORKING_ICON] while it works, [NEEDS_INPUT_ICON] when it needs input, [UNREAD_SESSION_ICON] when its reply is [unread],
 * else the orange asterisk.
 */
fun sessionIcon(status: SessionStatus?, unread: Boolean): Icon = when (status) {
    null -> OFFLINE_SESSION_ICON
    SessionStatus.WORKING -> WORKING_ICON
    SessionStatus.NEEDS_INPUT -> NEEDS_INPUT_ICON
    SessionStatus.IDLE -> if (unread) UNREAD_SESSION_ICON else CLAUDE_ICON
}
