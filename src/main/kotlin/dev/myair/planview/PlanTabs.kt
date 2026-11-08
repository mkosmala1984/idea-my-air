package dev.myair.planview

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.service.ClaudeSessionsService
import javax.swing.Icon

/** A plan tab is titled with its plan's name, which follows renames; "Plan" once its folder is gone. */
class PlanTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) =
        (file as? PlanVirtualFile)?.let { ClaudeSessionsService.getInstance().folders.find(it.folderId)?.name ?: "Plan" }
}

/** A plan tab has the icon of a plan folder in the Claude Sessions tree. */
class PlanFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = if (file is PlanVirtualFile) AllIcons.Nodes.Target else null
}
