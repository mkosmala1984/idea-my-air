package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.RowIcon
import dev.myair.claude.SessionStatus
import dev.myair.claude.isAtOrUnder
import dev.myair.service.ClaudeSessionsService
import java.nio.file.Path
import javax.swing.Icon

/**
 * A tab running `claude <claudeArgs>` in [cwd]. [sessionId] and [title] follow the session the process shows now, which
 * `/clear` or `/resume` can change; [ClaudeSessionsService] updates them by [pid], known once the process has started.
 * [project] is the session's [dev.myair.claude.ClaudeSession.project], named in the tab title outside that project's window.
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

class ClaudeSessionTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) =
        (file as? ClaudeSessionVirtualFile)?.let { tabTitle(it.title, it.project, projectBase(project)) }
}

/** [title], preceded by the name of [sessionProject] in brackets when it is neither [ideBase] nor a folder below it. */
fun tabTitle(title: String, sessionProject: Path, ideBase: Path) =
    if (sessionProject.isAtOrUnder(ideBase.toString())) title else "[${sessionProject.fileName ?: sessionProject}] $title"

/**
 * The tab icon shows the session's status, as the rows of the Claude Sessions tool window do, followed by a branch icon
 * when the session runs in a worktree, that is outside its project directory (as `presetOf` tells).
 */
class ClaudeSessionFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? ClaudeSessionVirtualFile)?.let {
            val status = if (it.unread) UNREAD_ICON else it.status?.icon ?: AllIcons.Toolwindows.ToolWindowMessages
            if (it.cwd == it.project) status else RowIcon(status, AllIcons.Vcs.Branch)
        }
}
