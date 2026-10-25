package dev.myair.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.myair.claude.SessionStatus
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.attentionQueue
import dev.myair.service.nextInQueue
import dev.myair.tabs.SessionHosts

/** Goes to the next session waiting for the user ([attentionQueue]); pressed again, to the one after it. */
class NextSessionNeedingMeAction : DumbAwareAction() {
    /** Where this action went last, so a walk through sessions without a tab here goes on instead of restarting. */
    private var last: String? = null

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ClaudeSessionsService.getInstance()
        val queue = attentionQueue(service.sessions, service.live, service.unread)
        val ids = queue.map { it.id }
        val shown = SessionHosts.visibleSessions().firstOrNull { it in ids }
        val next = nextInQueue(ids, shown, last)
        if (next == null || next == shown) {
            val working = service.live.values.count { it.status == SessionStatus.WORKING }
            val text = (if (next == null) "Nothing needs you" else "Nothing else needs you") + " · $working working"
            return JBPopupFactory.getInstance().createMessage(text).showCenteredInCurrentWindow(project)
        }
        last = next
        ClaudeSessionOpener.open(project, queue.first { it.id == next }, switchToWindow = true)
    }
}
