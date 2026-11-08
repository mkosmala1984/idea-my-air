package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.SessionPane
import dev.myair.editor.SessionPaneHost
import dev.myair.editor.SessionTerminals
import dev.myair.service.ActiveSession
import dev.myair.service.ClaudeSessionsService
import java.awt.BorderLayout
import java.awt.GridBagLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class PlanEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is PlanVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = PlanEditor(project, file as PlanVirtualFile)
    override fun getEditorTypeId() = "myair-plan"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * A plan tab: the plan ([PlanPanel]) beside the terminal of its manager session. The manager starts once the service has
 * read the folders and the plan is there; a plan gone (deleted while open, or before a restored tab was first shown) leaves
 * only a notice, its manager's process ended.
 */
class PlanEditor(private val project: Project, private val file: PlanVirtualFile) : UserDataHolderBase(), FileEditor, SessionPaneHost {
    private val service = ClaudeSessionsService.getInstance()
    // Created here rather than in a service notification, where its subscribing would change the list being notified.
    private val active = ActiveSession.getInstance(project)
    private val root = JPanel(BorderLayout())
    private var panel: PlanPanel? = null
    private var manager: ClaudeSessionVirtualFile? = null
    override var pane: SessionPane? = null
        private set
    private var gone = false

    init {
        service.subscribe(this, ::update)
        update()
    }

    private fun update() {
        if (gone) return
        // Folders not read yet, or unreadable, say nothing about the plan: wait, keeping what shows.
        if (!service.loaded || service.foldersError != null) {
            if (panel == null) show(notice(service.foldersError?.let { "Cannot read the plans: $it" } ?: "Loading..."))
            return
        }
        val view = planViewOf(service.folders, file.folderId, service.sessions, manager?.sessionId) ?: return planGone()
        panel?.let { return it.show(view) }
        val panel = PlanPanel(project, file.folderId, this).also { panel = it }
        // Files a new manager in the plan folder, which notifies the service's listeners, this editor among them, now.
        val manager = PlanFiles.getInstance().startManager(view.folder, service.folders, project).also { manager = it }
        // Checked whenever a process is about to start, not only for a restored tab; a detached one here is bound as it is.
        val pane = SessionPane(project, manager, checkFirst = !SessionTerminals.getInstance().runs(manager)).also { pane = it }
        Disposer.register(this, pane)
        show(OnePixelSplitter(false, "dev.myair.planView.split", 0.55f).apply {
            firstComponent = JBScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
            secondComponent = pane.component
        })
        panel.show(checkNotNull(planViewOf(service.folders, file.folderId, service.sessions, manager.sessionId)))
        active.tabFileChanged()
    }

    private fun planGone() {
        gone = true
        show(notice("This plan no longer exists."))
        manager?.let(SessionTerminals.getInstance()::close)
        PlanFiles.getInstance().forget(file.folderId)
        pane?.let(Disposer::dispose)
        pane = null
        active.tabFileChanged()
    }

    private fun show(component: JComponent) {
        root.removeAll()
        root.add(component)
        root.revalidate()
        root.repaint()
    }

    private fun notice(text: String) = JPanel(GridBagLayout()).apply { add(JBLabel(text)) }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = pane?.preferredFocusedComponent ?: root
    override fun getName() = "Plan"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
