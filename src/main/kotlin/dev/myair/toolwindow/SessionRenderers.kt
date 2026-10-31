package dev.myair.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import dev.myair.editor.sessionIcon
import dev.myair.folders.Folder
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** "2/5": done tasks over all of them, null for a brief without tasks. */
fun briefCount(brief: Brief?): String? = brief?.takeIf { it.tasks.isNotEmpty() }?.let { "${it.done}/${it.tasks.size}" }

private val TaskStatus.mark get() = when (this) {
    TaskStatus.COMPLETED -> "✓"
    TaskStatus.IN_PROGRESS -> "▶"
    TaskStatus.PENDING -> "○"
}

fun BriefTask.line() = "${status.mark} $content"

/** What a session is doing, as its tooltip and the context pane say it; null without a process. */
fun statusText(live: LiveSession?, unread: Boolean): String? = when (live?.status) {
    SessionStatus.NEEDS_INPUT -> "Needs input" + (live.waitingFor?.let { ": $it" } ?: "")
    SessionStatus.WORKING -> "Working"
    SessionStatus.IDLE -> if (unread) "Unread reply" else "Waiting for prompt"
    null -> null
}

/** A session row's tooltip: its status, settled, Desktop, the [project] when the row does not show it, its brief, and the first human prompt. */
fun tooltipLines(session: ClaudeSession, live: LiveSession?, unread: Boolean, settled: Boolean, project: Path?): List<String> = listOfNotNull(
    statusText(live, unread),
    "Settled".takeIf { settled },
    "Desktop".takeIf { session.desktop },
    project?.let { "Project: ${it.fileName ?: it}" },
    session.brief?.goal?.let { "Goal: $it" },
) + session.brief?.tasks.orEmpty().map { it.line() } + listOfNotNull(session.prompts.firstOrNull()?.lineSequence()?.first()?.take(PROMPT_CHARS)?.let { "Asked: $it" })

private const val PROMPT_CHARS = 200

private val projectIcons = RecentProjectIconHelper()

/** How roomy the session lists are: [COMPACT] as the IDE's own trees, [LOOSE] as Air's, with larger icons, taller rows and wider gaps. */
enum class Density(val rowHeight: Int, val iconSize: Int, val iconTextGap: Int) {
    COMPACT(26, 16, 6), LOOSE(34, 20, 10);

    /** A 16 px [icon] at [iconSize]. */
    fun grow(icon: Icon): Icon = IconUtil.scale(icon, null, iconSize / 16f)
}

/**
 * The icon the Welcome screen and Air give the project at [dir]: its `.idea/icon.svg`, else [name]'s initials on a colour
 * picked from the path. The helper caches it per path.
 */
private fun projectIcon(dir: Path, name: String, size: Int) = projectIcons.getProjectIcon(dir, true, size, name)

/**
 * A session's row in the tree and in the pinned list: its status as one [sessionIcon], title and task count; [SessionTrailer]
 * adds the age. [projectInRow] names the session's project after the count; [project] names it in the tooltip instead.
 */
private fun SimpleColoredComponent.appendSession(
    item: ClaudeSession, live: LiveSession?, settled: Boolean, unread: Boolean, project: Path?, projectInRow: Boolean, density: Density,
) {
    iconTextGap = JBUI.scale(density.iconTextGap)
    append(item.title, when {
        settled -> SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES
        unread -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    })
    toolTipText = tooltipLines(item, live, unread, settled, project).takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
    icon = density.grow(sessionIcon(live?.status, unread))
    briefCount(item.brief)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    if (projectInRow) append("  ${item.cwd.fileName ?: item.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
}

/** Sessions from every project, so each row names its project; a row without a process is there because it is pinned. */
class PinnedRenderer(
    private val liveOf: (ClaudeSession) -> LiveSession?, private val settled: (ClaudeSession) -> Boolean, private val unread: (ClaudeSession) -> Boolean,
    private val density: () -> Density,
) : ColoredListCellRenderer<ClaudeSession>() {
    override fun customizeCellRenderer(list: JList<out ClaudeSession>, value: ClaudeSession, index: Int, selected: Boolean, hasFocus: Boolean) =
        appendSession(value, liveOf(value), settled(value), unread(value), project = null, projectInRow = true, density())
}

class SessionRenderer(
    private val liveOf: (ClaudeSession) -> LiveSession?, private val settled: (ClaudeSession) -> Boolean, private val unread: (ClaudeSession) -> Boolean,
    private val density: () -> Density,
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val d = density()
        iconTextGap = JBUI.scale(d.iconTextGap)
        val node = value as DefaultMutableTreeNode
        // clear() keeps the tooltip, and one renderer draws every row: only session rows set one.
        toolTipText = null
        when (val item = node.userObject) {
            is Folder -> {
                icon = item.project?.let { projectIcon(Path.of(it), item.name, d.iconSize) } ?: d.grow(AllIcons.Nodes.Folder)
                append(item.name)
                item.project?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            UnfiledSessions -> {
                icon = d.grow(AllIcons.Actions.ListFiles)
                append(item.toString(), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
            RecentSessions -> {
                icon = d.grow(AllIcons.Vcs.History)
                append(item.toString(), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
            is Path -> {
                icon = projectIcon(item, projectName(item), d.iconSize)
                append(projectName(item))
                append("  $item", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            // Among recent sessions, which mix every project, the row names the project; in a folder not bound to it the tooltip does.
            is ClaudeSession -> when (val parent = (node.parent as DefaultMutableTreeNode).userObject) {
                RecentSessions -> appendSession(item, liveOf(item), settled(item), unread(item), project = null, projectInRow = true, d)
                else -> appendSession(
                    item, liveOf(item), settled(item), unread(item),
                    project = item.cwd.takeIf { parent is Folder && parent.project != item.cwd.toString() }, projectInRow = false, density = d,
                )
            }
            is BriefGoal -> {
                icon = d.grow(AllIcons.Actions.IntentionBulb)
                append(item.text)
            }
            is BriefTaskRow -> append(item.task.line(), when (item.task.status) {
                TaskStatus.COMPLETED -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                TaskStatus.IN_PROGRESS -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                TaskStatus.PENDING -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            })
            is AddedDir -> {
                icon = d.grow(AllIcons.Nodes.Folder)
                append(item.path.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ChangedFile -> {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(item.path.fileName.toString()).icon?.let(d::grow)
                append(fileLabel(item.path, item.cwd))
            }
        }
    }
}
