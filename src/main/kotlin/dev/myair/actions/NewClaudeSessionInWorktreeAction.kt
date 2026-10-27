package dev.myair.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import dev.myair.MyAirSettings
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.projectBase
import java.awt.Point
import java.nio.file.Path
import java.util.UUID

/** Starts a session in a new worktree of the project's repository, off a base picked from a popup. */
class NewClaudeSessionInWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) = startInWorktree(e.project!!, projectBase(e.project!!), null, emptyList(), popupPoint(e))
}

/** Under the toolbar button [e] came from; a toolbar's data context points at its target component (the tree), not the button. */
fun popupPoint(e: AnActionEvent): RelativePoint {
    val button = e.inputEvent?.component?.takeIf { e.isFromActionToolbar }
    return if (button != null) RelativePoint(button, Point(0, button.height)) else JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext)
}

/**
 * Starts `claude` with [args] in a new worktree of the repository of [dir]: a branch `worktree-<id>` off [base], or off
 * a base picked from a popup at [where] when [base] is null, checked out in the worktrees directory of [MyAirSettings],
 * with the `.worktreeinclude` files of the main checkout copied in. The session belongs to [dir].
 */
fun startInWorktree(project: Project, dir: Path, base: String?, args: List<String>, where: RelativePoint) =
    background(project, "Reading branches", { Git.repo(dir) }) { repo ->
        val recent = recentBases(repo.mainRoot)
        fun create(base: String) {
            val id = UUID.randomUUID().toString()
            val name = id.take(8)
            val path = worktreePath(repo.mainRoot, MyAirSettings.getInstance().worktreesDir, name)
            background(project, "Creating worktree", {
                Git.addWorktree(repo.mainRoot, path, "worktree-$name", base)
                copyIncluded(repo.mainRoot, path, Git.includedFiles(repo.mainRoot))
            }) {
                PropertiesComponent.getInstance().setList(recentKey(repo.mainRoot), pushRecent(recent, base, repo.branches))
                ClaudeSessionOpener.startNew(project, dir, path, id, args)
            }
        }
        if (base != null) {
            if (base in repo.branches) create(base) else Messages.showErrorDialog(project, "Branch $base is not in ${repo.mainRoot}.", "Cannot Create Worktree")
            return@background
        }
        basePopup("Base Branch for New Worktree", baseChoices(repo.branches, repo.default, recent), ::create).show(where)
    }

/** A searchable list of [choices], the recent ones under a separator; [chosen] gets the pick. */
fun basePopup(title: String, choices: BaseChoices, chosen: (String) -> Unit): ListPopup =
    JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>(title, choices.items) {
        override fun isSpeedSearchEnabled() = true
        override fun getDefaultOptionIndex() = choices.selected
        override fun getSeparatorAbove(value: String) = when (choices.items.indexOf(value)) {
            choices.restStart -> ListSeparator()
            choices.recentStart -> ListSeparator("Recent")
            else -> null
        }
        override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? = doFinalStep { chosen(selectedValue) }
    })

/** Runs [work] off the EDT, then [done] with its result on the EDT; a failure shows its message. */
internal fun <T> background(project: Project, title: String, work: () -> T, done: (T) -> Unit) =
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

/** Recent bases per repository, in the application's properties: a preset may start sessions in any repository. */
private fun recentKey(mainRoot: Path) = "dev.myair.worktreeBases:$mainRoot"

fun recentBases(mainRoot: Path): List<String> = PropertiesComponent.getInstance().getList(recentKey(mainRoot)).orEmpty()
