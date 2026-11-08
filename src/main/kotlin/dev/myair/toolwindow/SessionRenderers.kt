package dev.myair.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import dev.myair.editor.projectIcon
import dev.myair.editor.sessionIcon
import dev.myair.folders.Folder
import dev.myair.folders.PlanStatus
import dev.myair.folders.progress
import dev.myair.folders.startFolder
import java.nio.file.Path
import javax.swing.Icon
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

/** A plan task's status as its tree row and the context pane show it. */
val PlanStatus.icon: Icon get() = when (this) {
    PlanStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
    PlanStatus.IN_PROGRESS -> AllIcons.Actions.Execute
    PlanStatus.WAITING -> AllIcons.General.User
    PlanStatus.BLOCKED -> AllIcons.Actions.Suspend
    PlanStatus.DONE -> AllIcons.RunConfigurations.TestPassed
    PlanStatus.CANCELED -> AllIcons.RunConfigurations.TestIgnored
}

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

/** How roomy the session lists are: [COMPACT] as the IDE's own trees, [LOOSE] as Air's, with larger icons, taller rows and wider gaps. */
enum class Density(val rowHeight: Int, val iconSize: Int, val iconTextGap: Int) {
    COMPACT(26, 16, 6), LOOSE(34, 20, 10);

    /** A 16 px [icon] at [iconSize]. */
    fun grow(icon: Icon): Icon = IconUtil.scale(icon, null, iconSize / 16f)
}

/** A session's title: grey italic when settled, grey without a process, bold when unread. */
fun titleAttributes(live: LiveSession?, settled: Boolean, unread: Boolean): SimpleTextAttributes = when {
    settled -> SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES
    live == null -> SimpleTextAttributes.GRAYED_ATTRIBUTES
    unread -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
}

/** [tooltipLines] as the HTML of a row's tooltip; null when there are none. */
fun sessionTooltip(session: ClaudeSession, live: LiveSession?, unread: Boolean, settled: Boolean, project: Path?): String? =
    tooltipLines(session, live, unread, settled, project).takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }

/**
 * A session's row in the tree: its status as one [sessionIcon], title and task count; [SessionTrailer]
 * adds the age. [projectInRow] names the session's project after the count; [project] names it in the tooltip instead.
 */
