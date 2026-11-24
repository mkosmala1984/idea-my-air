package dev.myair.canvasview

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.editor.CANVAS_ICON
import dev.myair.service.ClaudeSessionsService
import javax.swing.Icon

/** A canvas tab is titled with its folder's name, which follows renames; "Canvas" once the folder is gone. */
class CanvasTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) =
        (file as? CanvasVirtualFile)?.let { ClaudeSessionsService.getInstance().folders.find(it.folderId)?.name ?: "Canvas" }
}

/** A canvas tab has the canvas folder's icon ([CANVAS_ICON]). */
class CanvasFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = if (file is CanvasVirtualFile) CANVAS_ICON else null
}
