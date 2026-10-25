package dev.myair.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import dev.myair.ClaudeSessionsService
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import java.util.UUID
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** The folder actions of the tree's menu and toolbar, acting on its selection. */
class FolderEditing(private val project: Project, private val tree: JTree, private val service: ClaudeSessionsService) {
    fun selectedFolder() = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Folder

    /** Inside the folder of the selection; at the top level when nothing or something under `sessions` is selected. */
    fun newFolder() {
        val parentId = tree.selectionPath?.let(::targetFolder)
        val name = askName("New Folder", "", parentId, null) ?: return
        service.updateFolders(ChangeSource.UI) { it.createFolder(parentId, UUID.randomUUID().toString(), name) }
    }

    fun renameFolder() {
        val folder = selectedFolder()!!
        val parentId = ((tree.selectionPath!!.parentPath.lastPathComponent as DefaultMutableTreeNode).userObject as? Folder)?.id
        val name = askName("Rename Folder", folder.name, parentId, folder.id) ?: return
        service.updateFolders(ChangeSource.UI) { it.rename(folder.id, name) }
    }

    fun deleteFolder() {
        val folder = selectedFolder()!!
        val count = service.folders.sessionCount(folder.id)
        val projects = folder.subtree().mapNotNull { it.project }.toList()
        val message = "Delete \"${folder.name}\" and its subfolders?" +
            (if (count > 0) "\n$count session(s) filed there go back to \"sessions\"." else "") +
            (if (projects.isNotEmpty()) "\nThe other sessions of ${projects.joinToString()} go back to \"sessions\"." else "")
        if (Messages.showYesNoDialog(project, message, "Delete Folder", Messages.getQuestionIcon()) != Messages.YES) return
        service.updateFolders(ChangeSource.UI) { it.delete(folder.id) }
    }

    /** A trimmed name no other folder under [parentId] has, or null when cancelled. */
    private fun askName(title: String, initial: String, parentId: String?, except: String?): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(input: String) = when {
                input.isBlank() -> "Name cannot be empty"
                '/' in input -> "Name cannot contain /, which separates folders in a path"
                service.folders.nameTaken(parentId, input, except) -> "A folder named \"${input.trim()}\" already exists here"
                else -> null
            }
        }
        return Messages.showInputDialog(project, "Name:", title, null, initial, validator)?.trim()
    }
}

/** A folder action, shown in Folders mode only. */
class FolderAction(text: String, icon: Icon?, private val visible: () -> Boolean, private val enabled: () -> Boolean, private val run: () -> Unit) :
    DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = visible()
        e.presentation.isEnabled = enabled()
    }
    override fun actionPerformed(e: AnActionEvent) = run()
}
