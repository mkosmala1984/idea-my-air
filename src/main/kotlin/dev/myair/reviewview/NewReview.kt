package dev.myair.reviewview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.MyAirSettings
import dev.myair.actions.BaseChoices
import dev.myair.actions.Git
import dev.myair.actions.Worktree
import dev.myair.actions.background
import dev.myair.actions.basePopup
import dev.myair.actions.copyIncluded
import dev.myair.actions.declineProjectMcpServers
import dev.myair.actions.dirName
import dev.myair.actions.parseBranches
import dev.myair.actions.popupPoint
import dev.myair.canvas.CanvasService
import dev.myair.folders.ChangeSource
import dev.myair.folders.SessionFolders
import dev.myair.review.ReviewService
import dev.myair.service.ClaudeSessionsService
import dev.myair.prompt.profileLabel
import dev.myair.store.BranchReview
import dev.myair.store.DEFAULT_PROFILE
import dev.myair.store.PlanManager
import dev.myair.store.Profile
import dev.myair.store.ProfileStore
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory

/** Where a new review folder goes: under [parent] (null: the top level), linked to the plan folder [plan] when it came from one. */
data class ReviewPlace(val parent: String?, val plan: String?)

/**
 * A review started from [place]: into it, or beside the outermost review folder of [reviews] or canvas folder of
 * [canvases] it is or lies in, as a review lies in neither; then beside the plan folder that place is or lies in, linked
 * to it, since a plan folder holds no review.
 */
fun reviewParent(structure: SessionFolders, reviews: Set<String>, place: String?, canvases: Set<String> = emptySet()): ReviewPlace {
    if (place == null) return ReviewPlace(null, null)
    require(structure.find(place) != null) { "folder $place is gone" }
    fun parentOf(id: String) = structure.ancestry(id).dropLast(1).lastOrNull()?.id
    val holder = structure.ancestry(place).firstOrNull { it.id in reviews || it.id in canvases }
    val outside = if (holder == null) place else parentOf(holder.id)
    val plan = outside?.let(structure::planFolderAt) ?: return ReviewPlace(outside, null)
    return ReviewPlace(parentOf(plan.id), plan.id)
}

/** `Review: <branch>`, a `/` as `-` since folder paths are joined by `/`; a name taken under [parent] gets ` (2)`, ` (3)`... */
fun reviewFolderName(structure: SessionFolders, parent: String?, branch: String): String {
    val name = "Review: ${branch.replace('/', '-')}"
    return (sequenceOf(name) + generateSequence(2) { it + 1 }.map { "$name ($it)" }).first { !structure.nameTaken(parent, it) }
}

/**
 * The work tree a review of [branch] runs in, and whether it must be made: the linked worktree of [linked] holding the
 * branch, else the main checkout [mainRoot] when it holds it ([mainBranch]), else a new `review-<branch>` in [worktreesDir].
 */
fun reviewDir(linked: List<Worktree>, mainRoot: Path, mainBranch: String?, branch: String, worktreesDir: Path): Pair<Path, Boolean> {
    linked.firstOrNull { it.branch == branch }?.let { return it.path to false }
    if (mainBranch == branch) return mainRoot to false
    return worktreesDir.resolve("review-${dirName(branch)}") to true
}

/**
 * [reviewDir] in the repository of [dirHint], [worktreesSetting] resolved against its main checkout; a new worktree checks
 * the local [branch] out, gets the `.worktreeinclude` files and declines the project's MCP servers, as a session's does.
 * A branch missing locally throws with git's message. Off the EDT.
 */
fun reviewWorkTree(dirHint: Path, branch: String, worktreesSetting: String): Path {
    val mainRoot = Git.mainRoot(dirHint)
    val trees = Git.worktrees(mainRoot)
    val (dir, new) = reviewDir(trees.drop(1), trees.first().path, trees.first().branch, branch, mainRoot.resolve(worktreesSetting))
    if (new) {
        // worktree add would make a missing branch off a remote namesake; the review is of the local branch only.
        Git.run(mainRoot, "rev-parse", "--verify", "refs/heads/$branch")
        Git.run(mainRoot, "worktree", "add", dir.toString(), branch)
        copyIncluded(mainRoot, dir, Git.includedFiles(mainRoot))
        declineProjectMcpServers(dir)
    }
    return dir
}

