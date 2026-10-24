package dev.myair

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
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
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
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
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

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
    private val tree = DropLineTree().apply {
        isRootVisible = false
        // Double-click opens a session, so it must not also expand its changed files.
        toggleClickCount = 0
        cellRenderer = SessionRenderer { service.live[it.id] }
        dropMode = DropMode.ON_OR_INSERT
    }
    private val pinned = JBList<ClaudeSession>().apply {
        cellRenderer = PinnedRenderer { service.live[it.id] }
        emptyText.text = "No running or pinned sessions"
    }
    private val filter = SearchTextField(false).apply {
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = render()
        })
    }

    init {
        tree.transferHandler = FolderDrop()
        setContent(OnePixelSplitter(true, "dev.myair.pinnedSplitter", 0.25f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(pinned)
            secondComponent = JPanel(BorderLayout()).apply {
                add(filter, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
            }
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
        val pinInTree = PinAction { tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession } }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(pinInTree, Separator.getInstance(), newFolder, rename, delete), "ClaudeSessionsPopup")
        // A plain list keeps its selection on a right click, so the row under the pointer is selected first.
        val remove = object : DumbAwareAction("Remove from List", "Unpin; a running session comes back when it waits for you again", AllIcons.General.Remove) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !pinned.isSelectionEmpty
            }
            override fun actionPerformed(e: AnActionEvent) {
                val removed = pinned.selectedValuesList
                service.updatePins { it.remove(removed, service.live) }
            }
        }
        remove.registerCustomShortcutSet(CommonShortcuts.getDelete(), pinned)
        val pinnedPopup = DefaultActionGroup(PinAction { pinned.selectedValuesList }, remove)
        pinned.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val row = pinned.locationToIndex(Point(x, y))
                if (row >= 0 && !pinned.isSelectedIndex(row)) pinned.selectedIndex = row
                ActionManager.getInstance().createActionPopupMenu("ClaudePinnedPopup", pinnedPopup).component.show(comp, x, y)
            }
        })
        val newSession = ActionManager.getInstance().getAction("dev.myair.NewClaudeSession")
        toolbar = ActionManager.getInstance().createActionToolbar("ClaudeSessions", DefaultActionGroup(newSession, newFolder, mode, refresh), true)
            .apply { targetComponent = tree }.component
        onOpen(tree, ::openSelected)
        onOpen(pinned) { pinned.selectedValue?.let { ClaudeSessionOpener.open(project, it) } != null }
        // A rebuild clears the selection before restoring it, so only a real selection moves the target.
        tree.addTreeSelectionListener {
            if (foldersMode) tree.selectionPath?.let { project.service<FolderTarget>().folderId = targetFolder(it) }
        }
        // Statuses alone only repaint, which keeps expansion and selection; anything else rebuilds the model.
        service.subscribe(toolWindow.disposable) {
            renderPinned()
            if (snapshot() != shown) render() else tree.repaint()
        }
        renderPinned()
        render()
    }

    /** Double-click and Enter on [component] run [open]. */
    private fun onOpen(component: JComponent, open: () -> Boolean) {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent) = open()
        }.installOn(component)
        component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) open()
            }
        })
    }

    /** Rebuilt on every change, since a status change reorders it; the selection follows the session ids. */
    private fun renderPinned() {
        val selected = pinned.selectedValuesList.map { it.id }.toSet()
        val items = pinnedSessions(service.sessions, service.live, service.pins)
        pinned.setListData(items.toTypedArray())
        pinned.selectedIndices = items.indices.filter { items[it].id in selected }.toIntArray()
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
        // The drop shown refers to nodes of the old model; the next pointer move computes it on the new one.
        tree.drop = null
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

    /** Pins or unpins the [selected] sessions; a running session is listed anyway, a pin keeps it there after it ends. */
    private inner class PinAction(private val selected: () -> List<ClaudeSession>) :
        ToggleAction("Pin Session", "Keep the session in the list above the tree after its process ends", AllIcons.General.Pin_tab), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = selected().isNotEmpty()
        }
        override fun isSelected(e: AnActionEvent) = selected().let { it.isNotEmpty() && it.all { session -> session.id in service.pins.sessions } }
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val ids = selected().map { it.id }
            service.updatePins { if (state) it.pin(ids) else it.unpin(ids) }
        }
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
            tree.drop = null
        }

        override fun canImport(support: TransferSupport): Boolean {
            val drop = if (support.isDrop) drop(support) else null
            support.setShowDropLocation(false)
            tree.drop = drop
            return drop != null
        }

        override fun importData(support: TransferSupport): Boolean {
            val drop = drop(support) ?: return false
            service.updateFolders(drop.change)
            return true
        }

        private fun drop(support: TransferSupport): Drop? {
            if (!canEditFolders() || dragged.isEmpty()) return null
            val location = support.dropLocation as JTree.DropLocation
            return dropChange(service.folders, dragged, location.path ?: return null, location.childIndex)
        }
    }

    companion object {
        private const val FOLDERS_MODE = "dev.myair.foldersMode"
    }
}

