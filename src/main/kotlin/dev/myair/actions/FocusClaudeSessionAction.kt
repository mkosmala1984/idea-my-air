package dev.myair.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.myair.store.IdeTabs
import dev.myair.store.OpenTab
import dev.myair.tabs.TabsSync

/** Lists the session tabs of every IDE running this plugin and brings the chosen one to the front. */
class FocusClaudeSessionAction : DumbAwareAction() {
    private class Choice(val ide: IdeTabs, val tab: OpenTab, val here: Boolean) {
        override fun toString() = "${tab.title}  —  ${tab.project}" + if (here) "" else "  (IDE pid ${ide.pid})"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val sync = TabsSync.getInstance()
        val choices = sync.allIdes().flatMap { ide -> ide.tabs.map { Choice(ide, it, sync.isSelf(ide)) } }
        val popup = if (choices.isEmpty()) JBPopupFactory.getInstance().createMessage("No Claude session tabs are open in any IDE")
        else JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("Focus Claude Session")
            .setNamerForFiltering { it.toString() }
            .setItemChosenCallback { sync.focusIn(it.ide, it.tab.sessionIds.first()) }
            .createPopup()
        val project = e.project
        if (project != null) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
    }
}
