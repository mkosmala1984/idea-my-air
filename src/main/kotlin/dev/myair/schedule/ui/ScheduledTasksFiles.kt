package dev.myair.schedule.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.LightVirtualFile
import dev.myair.service.ClaudeSessionsService
import dev.myair.tabs.SingleTabFile
import dev.myair.tabs.openSingleTab

/** The registry's tab; one per IDE ([ScheduledTasksFiles.file]), and a [LightVirtualFile] compares by identity. */
class ScheduledTasksVirtualFile : LightVirtualFile("Scheduled Tasks", FileTypes.PLAIN_TEXT, ""), EditorHistoryManager.OptionallyIncluded, SingleTabFile {
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
fun openScheduledTasks(project: Project?) = openSingleTab(ScheduledTasksFiles.getInstance().file, project)

class OpenScheduledTasksAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) = openScheduledTasks(e.project)
}

