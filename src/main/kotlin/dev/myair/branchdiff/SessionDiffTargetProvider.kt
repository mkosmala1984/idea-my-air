package dev.myair.branchdiff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.kosmala.branchdifftree.service.DiffTarget
import dev.kosmala.branchdifftree.service.DiffTargetProvider
import dev.myair.actions.Git
import dev.myair.service.ActiveSession
import dev.myair.service.ClaudeSessionsService
import kotlin.io.path.exists

/**
 * Branch Diff's target while it follows the session: the work tree the active session works in now (`workDir`, so
 * `EnterWorktree` counts) against its base ([Git.baseOf], the base Session Context shows), named by the session's title.
 */
class SessionDiffTargetProvider : DiffTargetProvider {
    override fun target(project: Project): DiffTarget? {
        val tab = ActiveSession.getInstance(project).tab ?: return null
        val session = ClaudeSessionsService.getInstance().sessions.firstOrNull { it.id == tab.sessionId }
        // A new tab no scan has seen yet works where it started.
        val workDir = session?.workDir ?: tab.cwd
        check(workDir.exists()) { "The folder of ${tab.title}, $workDir, no longer exists" }
        val root = checkNotNull(Git.root(workDir)) { "${tab.title} works in $workDir, which is in no Git repository" }
        return DiffTarget(root, Git.baseOf(root), session?.title ?: tab.title)
    }

    override fun subscribe(project: Project, parent: Disposable, onChange: () -> Unit) =
        ActiveSession.getInstance(project).subscribe(parent, onChange)

    override fun setBase(project: Project, target: DiffTarget, base: String) {
        val branch = checkNotNull(Git.headBranch(target.root)) { "${target.root} has a detached HEAD, which stores no base" }
        Git.setBase(target.root, branch, base)
        ApplicationManager.getApplication().invokeLater({ ActiveSession.getInstance(project).baseChanged() }, project.disposed)
    }
}
