package dev.myair.dashboard

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

/** The dashboard's tab; one per IDE ([DashboardFiles.file]), and a [LightVirtualFile] compares by identity. */
class DashboardVirtualFile : LightVirtualFile("Sessions", FileTypes.PLAIN_TEXT, ""), EditorHistoryManager.OptionallyIncluded, SingleTabFile {
    init {
        isWritable = false
    }

    override fun getFileSystem() = DashboardFileSystem.instance

    /** `myair-dashboard://sessions`, saved with the open tabs and resolved on startup. */
    override fun getPath() = PATH

    override fun isIncludedInEditorHistory(project: Project) = false

    companion object {
        const val PATH = "sessions"
    }
}

/** Restores the dashboard tab after a restart as the IDE's one [DashboardVirtualFile]. */
class DashboardFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol() = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile {
        require(path == DashboardVirtualFile.PATH) { "not a dashboard path: $path" }
        ClaudeSessionsService.getInstance()
        return DashboardFiles.getInstance().file
    }

    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    companion object {
        const val PROTOCOL = "myair-dashboard"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as DashboardFileSystem
    }
}

@Service
class DashboardFiles {
    val file = DashboardVirtualFile()

    companion object {
        fun getInstance(): DashboardFiles = service()
    }
}

/** Opens the dashboard, or brings forward the window that already shows it: the IDE holds one dashboard tab. */
class OpenDashboardAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) = openSingleTab(DashboardFiles.getInstance().file, e.project)
}