/**
 * Draws a drop itself: a line between rows, a frame around the row dropped onto. IntelliJ's tree UI would instead fill the
 * row of the drop's parent, which flickers as the pointer crosses rows, so the drop handler keeps it from showing.
 */
private class DropLineTree : SimpleTree() {
    var drop: Drop? = null
        set(value) {
            field = value
            repaint()
        }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val drop = drop ?: return
        g.color = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR
        if (drop.childIndex >= 0) insertLine(this, drop.path, drop.childIndex).let { g.fillRect(it.x, it.y - 1, it.width, 2) }
        else getPathBounds(drop.path)?.let { g.drawRect(it.x, it.y, width - it.x - 1, it.height - 1) }
    }
}

/**
 * The line for a drop between the children of [parent] at [childIndex]: above that child's row, or below the last row of
 * the parent's subtree when dropping after its last child, indented like the children.
 */
fun insertLine(tree: JTree, parent: TreePath, childIndex: Int): Rectangle {
    val node = parent.lastPathComponent as TreeNode
    fun line(x: Int, y: Int) = Rectangle(x, y, tree.width - x, 0)
    if (childIndex < node.childCount) {
        val row = checkNotNull(tree.getPathBounds(parent.pathByAddingChild(node.getChildAt(childIndex)))) { "drop into collapsed $parent" }
        return line(row.x, row.y)
    }
    if (node.childCount == 0) {
        val row = checkNotNull(tree.getPathBounds(parent)) { "drop into hidden $parent" }
        return line(row.x + row.height, row.y + row.height)
    }
    val last = parent.pathByAddingChild(node.getChildAt(node.childCount - 1))
    var bottom = tree.getRowForPath(last)
    while (bottom + 1 < tree.rowCount && last.isDescendant(tree.getPathForRow(bottom + 1))) bottom++
    return line(checkNotNull(tree.getPathBounds(last)) { "drop into collapsed $parent" }.x, tree.getRowBounds(bottom).let { it.y + it.height })
}

/** A session's row in the tree and in the pinned list: title, status icon and label, optionally its project, last change. */
private fun SimpleColoredComponent.appendSession(item: ClaudeSession, live: LiveSession?, showProject: Boolean) {
    // A session without a live process is greyed out, so the running ones stand out in a long list.
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
    if (showProject) append("  ${item.cwd.fileName ?: item.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    append("  " + DateFormatUtil.formatPrettyDateTime(item.lastModified.toMillis()), SimpleTextAttributes.GRAYED_ATTRIBUTES)
}

/** Sessions from every project, so each row names its project; a row without a process is there because it is pinned. */
private class PinnedRenderer(private val liveOf: (ClaudeSession) -> LiveSession?) : ColoredListCellRenderer<ClaudeSession>() {
    override fun customizeCellRenderer(list: JList<out ClaudeSession>, value: ClaudeSession, index: Int, selected: Boolean, hasFocus: Boolean) {
        val live = liveOf(value)
        appendSession(value, live, showProject = true)
        if (live == null) icon = AllIcons.General.Pin_tab
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
            // In a user folder the project is no longer the parent node, so the row names it.
            is ClaudeSession -> appendSession(item, liveOf(item), showProject = (node.parent as DefaultMutableTreeNode).userObject is Folder)
            is ChangedFile -> {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(item.path.fileName.toString()).icon
                // Files inside the session's directory read relative to it; anything else keeps its full path.
                append(if (item.path.startsWith(item.cwd)) item.cwd.relativize(item.path).toString() else item.path.toString())
            }
        }
    }
}
