package dev.myair.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.editor.ClaudeSessionOpener
import java.nio.file.Path
import kotlin.io.path.isDirectory

class NewClaudeSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = ClaudeSessionOpener.startNew(e.project!!)
}

/** On a project's or folder's menu: a session in [dir], the selected node's directory, shown only where it has one. */
class NewSessionInDirAction(text: String, inWorktree: Boolean, private val dir: () -> Path?) :
    DumbAwareAction(text, null, if (inWorktree) AllIcons.Vcs.Branch else AllIcons.General.Add) {
    private val start: (AnActionEvent, Path) -> Unit =
        if (inWorktree) { e, dir -> startInWorktree(e.project!!, dir, null, emptyList(), popupPoint(e)) }
        else { e, dir -> ClaudeSessionOpener.startNew(e.project!!, dir) }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val dir = dir()
        e.presentation.isVisible = dir != null
        // A removed worktree's row stays until its sessions go.
        e.presentation.isEnabled = dir != null && dir.isDirectory()
    }
    override fun actionPerformed(e: AnActionEvent) = start(e, dir()!!)
}
