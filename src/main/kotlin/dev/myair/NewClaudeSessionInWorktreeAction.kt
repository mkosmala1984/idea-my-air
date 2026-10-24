package dev.myair

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import java.util.UUID

/**
 * Starts a session in a new worktree: a branch `worktree-<id>` off a base picked from a popup, checked out in the
 * worktrees directory of [MyAirSettings], with the `.worktreeinclude` files of the main checkout copied in.
 */
class NewClaudeSessionInWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        // A toolbar's data context points at its target component (the tree), so the popup goes under the button instead.
        val button = e.inputEvent?.component?.takeIf { e.isFromActionToolbar }
        val where = if (button != null) RelativePoint(button, Point(0, button.height)) else JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext)
        background(project, "Reading branches", { Git.repo(projectBase(project)) }) { repo ->
            val recent = PropertiesComponent.getInstance(project).getList(RECENT_BASES).orEmpty()
            val choices = baseChoices(repo.branches, repo.default, recent)
            val step = object : BaseListPopupStep<String>("Base Branch for New Worktree", choices.items) {
                override fun isSpeedSearchEnabled() = true
                override fun getDefaultOptionIndex() = choices.selected
                override fun getSeparatorAbove(value: String) = when (choices.items.indexOf(value)) {
                    choices.restStart -> ListSeparator()
                    choices.recentStart -> ListSeparator("Recent")
                    else -> null
                }
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? =
                    doFinalStep { create(project, repo, recent, selectedValue) }
            }
            JBPopupFactory.getInstance().createListPopup(step).show(where)
        }
    }

    private fun create(project: Project, repo: GitRepo, recent: List<String>, base: String) {
        val id = UUID.randomUUID().toString()
        val name = id.take(8)
        val path = worktreePath(repo.mainRoot, MyAirSettings.getInstance().worktreesDir, name)
        background(project, "Creating worktree", {
            Git.addWorktree(repo.mainRoot, path, "worktree-$name", base)
            copyIncluded(repo.mainRoot, path, Git.includedFiles(repo.mainRoot))
        }) {
            PropertiesComponent.getInstance(project).setList(RECENT_BASES, pushRecent(recent, base, repo.branches))
            ClaudeSessionOpener.startNew(project, path, id)
        }
    }

    /** Runs [work] off the EDT, then [done] with its result on the EDT; a failure shows its message. */
    private fun <T> background(project: Project, title: String, work: () -> T, done: (T) -> Unit) =
        object : Task.Backgroundable(project, title) {
            private var result: Result<T>? = null
            override fun run(indicator: ProgressIndicator) {
                result = Result.success(work())
            }
            override fun onSuccess() = done(result!!.getOrThrow())
            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, error.message, title)
            }
        }.queue()

    private companion object {
        const val RECENT_BASES = "dev.myair.worktreeBases"
    }
}
