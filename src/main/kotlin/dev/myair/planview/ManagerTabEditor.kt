package dev.myair.planview

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.SessionPane
import dev.myair.editor.SessionPaneHost
import dev.myair.editor.SessionTerminals
import dev.myair.service.ActiveSession
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.LatestRead
import dev.myair.store.ProfileStore
import dev.myair.tabs.SessionHosts
import dev.myair.tabs.SessionTab
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.GridBagLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tab of session [sessionId] here (a session tab, or the file of a process running on detached, [SessionHosts.findTab]),
 * and its file when its process runs here: what a session made a folder's manager brings along.
 */
fun sessionTabAndProcess(sessionId: String): Pair<SessionTab?, ClaudeSessionVirtualFile?> {
    val tab = SessionHosts.findTab(sessionId)?.takeIf { it.file is ClaudeSessionVirtualFile }
    return tab to (tab?.file as? ClaudeSessionVirtualFile)?.takeIf(SessionTerminals.getInstance()::runs)
}

/**
 * After the session of [tab] became the manager of this folder tab: sends [message] to its [running] process, which the
 * tab's pane holds now, then closes the session's tab, leaving the process running; a tab not started yet would start a
 * second one when selected. A detached process has no tab to close.
 */
fun ManagerTabEditor.takeOver(tab: SessionTab?, running: ClaudeSessionVirtualFile?, message: String) {
    if (running != null) check(pane?.send(message) == true) { "the folder's tab shows no process of session ${running.sessionId}" }
    tab?.let { FileEditorManager.getInstance(it.project).closeFile(it.file) }
}

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

    /**
     * Why the folder's content cannot be read now, null when it can; asked only while [nameNow] gives a name. It holds the
     * tab back only before the content first showed; after that [render] shows it.
     */
    protected open fun readError(): String? = null

    protected abstract val goneNotice: String

    /** The left side, built once, before the manager starts. */
    protected abstract fun content(): JComponent

    /** Shows the folder as it is now on the left side, once [content] was built. */
    protected abstract fun render()

    /** The manager's file, kept while its process runs, else built again from its stored entry ([FolderTabs.running]). */
    protected abstract fun startManager(): ClaudeSessionVirtualFile

    /** The profile to check before [startManager] ([FolderTabs.profileToCheck]); null when nothing is to be checked. */
    protected abstract fun profileToCheck(): String?

    // The profile check reads profiles.json and .claude.json (megabytes), so off the EDT; only the latest lands, none once disposed or gone.
    private val profileChecks = LatestRead.onPool<String, String?>(
        AppExecutorUtil.getAppExecutorService(), { ModalityState.stateForComponent(root) }, { managerProfileRefusal(it, ProfileStore.getInstance()) },
    ) { _, refusal -> refusal.getOrThrow()?.let(::refuse) ?: bind() }

    protected abstract fun forgetManager()

    protected fun update() {
        if (gone) return
        // Folders not read yet, or unreadable, say nothing about the folder: wait, keeping what shows.
        if (!service.loaded || service.foldersError != null) return waiting(service.foldersError?.let { "Cannot read the folders: $it" } ?: "Loading...")
        val name = nameNow() ?: return folderGone()
        if (!shown) readError()?.let { return waiting(it) }
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
        if (gone || !shown) return
        pane?.let(Disposer::dispose)
        bindManager()
    }

    private fun bindManager() {
        // A stored entry that cannot be read is shown as a refusal too, never thrown into the service's listeners; it is read
        // only while no process runs, so the file [refuse] drops is never a running manager's.
        val profile = try {
            profileToCheck()
        } catch (e: Exception) {
            return refuse(e.message ?: e.toString())
        } ?: return bind()
        manager = null
        pane = null
        splitter.secondComponent = notice("Checking profile $profile...")
        profileChecks.request(profile)
    }

    /**
     * A refused start shows its reason where the terminal would be and starts nothing; Try Again asks again. The manager's
     * file is dropped, so the tab no longer counts as that session's.
     */
    private fun refuse(reason: String) {
        manager = null
        pane = null
        forgetManager()
        splitter.secondComponent = refused(reason)
        render()
        active.tabFileChanged()
    }

    private fun bind() {
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
        profileChecks.forget()
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

    // A text area, so a command it names can be selected and copied.
    private fun refused(reason: String) = JPanel(GridBagLayout()).apply {
        add(panel {
            row { cell(JBTextArea(reason).apply { isEditable = false; isOpaque = false }) }
            row { button("Try Again") { bindManager() } }
        })
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = pane?.preferredFocusedComponent ?: root
    override fun getName() = editorName
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() = profileChecks.forget()
}
