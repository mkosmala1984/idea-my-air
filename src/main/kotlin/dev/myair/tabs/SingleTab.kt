package dev.myair.tabs

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile

/** A tab the IDE holds once, in one window: the sessions dashboard, the scheduled tasks. */
interface SingleTabFile

/** Opens [file] in [project] (null: the first open one), or brings forward the window already showing it. */
fun openSingleTab(file: VirtualFile, project: Project?) {
    require(file is SingleTabFile) { "${file.name} is not a single tab" }
    val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDefault }
    val holder = projects.firstOrNull { FileEditorManager.getInstance(it).isFileOpen(file) }
    if (holder != null) ProjectUtil.focusProjectWindow(holder, true)
    FileEditorManager.getInstance(checkNotNull(holder ?: project ?: projects.firstOrNull()) { "no open project to show ${file.name} in" }).openFile(file, true)
}

/** Closes a single tab opened, or restored, in a window while another window already shows it. */
class SingleTabListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file !is SingleTabFile) return
        val elsewhere = ProjectManager.getInstance().openProjects.any { it != project && !it.isDisposed && FileEditorManager.getInstance(it).isFileOpen(file) }
        if (elsewhere) ApplicationManager.getApplication().invokeLater({ source.closeFile(file) }, project.disposed)
    }
}
