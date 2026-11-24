package dev.myair.canvasview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.canvas.CanvasService
import dev.myair.editor.SessionTerminals
import dev.myair.planview.ManagerTabEditor
import java.nio.file.Path

class CanvasEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is CanvasVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = CanvasEditor(project, file as CanvasVirtualFile)
    override fun getEditorTypeId() = "myair-canvas"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * A canvas tab: the canvas page ([CanvasPanel]) beside the terminal of its manager session ([ManagerTabEditor]). A canvas
 * folder gone shows a notice and ends the manager. A `canvas.json` that cannot be read before the canvas first showed
 * shows why, starting no manager, until it can; once the canvas shows, the panel's toolbar names the error and the last
 * frame stays, the manager left running.
 */
class CanvasEditor(project: Project, private val file: CanvasVirtualFile) : ManagerTabEditor(project, file, "Canvas", "dev.myair.canvasView.split") {
    private val canvases = CanvasService.getInstance()
    private var panel: CanvasPanel? = null
    override val goneNotice = "This canvas no longer exists."

    init {
        service.subscribe(this, ::update)
        canvases.subscribe(this, ::update)
        update()
    }

    override fun nameNow() = service.folders.find(file.folderId)?.name?.takeIf { file.folderId in canvases.canvases }

    override fun readError() = canvases.canvases.getValue(file.folderId).exceptionOrNull()?.let { "Cannot read the canvas: $it" }

    override fun content() = CanvasPanel(project, file.folderId, this, ::send, ::fileRoots).also { panel = it }

    override fun render() = checkNotNull(panel).show(canvases.canvases.getValue(file.folderId))

    override fun startManager() = CanvasTabs.getInstance().startManager(file.folderId, project)

    override fun profileToCheck() = CanvasTabs.getInstance().profileToCheck(file.folderId)

    override fun forgetManager() {
        CanvasTabs.getInstance().tabs.forgetManager(file.folderId)
    }

    /** Submits [text] to the manager's terminal; false while no process of it runs here, or it still starts. */
    private fun send(text: String): Boolean {
        val manager = manager ?: return false
        val terminals = SessionTerminals.getInstance()
        if (!terminals.runs(manager) || terminals.starting(manager)) return false
        return pane?.send(text) == true
    }

    /** Where a canvas may open files ([canvasFileTarget]): the folder's start directory, then the manager's working directory. */
    private fun fileRoots(): List<Path> = listOfNotNull(
        canvasStartDir(service.folders, file.folderId),
        canvases.canvases[file.folderId]?.getOrNull()?.manager?.cwd,
    ).distinct()
}
