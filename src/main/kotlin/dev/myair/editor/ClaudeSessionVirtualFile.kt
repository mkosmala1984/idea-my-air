package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.ui.RowIcon
import com.intellij.util.IconUtil
import dev.myair.claude.SessionStatus
import dev.myair.claude.isAtOrUnder
import dev.myair.service.ClaudeSessionsService
import java.awt.Color
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.SwingConstants

/**
 * A tab running `claude <claudeArgs>` in [cwd]. [sessionId] and [title] follow the session the process shows now, which
 * `/clear` or `/resume` can change; [ClaudeSessionsService] updates them by [pid], known once the process has started.
 * [project] is the session's [dev.myair.claude.ClaudeSession.project], whose icon and colour the tab takes outside that project's window.
 * [restored]: the platform reopened the tab on startup through [ClaudeSessionFileSystem]; nobody checked it may start yet.
 */
class ClaudeSessionVirtualFile(
    var sessionId: String,
    val cwd: Path,
    val project: Path,
    var title: String,
    val claudeArgs: List<String>,
    val restored: Boolean = false,
) : LightVirtualFile(title, FileTypes.PLAIN_TEXT, ""), EditorHistoryManager.OptionallyIncluded {
    @Volatile
    var pid: Long? = null

    /** The status the registry reports for [pid]; null until the process registers and after it ends. */
    @Volatile
    var status: SessionStatus? = null

    /** [ClaudeSessionsService.unread] holds [sessionId]. */
    @Volatile
    var unread = false

    init {
        isWritable = false
    }

    override fun getFileSystem() = ClaudeSessionFileSystem.instance

    /** Read when the platform saves the open tabs, so a tab `/clear` switched restores the session it shows now. */
    override fun getPath() = sessionTabPath(sessionId, cwd, project)

    // Recent Files would hold this object, its URL and claudeArgs from when it was opened, and reopen it unchecked.
    override fun isIncludedInEditorHistory(project: Project) = false
}

/** The tab's title is the session's; its tooltip names the session's project and, for a worktree, where it runs ([tabTooltip]). */
class ClaudeSessionTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) = (file as? ClaudeSessionVirtualFile)?.title

    override fun getEditorTabTooltipHtml(project: Project, file: VirtualFile): HtmlChunk? = (file as? ClaudeSessionVirtualFile)?.let {
        HtmlBuilder().appendWithSeparators(HtmlChunk.br(), tabTooltip(it.title, it.cwd, it.project).map(HtmlChunk::text)).toFragment()
    }
}

/** The tooltip's lines: [title], the project, and the worktree when the session runs outside the project directory. */
fun tabTooltip(title: String, cwd: Path, sessionProject: Path) =
    listOfNotNull(title, "Project: $sessionProject", "Worktree: $cwd".takeIf { cwd != sessionProject })

/** [sessionProject] is another project than the IDE's at [ideBase]: neither it nor a folder below it. */
fun isOtherProject(sessionProject: Path, ideBase: Path) = !sessionProject.isAtOrUnder(ideBase.toString())

/**
 * The tab icon shows the session's status as its rows in the Claude Sessions tool window do ([sessionIcon]), a branch badge in
 * its corner when the session runs in a worktree, that is outside its project directory (as `presetOf` tells), and after it the
 * project's own icon when that is another project than the window's.
 */
class ClaudeSessionFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? ClaudeSessionVirtualFile)?.let {
            val status = sessionIcon(it.status, it.unread)
            val badged = if (it.cwd == it.project) status else LayeredIcon(2).apply {
                setIcon(status, 0)
                setIcon(WORKTREE_BADGE, 1, SwingConstants.SOUTH_EAST)
            }
            if (project != null && !isOtherProject(it.project, projectBase(project))) badged
            else RowIcon(badged, projectIcon(it.project, (it.project.fileName ?: it.project).toString(), badged.iconWidth))
        }
}

private val WORKTREE_BADGE: Icon = IconUtil.scale(AllIcons.Vcs.Branch, null, 0.625f)

/**
 * A tab of another project's session takes that project's colour ([projectColor]), mixed into the tab background so the
 * title stays readable; tabs of the window's own project keep the default.
 */
class ClaudeSessionTabColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        (file as? ClaudeSessionVirtualFile)?.project?.takeIf { isOtherProject(it, projectBase(project)) }
            ?.let { dir -> projectColor(dir) { if (!project.isDisposed) FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file) } }
            ?.let { JBColor(ColorUtil.mix(Color.WHITE, it, 0.3), ColorUtil.mix(Color(0x2B2D30), it, 0.35)) }
}
