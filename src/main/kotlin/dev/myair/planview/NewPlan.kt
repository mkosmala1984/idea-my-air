package dev.myair.planview

import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.folders.ChangeSource
import dev.myair.folders.Plan
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.PlanManager
import java.nio.file.Path
import java.util.UUID

/**
 * A new plan named [name], in the folder [dev.myair.folders.SessionFolders.planParentFor] gives [sessionProject], managed by
 * the new session [id], which starts in [cwd] with [args] (flags, then a first prompt after `--`) when its plan tab, opened
 * in [project], starts it.
 */
fun startAsPlan(project: Project, name: String, sessionProject: Path, cwd: Path, id: String, args: List<String>) {
    ThreadingAssertions.assertEventDispatchThread()
    require(name.isNotBlank()) { "blank plan name" }
    val service = ClaudeSessionsService.getInstance()
    val folderId = UUID.randomUUID().toString()
    service.updateFolders(ChangeSource.UI) { it.createFolder(it.planParentFor(sessionProject), folderId, name, plan = Plan()).assign(id, folderId) }
    check(service.folders.find(folderId)?.plan != null) { "plan folder $folderId was not created" }
    PlanFiles.getInstance().adopt(folderId, PlanManager(id, cwd, sessionProject), null, args)
    PlanOpener.open(project, folderId)
}
