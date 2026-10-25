package dev.myair.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.editor.ClaudeSessionOpener

class NewClaudeSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = ClaudeSessionOpener.startNew(e.project!!)
}
