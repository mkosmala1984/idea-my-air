package dev.myair.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.myair.claude.SessionStatus
import dev.myair.service.ClaudeSessionsService
import java.nio.file.Path
import javax.swing.Icon

/**
 * A tab running `claude <claudeArgs>` in [cwd]. [sessionId] and [title] follow the session the process shows now, which
 * `/clear` or `/resume` can change; [ClaudeSessionsService] updates them by [pid], known once the process has started.
 */
class ClaudeSessionVirtualFile(var sessionId: String, val cwd: Path, var title: String, val claudeArgs: List<String>) :
    LightVirtualFile(title, FileTypes.PLAIN_TEXT, "") {
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
}

class ClaudeSessionTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) = (file as? ClaudeSessionVirtualFile)?.title
}

/** The tab icon shows the session's status, as the rows of the Claude Sessions tool window do. */
class ClaudeSessionFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? ClaudeSessionVirtualFile)?.let { if (it.unread) UNREAD_ICON else it.status?.icon ?: AllIcons.Toolwindows.ToolWindowMessages }
}
