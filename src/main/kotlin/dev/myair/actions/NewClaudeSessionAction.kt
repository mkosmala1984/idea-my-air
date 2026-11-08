package dev.myair.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.PlanStart
import java.nio.file.Path
import kotlin.io.path.isDirectory

class NewClaudeSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = ClaudeSessionOpener.startNew(e.project!!)
}

/**
 * On a project's, folder's or worktree's menu: a session in [dir], the selected node's directory, shown only where it has
 * one, of the project [home] (a worktree's main checkout, else [dir]); in [plan], the selected plan row's plan and task, when there is one; filed in [folder], the selected folder, when it is one.
 */
class NewSessionInDirAction(
    text: String, inWorktree: Boolean, private val dir: () -> Path?, private val home: () -> Path?, private val plan: () -> PlanStart?,
    private val folder: () -> String?,
) : DumbAwareAction(text, null, if (inWorktree) AllIcons.Vcs.Branch else AllIcons.General.Add) {
    private val start: (AnActionEvent, Path, PlanStart?, String?) -> Unit =
        if (inWorktree) { e, dir, plan, folderId -> startInWorktree(e.project!!, dir, null, emptyList(), popupPoint(e), plan, askBranch = true, folderId = folderId) }
        else { e, dir, plan, folderId -> ClaudeSessionOpener.startNew(e.project!!, home()!!, dir, plan = plan, folderId = folderId) }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val dir = dir()
        e.presentation.isVisible = dir != null
        // A removed worktree's row stays until its sessions go.
        e.presentation.isEnabled = dir != null && dir.isDirectory()
    }
    override fun actionPerformed(e: AnActionEvent) = plan().let { start(e, dir()!!, it, folder() ?: it?.folderId) }
}
