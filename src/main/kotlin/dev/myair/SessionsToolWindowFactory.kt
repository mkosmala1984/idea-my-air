package dev.myair

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.UUID
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class SessionsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionsPanel(project, toolWindow)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, null, false))
    }
}

private class SessionsPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val service = ClaudeSessionsService.getInstance()
    private var foldersMode = PropertiesComponent.getInstance().getBoolean(FOLDERS_MODE)
    private var shown: List<Any?> = emptyList()
    private val tree = SimpleTree().apply {
        isRootVisible = false
        // Double-click opens a session, so it must not also expand its changed files.
        toggleClickCount = 0
        cellRenderer = SessionRenderer { service.live[it.id] }
        dropMode = DropMode.ON_OR_INSERT
    }
    private val filter = SearchTextField(false).apply {
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = render()
        })
    }

    init {
        tree.transferHandler = FolderDrop()
        setContent(JPanel(BorderLayout()).apply {
            add(filter, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        })
        val refresh = object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = service.refresh()
        }
        val mode = object : ToggleAction("Folders", "Arrange sessions in your own folders", AllIcons.Nodes.Folder), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = foldersMode
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                foldersMode = state
                PropertiesComponent.getInstance().setValue(FOLDERS_MODE, state)
                render()
            }
        }
        val newFolder = FolderAction("New Folder", AllIcons.Actions.NewFolder, ::canEditFolders, ::newFolder)
        val rename = FolderAction("Rename Folder...", null, { canEditFolders() && selectedFolder() != null }, ::renameFolder)
        val delete = FolderAction("Delete Folder...", AllIcons.Actions.GC, { canEditFolders() && selectedFolder() != null }, ::deleteFolder)
        delete.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree)
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(newFolder, rename, delete), "ClaudeSessionsPopup")
        val newSession = ActionManager.getInstance().getAction("dev.myair.NewClaudeSession")
        toolbar = ActionManager.getInstance().createActionToolbar("ClaudeSessions", DefaultActionGroup(newSession, newFolder, mode, refresh), true)
            .apply { targetComponent = tree }.component
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent) = openSelected()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
        // A rebuild clears the selection before restoring it, so only a real selection moves the target.
        tree.addTreeSelectionListener {
            if (foldersMode) tree.selectionPath?.let { project.service<FolderTarget>().folderId = targetFolder(it) }
        }
        // Statuses alone only repaint, which keeps expansion and selection; anything else rebuilds the model.
        service.subscribe(toolWindow.disposable) {
            if (snapshot() != shown) render() else tree.repaint()
        }
        render()
    }

    private fun snapshot() = listOf(foldersMode, service.sessions, service.folders, service.foldersError)

    private fun canEditFolders() = foldersMode && service.foldersError == null

    private fun selectedFolder() = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Folder

    private fun openSelected(): Boolean {
        val path = tree.selectionPath ?: return false
        when (val item = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
            is ClaudeSession -> ClaudeSessionOpener.open(project, item)
            is ChangedFile -> {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(item.path)
                if (file == null) Messages.showErrorDialog(project, "File no longer exists:\n${item.path}", "Cannot Open File")
                else FileEditorManager.getInstance(project).openFile(file, true)
            }
            else -> if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        }
        return true
    }

    /** Inside the folder of the selection; at the top level when nothing or something under `sessions` is selected. */
    private fun newFolder() {
        val parentId = tree.selectionPath?.let(::targetFolder)
        val name = askName("New Folder", "", parentId, null) ?: return
        service.updateFolders { it.createFolder(parentId, UUID.randomUUID().toString(), name) }
    }

    private fun renameFolder() {
        val folder = selectedFolder()!!
        val parentId = ((tree.selectionPath!!.parentPath.lastPathComponent as DefaultMutableTreeNode).userObject as? Folder)?.id
        val name = askName("Rename Folder", folder.name, parentId, folder.id) ?: return
        service.updateFolders { it.rename(folder.id, name) }
    }

    private fun deleteFolder() {
        val folder = selectedFolder()!!
        val count = service.folders.sessionCount(folder.id)
        val message = "Delete \"${folder.name}\" and its subfolders?" + if (count > 0) "\n$count session(s) filed there go back to \"sessions\"." else ""
        if (Messages.showYesNoDialog(project, message, "Delete Folder", Messages.getQuestionIcon()) != Messages.YES) return
        service.updateFolders { it.delete(folder.id) }
    }

    /** A trimmed name no other folder under [parentId] has, or null when cancelled. */
    private fun askName(title: String, initial: String, parentId: String?, except: String?): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(input: String) = when {
                input.isBlank() -> "Name cannot be empty"
                service.folders.nameTaken(parentId, input, except) -> "A folder named \"${input.trim()}\" already exists here"
                else -> null
            }
        }
        return Messages.showInputDialog(project, "Name:", title, null, initial, validator)?.trim()
    }

    /**
     * Keeps expanded nodes and the selection across rebuilds; a non-empty filter expands everything, so all matches are
     * visible at once. Folders mode with an unreadable `structure.json` shows the error instead of the tree.
     */
    private fun render() {
        shown = snapshot()
        val query = filter.text.trim()
        val state = TreeState.createOn(tree)
        val error = service.foldersError.takeIf { foldersMode }
        tree.emptyText.text = error?.let { "Cannot read ${service.structureFile}: $it" } ?: StatusText.getDefaultEmptyText()
        tree.dragEnabled = foldersMode
        tree.model = DefaultTreeModel(
            when {
                error != null -> DefaultMutableTreeNode()
                foldersMode -> foldersTree(service.folders, service.sessions, query)
                else -> projectsTree(service.sessions.filter { it.matches(query) })
            },
        )
        if (query.isNotEmpty()) TreeUtil.expandAll(tree) else state.applyTo(tree)
    }

    private inner class FolderAction(text: String, icon: Icon?, private val enabled: () -> Boolean, private val run: () -> Unit) :
        DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = foldersMode
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    /** Drags sessions (one or many) or a single folder within the tree; nothing crosses to other components. */
    private inner class FolderDrop : TransferHandler() {
        private var dragged = emptyList<Any>()

        override fun getSourceActions(c: JComponent) = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val items = tree.selectionPaths.orEmpty().map { (it.lastPathComponent as DefaultMutableTreeNode).userObject }
            dragged = when {
                items.isNotEmpty() && items.all { it is ClaudeSession } -> items
                items.size == 1 && items[0] is Folder -> items
                else -> return null
            }
            return StringSelection(dragged.joinToString("\n"))
        }

        override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
            dragged = emptyList()
        }

        override fun canImport(support: TransferSupport) = support.isDrop && drop(support) != null

        override fun importData(support: TransferSupport): Boolean {
            val change = drop(support) ?: return false
            service.updateFolders(change)
            return true
        }

        private fun drop(support: TransferSupport): ((SessionFolders) -> SessionFolders)? {
            if (!canEditFolders() || dragged.isEmpty()) return null
            val location = support.dropLocation as JTree.DropLocation
            return dropChange(service.folders, dragged, location.path ?: return null, location.childIndex)
        }
    }

    companion object {
        private const val FOLDERS_MODE = "dev.myair.foldersMode"
    }
}

