package dev.myair.reviewview

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.editor.REVIEW_ICON
import dev.myair.service.ClaudeSessionsService
import javax.swing.Icon

/** A review tab is titled with its folder's name, which follows renames; "Review" once the folder is gone. */
class ReviewTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) =
        (file as? ReviewVirtualFile)?.let { ClaudeSessionsService.getInstance().folders.find(it.folderId)?.name ?: "Review" }
}

/** A review tab has the review folder's icon in the tree. */
class ReviewFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = if (file is ReviewVirtualFile) REVIEW_ICON else null
}
