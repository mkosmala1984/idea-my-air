package dev.myair.editor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.service.ClaudeSessionsService

/**
 * Marks the session of the tab settled, ends its process, which a closed tab alone may leave running, then closes the tab.
 * A background job the tab shows outlives its view, so it is stopped too (`claude stop`, which keeps its conversation).
 */
class CloseAndSettleAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) is ClaudeSessionVirtualFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) as ClaudeSessionVirtualFile
        val terminals = SessionTerminals.getInstance()
        val job = terminals.jobOf(file)
        ClaudeSessionsService.getInstance().updatePins { it.settle(listOf(file.sessionId)) }
        terminals.close(file)
        FileEditorManager.getInstance(e.project!!).closeFile(file)
        if (job != null) ApplicationManager.getApplication().executeOnPooledThread { stop(job) }
    }

    private fun stop(job: String) {
        val output = CapturingProcessHandler(GeneralCommandLine(claudeExecutable().path, "stop", job)).runProcess(STOP_TIMEOUT_MS, true)
        when {
            output.isTimeout -> thisLogger().error("claude stop $job did not end within $STOP_TIMEOUT_MS ms: ${output.stderr}")
            output.exitCode != 0 -> thisLogger().error("claude stop $job exited with ${output.exitCode}: ${output.stderr}")
            else -> thisLogger().info("claude stop $job: background job stopped")
        }
    }

    private companion object {
        /** As Air's Mark Done waits for `claude stop`. */
        const val STOP_TIMEOUT_MS = 5_000
    }
}
