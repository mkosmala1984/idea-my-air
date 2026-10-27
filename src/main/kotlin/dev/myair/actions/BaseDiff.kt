package dev.myair.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import dev.myair.actions.FileChange.Kind
import dev.myair.claude.ClaudeSession
import kotlin.io.path.exists

/** The diff window over every file of [state]'s changes, opened on the [selected] one; each file is read when shown. */
fun showBaseDiff(project: Project, state: GitState, selected: Int) {
    val changes = checkNotNull(state.changes) { "base ${state.base} names no commit" }
    val producers = changes.map { change ->
        object : DiffRequestProducer {
            override fun getName() = change.path
            override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
                val factory = DiffContentFactory.getInstance()
                val type = FileTypeManager.getInstance().getFileTypeByFileName(change.path.substringAfterLast('/'))
                val before = when (change.kind) {
                    Kind.ADDED, Kind.UNTRACKED -> factory.createEmpty()
                    Kind.MODIFIED, Kind.DELETED -> factory.createFromBytes(project, Git.show(state.root, checkNotNull(state.mergeBase), change.path), type, change.path)
                }
                val after = when (change.kind) {
                    Kind.DELETED -> factory.createEmpty()
                    else -> factory.create(project, checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(state.root.resolve(change.path))) {
                        "${change.path} is no longer in ${state.root}"
                    })
                }
                return SimpleDiffRequest(change.path, before, after, "${state.base} (merge base)", "Working tree")
            }
        }
    }
    DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain.fromProducers(producers, selected), DiffDialogHints.FRAME)
}

/** On a session's menu: the diff of the [selected] session's folder against its base. */
class ShowChangesVsBaseAction(private val selected: () -> List<ClaudeSession>) :
    DumbAwareAction("Show Changes vs Base", "Changes of the session's branch against the branch it started from", AllIcons.Actions.Diff) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val sessions = selected()
        e.presentation.isVisible = sessions.size == 1
        e.presentation.isEnabled = sessions.singleOrNull()?.cwd?.exists() == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        val cwd = selected().single().cwd
        background(project, "Reading changes", { Git.state(cwd) }) { state ->
            when {
                state == null -> Messages.showErrorDialog(project, "$cwd is not in a git repository.", "No Changes")
                state.changes == null -> Messages.showErrorDialog(project, "${state.problem}.", "No Changes")
                state.changes.isEmpty() -> Messages.showInfoMessage(project, "No changes" + state.base?.let { " against $it" }.orEmpty() + ".", "No Changes")
                else -> showBaseDiff(project, state, 0)
            }
        }
    }
}