private fun SimpleColoredComponent.appendSession(
    item: ClaudeSession, live: LiveSession?, settled: Boolean, unread: Boolean, project: Path?, projectInRow: Boolean, density: Density,
) {
    iconTextGap = JBUI.scale(density.iconTextGap)
    append(item.title, titleAttributes(live, settled, unread))
    toolTipText = sessionTooltip(item, live, unread, settled, project)
    icon = density.grow(sessionIcon(live?.status, unread))
    briefCount(item.brief)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    if (projectInRow) append("  ${item.cwd.fileName ?: item.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
}


class SessionRenderer(
    private val liveOf: (ClaudeSession) -> LiveSession?, private val settled: (ClaudeSession) -> Boolean, private val unread: (ClaudeSession) -> Boolean,
    private val density: () -> Density, private val branchOf: (Path) -> String?, private val isGone: (Path) -> Boolean,
) : ColoredTreeCellRenderer() {
    /** A project or folder directory as its row shows it: the path, then the branch checked out there in brackets. */
    private fun dirLabel(dir: Path) = "$dir" + (branchOf(dir)?.let { " ($it)" } ?: "")

    /** A session's directory row: [dir] in [attributes] with [tooltip], or grey italic and saying so once it is gone. */
    private fun appendDir(dir: Path, attributes: SimpleTextAttributes, tooltip: String) {
        val gone = isGone(dir)
        append("$dir", if (gone) SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES else attributes)
        toolTipText = if (gone) "$tooltip (folder no longer exists)" else tooltip
    }

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
                icon = when {
                    item.plan != null -> d.grow(AllIcons.Nodes.Target)
                    item.dirs.isNotEmpty() -> projectIcon(Path.of(item.dirs.first()), item.name, d.iconSize)
                    else -> d.grow(AllIcons.Nodes.Folder)
                }
                append(item.name)
                val chain = node.path.map { (it as DefaultMutableTreeNode).userObject }.filterIsInstance<Folder>()
                val starts = startFolder(chain)?.let { "New sessions start in: ${it.startDir} (from ${chain.take(chain.indexOf(it) + 1).joinToString("/") { f -> f.name }})" }
                toolTipText = describedTooltip(listOfNotNull(starts), item.plan?.description)
                item.plan?.let { append("  ${progress(it.tasks())}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                if (item.dirs.isNotEmpty()) append("  " + item.dirs.joinToString { dirLabel(Path.of(it)) }, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                item.workdir?.let(Path::of)?.let {
                    append("  ⌂ " + dirLabel(it), if (isGone(it)) SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is MilestoneRow -> {
                icon = d.grow(if (item.milestone.done) AllIcons.Actions.Checked else AllIcons.Nodes.Bookmark)
                append("${item.milestone.id} ${item.milestone.title}", if (item.milestone.done) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${progress(item.milestone.tasks)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = describedTooltip(emptyList(), item.milestone.description)
            }
            is PlanTaskRow -> {
                val status = item.task.status
                icon = d.grow(status.icon)
                append("${item.task.id} ${item.task.title}", when {
                    status.closed -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                    status == PlanStatus.IN_PROGRESS -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                })
                item.task.next?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                // Its session may have ended without moving the task on.
                if (status == PlanStatus.IN_PROGRESS && groupSessions(node).none { liveOf(it) != null }) append("  no live session", SimpleTextAttributes.ERROR_ATTRIBUTES)
                toolTipText = describedTooltip(listOfNotNull(status.label, item.task.next?.let { "Next: $it" }), item.task.description)
            }
            is NoTask -> {
                icon = d.grow(AllIcons.Actions.ListFiles)
                append("no task", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
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
                append("  ${dirLabel(item)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            // Among recent sessions, which mix every project, the row names the project; in a folder not bound to it the tooltip does.
            is ClaudeSession -> when (val parent = (node.parent as DefaultMutableTreeNode).userObject) {
                // A task's sessions may run in any of its plan's directories.
                RecentSessions, is PlanTaskRow -> appendSession(item, liveOf(item), settled(item), unread(item), project = null, projectInRow = true, d)
                else -> appendSession(
                    item, liveOf(item), settled(item), unread(item),
                    project = item.cwd.takeIf { parent is Folder && item.cwd.toString() !in parent.dirs }, projectInRow = false, density = d,
                )
            }
            is WorkDirRow -> {
                icon = d.grow(AllIcons.Nodes.Folder)
                appendDir(item.session.workDir, SimpleTextAttributes.REGULAR_ATTRIBUTES, "Works in")
            }
            is BranchRow -> {
                icon = d.grow(AllIcons.Vcs.Branch)
                append(item.branch, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = "Branch checked out in ${item.dir}"
            }
            is BriefGroup -> {
                icon = d.grow(AllIcons.Actions.IntentionBulb)
                append("Brief")
                briefCount(item.session.brief)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            is BriefGoal -> append(item.text)
            is FilesGroup -> {
                icon = d.grow(AllIcons.Nodes.Folder)
                append("Files")
                append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is BriefTaskRow -> append(item.task.line(), when (item.task.status) {
                TaskStatus.COMPLETED -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                TaskStatus.IN_PROGRESS -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                TaskStatus.PENDING -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            })
            is AddedDir -> {
                icon = d.grow(AllIcons.Nodes.Folder)
                appendDir(item.path, SimpleTextAttributes.GRAYED_ATTRIBUTES, "Added directory")
            }
            is ChangedFile -> {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(item.path.fileName.toString()).icon?.let(d::grow)
                append(fileLabel(item.path, item.cwd))
            }
        }
    }
}

/** Markdown descriptions as HTML; one renderer paints every row on every repaint, so each text is converted once. */
private val descriptionHtml = HashMap<String, String>()

/** A plan row's tooltip: [lines] as text, then its [description] rendered; null when there is neither. */
private fun describedTooltip(lines: List<String>, description: String?): String? {
    val html = lines.map(StringUtil::escapeXmlEntities) + listOfNotNull(description?.let { descriptionHtml.getOrPut(it) { convertMarkdownToHtml(it) } })
    return html.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>")
}
