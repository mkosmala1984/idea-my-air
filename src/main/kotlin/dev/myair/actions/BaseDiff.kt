package dev.myair.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import dev.myair.actions.FileChange.Kind

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
