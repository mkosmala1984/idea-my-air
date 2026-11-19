package dev.myair.schedule.ui

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.LightVirtualFile
import dev.myair.service.ClaudeSessionsService

/** The registry's tab; one per IDE ([ScheduledTasksFiles.file]), and a [LightVirtualFile] compares by identity. */
class ScheduledTasksVirtualFile : LightVirtualFile("Scheduled Tasks", FileTypes.PLAIN_TEXT, ""), EditorHistoryManager.OptionallyIncluded {
    init {
        isWritable = false
    }

    override fun getFileSystem() = ScheduledTasksFileSystem.instance

    /** `myair-scheduled://tasks`, saved with the open tabs and resolved on startup. */
    override fun getPath() = PATH

    override fun isIncludedInEditorHistory(project: Project) = false

    companion object {
        const val PATH = "tasks"
    }
}

/** Restores the registry tab after a restart as the IDE's one [ScheduledTasksVirtualFile]. */
class ScheduledTasksFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol() = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile {
        require(path == ScheduledTasksVirtualFile.PATH) { "not a scheduled tasks path: $path" }
        ClaudeSessionsService.getInstance()
        return ScheduledTasksFiles.getInstance().file
    }

    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    companion object {
        const val PROTOCOL = "myair-scheduled"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as ScheduledTasksFileSystem
    }
}

@Service
class ScheduledTasksFiles {
    val file = ScheduledTasksVirtualFile()

    companion object {
        fun getInstance(): ScheduledTasksFiles = service()
    }
}

/** Opens the registry in [project] (null: the first open one), or brings forward the window already showing it. */
fun openScheduledTasks(project: Project?) {
    val file = ScheduledTasksFiles.getInstance().file
    val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDefault }
    val holder = projects.firstOrNull { FileEditorManager.getInstance(it).isFileOpen(file) }
    if (holder != null) ProjectUtil.focusProjectWindow(holder, true)
    FileEditorManager.getInstance(holder ?: project ?: projects.firstOrNull() ?: return).openFile(file, true)
}

class OpenScheduledTasksAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) = openScheduledTasks(e.project)
}

/** Closes a registry tab opened, or restored, in a window while another window already shows it. */
class ScheduledTasksSingleTab(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file !is ScheduledTasksVirtualFile) return
        val elsewhere = ProjectManager.getInstance().openProjects.any { it != project && !it.isDisposed && FileEditorManager.getInstance(it).isFileOpen(file) }
        if (elsewhere) ApplicationManager.getApplication().invokeLater({ source.closeFile(file) }, project.disposed)
    }
}
