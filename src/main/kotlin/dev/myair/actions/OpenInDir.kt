package dev.myair.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path
import javax.swing.Icon
import kotlin.io.path.isDirectory

/** Brings the window of the open project at [dir] forward in this IDE, or opens it there. */
fun openInIdea(project: Project, dir: Path) {
    val open = ProjectManager.getInstance().openProjects.firstOrNull { ProjectUtil.isSameProject(dir, it) }
    if (open != null) ProjectUtil.focusProjectWindow(open, true)
    else ApplicationManager.getApplication().executeOnPooledThread { ProjectUtil.openOrImport(dir, project, false) }
}

/** A new tab of [project]'s Terminal tool window in [dir]. */
fun openInTerminal(project: Project, dir: Path) {
    TerminalToolWindowManager.getInstance(project).createShellWidget(dir.toString(), dir.fileName?.toString() ?: dir.toString(), true, true)
}

/** On a project's, folder's or session's menu: [open] the node's directory, shown only where it has one. */
class OpenInDirAction(text: String, icon: Icon, private val dir: () -> Path?, private val open: (Project, Path) -> Unit) : DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val dir = dir()
        e.presentation.isVisible = dir != null
        // A removed worktree's row stays until its sessions go.
        e.presentation.isEnabled = dir != null && dir.isDirectory()
    }
    override fun actionPerformed(e: AnActionEvent) = open(e.project!!, dir()!!)
}

fun openInIdeaAction(dir: () -> Path?) = OpenInDirAction("Open in IDEA", AllIcons.Nodes.Folder, dir, ::openInIdea)
fun openInTerminalAction(dir: () -> Path?) = OpenInDirAction("Open in Terminal", AllIcons.Debugger.Console, dir, ::openInTerminal)