private class SessionRenderer(private val liveOf: (ClaudeSession) -> LiveSession?) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val node = value as DefaultMutableTreeNode
        when (val item = node.userObject) {
            is Folder -> {
                icon = AllIcons.Nodes.Folder
                append(item.name)
            }
            UnfiledSessions -> {
                icon = AllIcons.Actions.ListFiles
                append(item.toString(), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
            is Path -> {
                icon = AllIcons.Nodes.Folder
                append(item.fileName?.toString() ?: item.toString())
                append("  $item", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ClaudeSession -> {
                // A session without a live process is greyed out, so the running ones stand out in a long list.
                val live = liveOf(item)
                append(item.title, if (live == null) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                when (live?.status) {
                    SessionStatus.WORKING -> {
                        icon = AllIcons.Actions.Execute
                        append("  working", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                    }
                    SessionStatus.NEEDS_INPUT -> {
                        icon = AllIcons.General.Warning
                        append("  needs input" + (live.waitingFor?.let { ": $it" } ?: ""), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                    SessionStatus.IDLE -> {
                        icon = AllIcons.Actions.Checked
                        append("  waiting for prompt", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                    }
                    null -> {}
                }
                // In a user folder the project is no longer the parent node, so the row names it.
                if ((node.parent as DefaultMutableTreeNode).userObject is Folder) append("  ${item.cwd.fileName ?: item.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  " + DateFormatUtil.formatPrettyDateTime(item.lastModified.toMillis()), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ChangedFile -> {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(item.path.fileName.toString()).icon
                // Files inside the session's directory read relative to it; anything else keeps its full path.
                append(if (item.path.startsWith(item.cwd)) item.cwd.relativize(item.path).toString() else item.path.toString())
            }
        }
    }
}
