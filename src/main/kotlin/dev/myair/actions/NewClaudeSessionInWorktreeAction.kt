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
import dev.myair.editor.PlanStart
import dev.myair.editor.projectBase
import dev.myair.prompt.showNewBranchPopup
import dev.myair.service.ClaudeSessionsService
import java.awt.Point
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/** Starts a session in a new worktree of the project's repository, off a base picked from a popup. */
class NewClaudeSessionInWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) =
        startInWorktree(e.project!!, projectBase(e.project!!), null, emptyList(), popupPoint(e), askBranch = true)
}

/** Under the toolbar button [e] came from; a toolbar's data context points at its target component (the tree), not the button. */
fun popupPoint(e: AnActionEvent): RelativePoint {
    val button = e.inputEvent?.component?.takeIf { e.isFromActionToolbar }
    return if (button != null) RelativePoint(button, Point(0, button.height)) else JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext)
}

/**
 * Starts `claude` with [args] in a new worktree of the repository of [dir] on the local branch of [base], or of a base
 * picked from a popup at [where] when [base] is null ([Git.addWorktree]; a branch it cannot free from another worktree
 * gives a new branch `worktree-<base>-<date>-<id4>` off it), in the worktrees directory of [MyAirSettings],
 * with the `.worktreeinclude` files of the main checkout copied in. A [branch] given is made off the base instead
 * ([Git.addBranchWorktree]) in `<worktrees dir>/<branch>`; with [askBranch] a popup offers that after the base, its name
 * typed or suggested in [showNewBranchPopup]. The session belongs to [dir], and to [plan] if given.
 */
fun startInWorktree(
    project: Project, dir: Path, base: String?, args: List<String>, where: RelativePoint, plan: PlanStart? = null,
    branch: String? = null, askBranch: Boolean = false, folderId: String? = plan?.folderId,
) = background(project, "Reading branches", { Git.repo(dir) }) { repo ->
    require(branch == null || !askBranch) { "a branch named and asked for" }
    fun create(base: String, branch: String?, prompt: String? = null) {
        require(prompt == null || "--" !in args) { "a first message in args and in the branch popup" }
        val id = UUID.randomUUID().toString()
        val running = runningDirs()
        val claudeArgs = if (prompt == null) args else args + listOf("--", prompt)
        background(project, "Creating worktree", { createWorktree(repo, base, branch, id, running) }) { path ->
            rememberBase(repo, base)
            ClaudeSessionOpener.startNew(project, dir, path, id, claudeArgs, plan, folderId)
        }
    }
    fun picked(base: String) =
        if (askBranch) branchPopup(project, dir, base) { branch, prompt -> create(base, branch, prompt) }.show(where) else create(base, branch)
    if (base != null) picked(base)
    else basePopup("Base Branch for New Worktree", baseChoices(repo.branches, repo.default, recentBases(repo.mainRoot)), ::picked).show(where)
}

/**
 * A new worktree of [repo] for session [id], on the local branch of [base] ([Git.addWorktree], falling back to a new
 * branch `worktree-<name>`), or on a new [branch] off [base] ([Git.addBranchWorktree]), with the `.worktreeinclude` files
 * copied in. A worktree holding the branch is not removed while a directory of [running] lies in it. Off the EDT.
 */
fun createWorktree(repo: GitRepo, base: String, branch: String?, id: String, running: List<Path>): Path {
    require(base in repo.branches) { "Branch $base is not in ${repo.mainRoot}." }
    val name = branch?.let(::dirName) ?: worktreeName(base, LocalDate.now(), id)
    val path = worktreePath(repo.mainRoot, MyAirSettings.getInstance().worktreesDir, name)
    if (branch != null) Git.addBranchWorktree(repo.mainRoot, path, branch, base)
    else Git.addWorktree(repo.mainRoot, path, base, "worktree-$name") { tree -> running.any { it.startsWith(tree) } }
    copyIncluded(repo.mainRoot, path, Git.includedFiles(repo.mainRoot))
    return path
}

/** The directories running sessions started in and work in now. EDT. */
fun runningDirs(): List<Path> {
    val sessions = ClaudeSessionsService.getInstance()
    return sessions.sessions.filter { it.id in sessions.live }.flatMap { listOf(it.cwd, it.workDir) }
}

/** Puts [base] first among the recent bases of [repo]. EDT. */
fun rememberBase(repo: GitRepo, base: String) =
    PropertiesComponent.getInstance().setList(recentKey(repo.mainRoot), pushRecent(recentBases(repo.mainRoot), base, repo.branches))

/**
 * Check out [base] (nulls to [chosen]), or a new branch off it named in [showNewBranchPopup], whose task [chosen] gets
 * as the session's first message.
 */
private fun branchPopup(project: Project, dir: Path, base: String, chosen: (String?, String?) -> Unit): ListPopup =
    JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<Boolean>("Worktree Branch", listOf(false, true)) {
        override fun getTextFor(value: Boolean) = if (value) "New branch off $base..." else "Check out $base"
        override fun onChosen(selectedValue: Boolean, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
            if (!selectedValue) chosen(null, null) else showNewBranchPopup(project, dir, base, chosen)
        }
    })

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
