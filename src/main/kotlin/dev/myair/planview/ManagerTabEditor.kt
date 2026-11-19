package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
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

/**
 * A folder's tab (a plan's, a review's): its content beside the terminal of its manager session. The manager starts once
 * the service has read the folders and the folder's plan or review is there; one gone (its folder deleted, its plan
 * removed while open, or before a restored tab was first shown) leaves only a notice, its manager's process ended.
 * A subclass calls [update] from its `init`, after its own fields, and on every change it follows.
 */
abstract class ManagerTabEditor(protected val project: Project, private val file: FolderTabFile, private val editorName: String, splitKey: String) :
    UserDataHolderBase(), FileEditor, SessionPaneHost {
    protected val service = ClaudeSessionsService.getInstance()
    // Created here rather than in a service notification, where its subscribing would change the list being notified.
    private val active = ActiveSession.getInstance(project)
    private val root = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(false, splitKey, 0.55f)
    /** The manager's file bound now; null before the content first shows. */
    protected var manager: ClaudeSessionVirtualFile? = null
        private set
    final override var pane: SessionPane? = null
        private set
    /** The folder's plan or review went while this editor was open; it stays a notice, as the opener replaces it with a new editor. */
    var gone = false
        private set
    private var shown = false
    // The tab's title shown, the folder's name; null until the content first shows, when the platform reads it anyway.
    private var title: String? = null

    /** The folder's name while its plan or review is there, else null; asked only once the folders are read. */
    protected abstract fun nameNow(): String?

    /** Why the folder's content cannot be read now, null when it can; asked only while [nameNow] gives a name. */
    protected open fun readError(): String? = null

    protected abstract val goneNotice: String

    /** The left side, built once, before the manager starts. */
    protected abstract fun content(): JComponent

    /** Shows the folder as it is now on the left side, once [content] was built. */
    protected abstract fun render()

    /** The manager's file, kept while its process runs, else built again from its stored entry ([FolderTabs.running]). */
    protected abstract fun startManager(): ClaudeSessionVirtualFile

    protected abstract fun forgetManager()

    protected fun update() {
        if (gone) return
        // Folders not read yet, or unreadable, say nothing about the folder: wait, keeping what shows.
        if (!service.loaded || service.foldersError != null) return waiting(service.foldersError?.let { "Cannot read the folders: $it" } ?: "Loading...")
        val name = nameNow() ?: return folderGone()
        readError()?.let { return waiting(it) }
        if (title != null && title != name) presentationChanged()
        title = name
        if (shown) return render()
        shown = true
        show(splitter.apply { firstComponent = content() })
        bindManager()
    }

    private fun waiting(text: String) {
        if (!shown) show(notice(text))
    }

    /**
     * Shows the manager stored now, after it was replaced: the pane of the previous one closes, its process already detached.
     * Nothing before the content first showed, which binds the new one then, or once the folder is gone.
     */
    fun showManager() {
        if (gone || pane == null) return
        pane?.let(Disposer::dispose)
        bindManager()
    }

    private fun bindManager() {
        // Filing a new manager notifies the service's listeners, this editor among them, now: content shows already, so it renders.
        val manager = startManager().also { manager = it }
        // Checked whenever a process is about to start, not only for a restored tab; a detached one here is bound as it is.
        val pane = SessionPane(project, manager, checkFirst = !SessionTerminals.getInstance().runs(manager)).also { pane = it }
        Disposer.register(this, pane)
        splitter.secondComponent = pane.component
        render()
        active.tabFileChanged()
    }

    private fun folderGone() {
        gone = true
        show(notice(goneNotice))
        manager?.let(SessionTerminals.getInstance()::close)
        forgetManager()
        pane?.let(Disposer::dispose)
        pane = null
        // Titled by the kind once its folder is gone; Remove Plan keeps the folder's name.
        if (title != null) presentationChanged()
        active.tabFileChanged()
    }

    private fun presentationChanged() = FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)

    private fun show(component: JComponent) {
        root.removeAll()
        root.add(component)
        root.revalidate()
        root.repaint()
    }

    private fun notice(text: String) = JPanel(GridBagLayout()).apply { add(JBLabel(text)) }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = pane?.preferredFocusedComponent ?: root
    override fun getName() = editorName
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
