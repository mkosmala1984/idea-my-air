package dev.myair.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class SessionsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionsPanel(project, toolWindow)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, null, false))
    }
}
