package dev.myair.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import javax.swing.JComponent

class SessionsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) =
        toolWindow.setContent(SessionsPanel(project, toolWindow))
}

class SessionContextToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) =
        toolWindow.setContent(ScrollPaneFactory.createScrollPane(SessionContextPanel(project, toolWindow.disposable), true))
}

private fun ToolWindow.setContent(component: JComponent) =
    contentManager.addContent(contentManager.factory.createContent(component, null, false))
