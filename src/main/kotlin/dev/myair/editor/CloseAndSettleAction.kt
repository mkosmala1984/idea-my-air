package dev.myair.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.service.ClaudeSessionsService

/** Marks the session of the tab settled, then closes the tab, which ends its process. */
class CloseAndSettleAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) is ClaudeSessionVirtualFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) as ClaudeSessionVirtualFile
        ClaudeSessionsService.getInstance().updatePins { it.settle(listOf(file.sessionId)) }
        FileEditorManager.getInstance(e.project!!).closeFile(file)
    }
}