/** A PR number typed: null when empty; anything but a positive number in plain digits throws. */
fun prNumber(input: String): Int? {
    if (input.isEmpty()) return null
    val n = input.takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    require(n != null && n > 0) { "'$input' is not a PR number" }
    return n
}

/**
 * Makes the review of [branch] in the repository of [dirHint]: its work tree ([reviewWorkTree]) and base ([Git.baseOf])
 * read off the EDT, then `review.json` and the folder `Review: <branch>` where [reviewParent] puts a review started from
 * [place], in that order, so a failed structure write leaves an ignored file rather than an empty folder; then opens it.
 * Its manager is stored at once, a new session of [profile] in the work tree, its project the main checkout, filed in the
 * folder; the tab starts it. EDT.
 */
fun startReview(project: Project, branch: String, dirHint: Path, place: String?, pr: Int?, profile: Profile) {
    ThreadingAssertions.assertEventDispatchThread()
    val setting = MyAirSettings.getInstance().worktreesDir
    background(project, "Preparing review of $branch", { reviewWorkTree(dirHint, branch, setting).let { Triple(it, Git.baseOf(it), Git.mainCheckout(it)) } }) { (dir, base, main) ->
        val service = ClaudeSessionsService.getInstance()
        val reviews = ReviewService.getInstance().reviews.keys
        val canvases = CanvasService.getInstance().canvases.keys
        val target = reviewParent(service.folders, reviews, place, canvases)
        val id = UUID.randomUUID().toString()
        val manager = PlanManager(UUID.randomUUID().toString(), dir, main, profile.name)
        ReviewService.getInstance().create(id, BranchReview(branch, base, dir, pr, target.plan, manager, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()))
        service.updateFolders(ChangeSource.UI) { structure ->
            check(reviewParent(structure, reviews, place, canvases) == target) { "the place of the review of $branch changed meanwhile" }
            structure.createFolder(target.parent, id, reviewFolderName(structure, target.parent, branch)).assign(manager.session, id)
        }
        ReviewOpener.open(project, id)
    }
}

/** What New Review starts from: the work tree [dir] of a row, its [branch] (null: picked from the local branches) and the [place] it shows in. */
data class ReviewStart(val dir: Path, val branch: String?, val place: String?)

/** New Review... on the tree's menu, shown where [start] gives the selected row's start, enabled while [enabled] says so. */
class NewReviewAction(private val start: () -> ReviewStart?, private val enabled: () -> Boolean) :
    DumbAwareAction("New Review...", "Review a branch in a review folder", AllIcons.Actions.Preview) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val start = start()
        e.presentation.isVisible = start != null
        e.presentation.isEnabled = start != null && start.dir.isDirectory() && enabled()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        val start = checkNotNull(start()) { "the row the menu was opened on is gone" }
        fun picked(branch: String) {
            val pr = Messages.showInputDialog(project, "Pull request number of $branch, empty for none:", "New Review", null, "", PrValidator) ?: return
            val profile = chooseProfile(project, "New Review", "Profile the review's manager runs in:") ?: return
            startReview(project, branch, start.dir, start.place, prNumber(pr), profile)
        }
        if (start.branch != null) return picked(start.branch)
        val where = popupPoint(e)
        background(project, "Reading branches", {
            val local = parseBranches(Git.run(Git.mainRoot(start.dir), "for-each-ref", "--sort=-committerdate", "--format=%(refname)", "refs/heads"))
            check(local.isNotEmpty()) { "${start.dir} has no local branches." }
            local to Git.headBranch(start.dir)
        }) { (local, current) ->
            basePopup("Branch to Review", BaseChoices(local, local.size, local.size, local.indexOf(current).coerceAtLeast(0)), ::picked).show(where)
        }
    }
}

/**
 * The profile a new review's or canvas's manager runs in, asked in a dialog titled [title] with [message] only when
 * profiles besides `default` are listed, `default` preselected; null when cancelled.
 */
fun chooseProfile(project: Project, title: String, message: String): Profile? {
    val all = ProfileStore.getInstance().all()
    if (all.size == 1) return all.single()
    val labels = all.map { profileLabel(it.name) }.toTypedArray()
    return all.getOrNull(Messages.showChooseDialog(project, message, title, null, labels, profileLabel(DEFAULT_PROFILE)))
}

private object PrValidator : InputValidator {
    override fun checkInput(inputString: String) = runCatching { prNumber(inputString) }.isSuccess
    override fun canClose(inputString: String) = checkInput(inputString)
}
