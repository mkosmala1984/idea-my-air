package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.myair.tabs.TabsSync

object PlanOpener {
    /**
     * Focuses the plan's tab where its manager already shows (another IDE), else opens it in [project]. A tab of
     * [project] left showing the plan gone, which came back since, is closed first, so the plan opens in a new editor.
     */
    fun open(project: Project, folderId: String) {
        val files = PlanFiles.getInstance()
        val manager = files.store.read().byFolder[folderId]?.session
        if (manager != null && TabsSync.getInstance().focusElsewhere(manager)) return
        val editors = FileEditorManager.getInstance(project)
        val file = files.file(folderId)
        if (editors.getAllEditors(file).filterIsInstance<PlanEditor>().any { it.gone }) editors.closeFile(file)
        editors.openFile(file, true)
    }
}
