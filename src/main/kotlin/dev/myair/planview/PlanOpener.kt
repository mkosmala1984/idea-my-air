package dev.myair.planview

import com.intellij.openapi.project.Project
import dev.myair.tabs.TabsSync

object PlanOpener {
    /** Focuses the plan's tab where its manager already shows (another IDE), else opens it in [project] ([openHere]). */
    fun open(project: Project, folderId: String) {
        val manager = PlanFiles.getInstance().store.read().byFolder[folderId]?.session
        if (manager != null && TabsSync.getInstance().focusElsewhere(manager)) return
        openHere(project, folderId)
    }

    /** Opens the plan's tab in [project], or focuses it there, and returns its editor ([FolderTabs.openHere]). */
    fun openHere(project: Project, folderId: String): PlanEditor = PlanFiles.getInstance().tabs.openHere(project, folderId)

    /** The open editors of the plan of [folderId] in every project of this IDE. */
    fun editors(folderId: String): List<PlanEditor> = PlanFiles.getInstance().tabs.editors(folderId)
}
