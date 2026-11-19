package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.folders.SessionFolders
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
 * The session [file] shows: a session tab's own file, a plan or review tab's manager (none before it first starts, nor
 * once its folder holds no plan or is gone, so a tab left showing that its plan or review no longer exists shows no
 * session), else null. A review is gone with its folder: `review.json` stays.
 */
fun sessionFileOf(file: VirtualFile?): ClaudeSessionVirtualFile? = when (file) {
    is ClaudeSessionVirtualFile -> file
    is PlanVirtualFile -> PlanFiles.getInstance().manager(file.folderId)?.takeIf { hasPlan(ClaudeSessionsService.getInstance().folders, file.folderId) }
    is ReviewVirtualFile -> ReviewTabs.getInstance().tabs.manager(file.folderId)?.takeIf { ClaudeSessionsService.getInstance().folders.find(file.folderId) != null }
    else -> null
}

/** The session files the open tabs of [editors] show: session tabs and the managers of plan and review tabs. */
fun shownSessionFiles(editors: FileEditorManager): List<ClaudeSessionVirtualFile> = editors.openFiles.mapNotNull(::sessionFileOf)

/** The tab that shows [file]: its plan's or review's tab for a manager, which is never opened itself, else the file. */
fun tabFileOf(file: ClaudeSessionVirtualFile): VirtualFile = PlanFiles.getInstance().planOf(file) ?: ReviewTabs.getInstance().reviewOf(file) ?: file
