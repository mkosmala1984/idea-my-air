package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.myair.tabs.TabsSync

object PlanOpener {
    /** Focuses the plan's tab where its manager already shows (another IDE), else opens it in [project]. */
    fun open(project: Project, folderId: String) {
        val files = PlanFiles.getInstance()
        val manager = files.store.read().byFolder[folderId]?.session
        if (manager != null && TabsSync.getInstance().focusElsewhere(manager)) return
        FileEditorManager.getInstance(project).openFile(files.file(folderId), true)
    }
}
