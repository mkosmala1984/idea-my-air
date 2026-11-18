package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.myair.tabs.TabsSync

object PlanOpener {
    /** Focuses the plan's tab where its manager already shows (another IDE), else opens it in [project] ([openHere]). */
    fun open(project: Project, folderId: String) {
        val manager = PlanFiles.getInstance().store.read().byFolder[folderId]?.session
        if (manager != null && TabsSync.getInstance().focusElsewhere(manager)) return
        openHere(project, folderId)
    }

    /**
     * Opens the plan's tab in [project], or focuses it there, and returns its editor. A tab left showing the plan gone, which
     * came back since, is closed first, so the plan opens in a new editor.
     */
    fun openHere(project: Project, folderId: String): PlanEditor {
        val editors = FileEditorManager.getInstance(project)
        val file = PlanFiles.getInstance().file(folderId)
        if (editors.getAllEditors(file).filterIsInstance<PlanEditor>().any { it.gone }) editors.closeFile(file)
        return editors.openFile(file, true).filterIsInstance<PlanEditor>().single()
    }

    /** The open editors of the plan of [folderId] in every project of this IDE. */
    fun editors(folderId: String): List<PlanEditor> = ProjectManager.getInstance().openProjects.flatMap {
        FileEditorManager.getInstance(it).getAllEditors(PlanFiles.getInstance().file(folderId)).filterIsInstance<PlanEditor>()
    }
}
