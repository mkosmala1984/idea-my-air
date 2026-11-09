package dev.myair.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.service.ClaudeSessionsService
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class ClaudeSessionEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ClaudeSessionVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ClaudeSessionEditor(project, file as ClaudeSessionVirtualFile)
    override fun getEditorTypeId() = "claude-session"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** A session tab: a header naming its session above its [SessionPane], which a tab restored on startup checks before starting. */
class ClaudeSessionEditor(project: Project, private val file: ClaudeSessionVirtualFile) : UserDataHolderBase(), FileEditor, SessionPaneHost {
    override val pane = SessionPane(project, file, checkFirst = file.restored).also { Disposer.register(this, it) }
    private val header = SessionHeader { updateHeader() }
    private val root = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(pane.component, BorderLayout.CENTER)
    }

    init {
        ClaudeSessionsService.getInstance().subscribe(this, ::updateHeader)
        updateHeader()
    }

    /** The session the tab shows now ([ClaudeSessionVirtualFile.sessionId] follows `/clear`); before the first scan only the file's title. */
    private fun updateHeader() {
        val service = ClaudeSessionsService.getInstance()
        val session = service.listed.firstOrNull { it.id == file.sessionId }
        header.show(if (session == null) HeaderText(file.title, file.project, null, null, null) else headerText(session, service.branchOf(session)))
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = pane.preferredFocusedComponent
    override fun getName() = "Claude"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
