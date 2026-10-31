package dev.myair.actions

import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.tools.combined.CombinedBlockProducer
import com.intellij.diff.tools.combined.CombinedDiffVirtualFileImpl
import com.intellij.diff.tools.combined.CombinedPathBlockId
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.history.VcsDiffUtil
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.vcsUtil.VcsUtil
import dev.myair.actions.FileChange.Kind
import dev.myair.claude.ClaudeSession
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import kotlin.io.path.exists

/** The file status the IDE colours [this] kind of change with. */
val Kind.status: FileStatus
    get() = when (this) {
        Kind.ADDED -> FileStatus.ADDED
        Kind.MODIFIED -> FileStatus.MODIFIED
        Kind.DELETED -> FileStatus.DELETED
        Kind.UNTRACKED -> FileStatus.UNKNOWN
    }

/**
 * [state]'s changes as the IDE's change views take them: the merge base read with `git show`, the work tree from disk,
 * each when a view shows it.
 */
private fun changesOf(state: GitState): List<Change> {
    val changes = checkNotNull(state.changes) { "base ${state.base} names no commit" }
    val mergeBase = object : VcsRevisionNumber {
        override fun asString() = "${state.base} (merge base)"
        override fun compareTo(other: VcsRevisionNumber?) = 0
    }
    return changes.map { change ->
        val path = VcsUtil.getFilePath(state.root.resolve(change.path), false)
        val before = when (change.kind) {
            Kind.ADDED, Kind.UNTRACKED -> null
            Kind.MODIFIED, Kind.DELETED -> object : ByteBackedContentRevision {
                override fun getContentAsBytes() = Git.show(state.root, checkNotNull(state.mergeBase), change.path)
                override fun getContent() = contentAsBytes.toString(path.charset)
                override fun getFile() = path
                override fun getRevisionNumber() = mergeBase
            }
        }
        Change(before, if (change.kind == Kind.DELETED) null else CurrentContentRevision.create(path), change.kind.status)
    }
}

/** [work] on [state]'s changes off the EDT, in a read action: each change looks its file up in the VFS; then [show] on the EDT. */
private fun <T> withChanges(project: Project, state: GitState, work: (List<Change>) -> T, show: (T) -> Unit) =
    background(project, "Reading changes", { runReadAction { work(changesOf(state)) } }, show)

/** The diff of every file of [state]'s changes, opened on the [selected] one. */
fun showBaseDiff(project: Project, state: GitState, selected: Int) =
    withChanges(project, state, { it }) { ShowDiffAction.showDiffForChange(project, it, selected) }

/** Every file of [state]'s changes in one editor tab, one under another: the IDE's combined diff. */
fun showCombinedBaseDiff(project: Project, state: GitState) {
    // As the platform's own ShowCombinedDiffAction.showDiff, which is internal.
    val blocks = { changes: List<Change> ->
        changes.map { CombinedBlockProducer(CombinedPathBlockId(ChangesUtil.getFilePath(it), it.fileStatus), checkNotNull(ChangeDiffRequestProducer.create(project, it)) { "no diff for $it" }) }
    }
    withChanges(project, state, blocks) {
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(CombinedDiffVirtualFileImpl(project, it, "Changes vs ${state.base}"), true)
    }
}

/** [state]'s changes as a tree of folders in a dialog, as Show Changes lists a commit's; a file opens its diff. */
fun showBaseChangesTree(project: Project, state: GitState) =
    withChanges(project, state, { it }) { VcsDiffUtil.showChangesDialog(project, "Changes vs ${state.base}", it) }

/** The `plannotator` on PATH, looked up once: one installed later shows after a restart. */
val plannotator: File? by lazy { PathEnvironmentVariableUtil.findInPath("plannotator.exe") }

/**
 * Plannotator's review of [state]'s work tree against its base in the browser: all changes since the base, committed or
 * not, as the diff views show them. It runs in a Terminal tab at the work tree, which shows its output and stops it.
 */
fun reviewInPlannotator(project: Project, state: GitState) {
    checkNotNull(plannotator) { "plannotator is not on PATH" }
    TerminalToolWindowManager.getInstance(project).createShellWidget(state.root.toString(), "Plannotator", true, true)
        .sendCommandToExecute("plannotator review --git --base ${checkNotNull(state.base)} --diff-type since-base")
}

/** On a session's menu: all changes of the [selected] session's folder against its base, in one tab. */
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
                else -> showCombinedBaseDiff(project, state)
            }
        }
    }
}
