package dev.myair.canvasview

import com.intellij.openapi.project.Project
import dev.myair.canvas.CanvasService
import dev.myair.tabs.TabsSync

object CanvasOpener {
    /**
     * Focuses the canvas's tab where its manager already shows (another IDE), else opens it in [project]. A canvas that
     * cannot be read names no manager; its tab opens here and says why.
     */
    fun open(project: Project, folderId: String) {
        val manager = requireNotNull(CanvasService.getInstance().canvases[folderId]) { "folder $folderId has no canvas" }.getOrNull()?.manager?.session
        if (manager != null && TabsSync.getInstance().focusElsewhere(manager)) return
        openHere(project, folderId)
    }

    /** Opens the canvas's tab in [project], or focuses it there, and returns its editor. */
    fun openHere(project: Project, folderId: String): CanvasEditor = CanvasTabs.getInstance().tabs.openHere(project, folderId)
}
