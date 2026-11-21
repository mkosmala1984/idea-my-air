package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import javax.swing.ScrollPaneConstants

class PlanEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is PlanVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = PlanEditor(project, file as PlanVirtualFile)
    override fun getEditorTypeId() = "myair-plan"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** A plan tab: the plan ([PlanPanel]) beside the terminal of its manager session ([ManagerTabEditor]). */
class PlanEditor(project: Project, private val file: PlanVirtualFile) : ManagerTabEditor(project, file, "Plan", "dev.myair.planView.split") {
    private var panel: PlanPanel? = null
    override val goneNotice = "This plan no longer exists."

    init {
        service.subscribe(this, ::update)
        update()
    }

    override fun nameNow() = service.folders.find(file.folderId)?.takeIf { it.plan != null }?.name

    override fun content() = JBScrollPane(
        PlanPanel(project, file.folderId, this).also { panel = it }, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    )

    override fun render() = checkNotNull(panel).show(checkNotNull(planViewOf(service.folders, file.folderId, service.sessions, manager?.sessionId)))

    override fun startManager() = PlanFiles.getInstance().startManager(checkNotNull(service.folders.find(file.folderId)), service.folders, project)

    override fun profileToCheck() = PlanFiles.getInstance().profileToCheck(file.folderId)

    override fun forgetManager() = PlanFiles.getInstance().forgetManager(file.folderId)
}
