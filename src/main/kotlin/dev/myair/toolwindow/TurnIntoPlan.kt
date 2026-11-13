package dev.myair.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.SessionTerminals
import dev.myair.folders.ChangeSource
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.planview.PlanFiles
import dev.myair.planview.PlanOpener
import dev.myair.planview.PlanEditor
import dev.myair.editor.planPrompt
import dev.myair.MyAirSettings
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.PlanManager
import dev.myair.tabs.SessionHosts
import java.util.UUID

/** Whether a plan may go where [session] shows (`placeOf`, the top level when nowhere): not in a plan, as plans do not nest. */
private fun canHoldPlan(folders: SessionFolders, session: ClaudeSession) = folders.placeOf(session)?.let { folders.planFolderAt(it) == null } ?: true

/**
 * On a session's menu: a new plan folder, named in a dialog (the session's title to start with), in the folder the session
 * shows in, managed by the session. Its process in this IDE moves into the plan tab, whose pane takes it before the
 * session's tab closes, so it runs on; a session without one is resumed there, its tab here closed. Not for a session managing a plan already.
 */
class TurnIntoPlanAction(private val selected: () -> List<ClaudeSession>) :
    DumbAwareAction("Turn into Plan...", "A new plan in this session's folder, managed by this session", AllIcons.Nodes.Target) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val session = selected().singleOrNull()
        e.presentation.isVisible = session != null
        e.presentation.isEnabled = session != null && canHoldPlan(ClaudeSessionsService.getInstance().folders, session) &&
            PlanFiles.getInstance().store.read().folderOf(session.id) == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        val session = selected().single()
        val service = ClaudeSessionsService.getInstance()
        check(canHoldPlan(service.folders, session)) { "session ${session.id} lies in a plan" }
        val parentId = service.folders.placeOf(session)
        val name = askFolderName(project, "Turn into Plan", session.title, parentId, null) ?: return
        val tab = SessionHosts.findTab(session.id)?.takeIf { it.file is ClaudeSessionVirtualFile }
        val running = (tab?.file as? ClaudeSessionVirtualFile)?.takeIf(SessionTerminals.getInstance()::runs)
        val folderId = UUID.randomUUID().toString()
        service.updateFolders(ChangeSource.UI) { it.createFolder(parentId, folderId, name, plan = Plan()).assign(session.id, folderId) }
        // A running process keeps the system prompt it started with, and a resumed one keeps the recorded one until it
        // compacts, so the session learns of its plan from a message, which stays in its transcript.
        val folders = service.folders
        val message = planPrompt(folders, checkNotNull(folders.find(folderId)), null, MyAirSettings.getInstance().briefs, manager = true)
        val files = PlanFiles.getInstance()
        files.adopt(folderId, PlanManager(session.id, running?.cwd ?: session.cwd, running?.project ?: session.project), running, message)
        PlanOpener.open(project, folderId)
        if (running != null) {
            val pane = FileEditorManager.getInstance(project).getAllEditors(files.file(folderId)).filterIsInstance<PlanEditor>().single().pane
            check(pane?.send(message) == true) { "the plan tab of $folderId shows no process of session ${session.id}" }
        }
        // The plan tab's pane holds the process now, so closing the session's tab leaves it running; a tab not started yet
        // would start a second one when selected. A detached process has no tab to close.
        tab?.let { FileEditorManager.getInstance(it.project).closeFile(it.file) }
    }
}
