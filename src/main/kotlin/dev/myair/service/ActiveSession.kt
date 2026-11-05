package dev.myair.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.editor.ClaudeSessionVirtualFile

/** [selected] when it is a session tab, else [last] while [isOpen]: a file opened beside a session keeps it active. */
internal fun <T> activeTab(selected: T?, last: T?, isOpen: (T) -> Boolean): T? = selected ?: last?.takeIf(isOpen)

/**
 * The session tab of [project]'s window that Session Context and Branch Diff describe ([activeTab]). Notifies on the EDT
 * when the tab or its session changes: another tab, `/clear` switching the tab's session, any change of its transcript,
 * its work tree's head or its live entry.
 */
@Service(Service.Level.PROJECT)
class ActiveSession(private val project: Project) : Disposable {
    /** Written on the EDT, read also by the Branch Diff provider off it. */
    @Volatile
    var tab: ClaudeSessionVirtualFile? = null
        private set

    /** Counts [baseChanged] calls: a stored base is in no file the service reads, so its change is announced by hand. */
    var baseGeneration = 0
        private set

    private val service = ClaudeSessionsService.getInstance()
    private val listeners = ArrayList<() -> Unit>()
    /** What listeners were last notified about. */
    private var notified: Any? = null

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = update()
            // Closing the remembered tab while another file is selected changes no selection.
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = update()
        })
        service.subscribe(this, ::update)
        update()
    }

    /** Calls [listener] on the EDT after every change, until [parent] is disposed. */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Tells listeners that the base stored for a branch changed (`Git.setBase`), so they read it again. */
    fun baseChanged() {
        ThreadingAssertions.assertEventDispatchThread()
        baseGeneration++
        update()
    }

    private fun update() {
        ThreadingAssertions.assertEventDispatchThread()
        val editors = FileEditorManager.getInstance(project)
        tab = activeTab(editors.selectedFiles.firstOrNull() as? ClaudeSessionVirtualFile, tab) { editors.isFileOpen(it) }
        val session = service.sessions.firstOrNull { it.id == tab?.sessionId }
        val shown = listOf(tab, tab?.sessionId, session, session?.let { service.heads[it.workDir] }, session?.let { service.live[it.id] }, baseGeneration)
        if (shown == notified) return
        notified = shown
        listeners.toList().forEach { it() }
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): ActiveSession = project.service()
    }
}
