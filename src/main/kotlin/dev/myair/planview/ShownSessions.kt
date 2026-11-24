package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.folders.SessionFolders
import dev.myair.canvasview.CanvasTabs
import dev.myair.canvasview.CanvasVirtualFile
import dev.myair.reviewview.ReviewTabs
import dev.myair.reviewview.ReviewVirtualFile
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.PlanManagers

/** Whether folder [folderId] is there and holds a plan, which is when its manager manages one. */
fun hasPlan(structure: SessionFolders, folderId: String) = structure.find(folderId)?.plan != null

/** The plan folder whose manager is session [sessionId], while that folder still holds a plan; else null. */
fun managedPlanFolder(managers: PlanManagers, structure: SessionFolders, sessionId: String): String? =
    managers.folderOf(sessionId)?.takeIf { hasPlan(structure, it) }

/**
 * The session [file] shows: a session tab's own file, a plan, review or canvas tab's manager (none before it first starts,
 * nor once its folder holds no plan or is gone, so a tab left showing that its plan, review or canvas no longer exists
 * shows no session), else null. A review or canvas is gone with its folder: `review.json` and `canvas.json` stay.
 */
fun sessionFileOf(file: VirtualFile?): ClaudeSessionVirtualFile? = when (file) {
    is ClaudeSessionVirtualFile -> file
    is PlanVirtualFile -> PlanFiles.getInstance().manager(file.folderId)?.takeIf { hasPlan(ClaudeSessionsService.getInstance().folders, file.folderId) }
    is ReviewVirtualFile -> ReviewTabs.getInstance().tabs.manager(file.folderId)?.takeIf { ClaudeSessionsService.getInstance().folders.find(file.folderId) != null }
    is CanvasVirtualFile -> CanvasTabs.getInstance().tabs.manager(file.folderId)?.takeIf { ClaudeSessionsService.getInstance().folders.find(file.folderId) != null }
    else -> null
}

/** The session files the open tabs of [editors] show: session tabs and the managers of plan, review and canvas tabs. */
fun shownSessionFiles(editors: FileEditorManager): List<ClaudeSessionVirtualFile> = editors.openFiles.mapNotNull(::sessionFileOf)

/** The tab that shows [file]: its plan's, review's or canvas's tab for a manager, which is never opened itself, else the file. */
fun tabFileOf(file: ClaudeSessionVirtualFile): VirtualFile =
    PlanFiles.getInstance().planOf(file) ?: ReviewTabs.getInstance().reviewOf(file) ?: CanvasTabs.getInstance().canvasOf(file) ?: file
