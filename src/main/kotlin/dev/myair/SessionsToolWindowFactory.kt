package dev.myair

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.CardLayout
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
import javax.swing.Timer
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
    private var showSettled = PropertiesComponent.getInstance().getBoolean(SHOW_SETTLED)
    private var shown: List<Any?> = emptyList()
    private val tree = DropLineTree().apply {
        isRootVisible = false
        // Double-click opens a session, so it must not also expand its changed files.
        toggleClickCount = 0
        cellRenderer = SessionRenderer({ service.live[it.id] }, ::isSettled)
        dropMode = DropMode.ON_OR_INSERT
    }
    private val pinned = TrailerList().apply {
        cellRenderer = PinnedRenderer({ service.live[it.id] }, ::isSettled)
    }
    private val trailerButtons = listOf(SETTLE, PIN).map { mark ->
        TrailerButton(mark.icon, { it.id in mark.marked(service.pins) }) { session ->
            service.updatePins { mark.change(it, listOf(session.id), session.id !in mark.marked(it)) }
        }
    }
    private val filter = SearchTextField(false).apply {
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = render()
        })
    }
    /** The toolbar, or the search field in its place. */
    private val top = JPanel(CardLayout())

    init {
        tree.transferHandler = FolderDrop()
        tree.trailer = SessionTrailer(
            tree, { tree.getClosestRowForLocation(it.x, it.y) }, tree::getRowBounds,
            { (tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession },
            { DefaultTreeUI.getBackground(tree, tree.getPathForRow(it), it, tree.isRowSelected(it)) ?: tree.background }, trailerButtons,
        )
        pinned.trailer = SessionTrailer(
            pinned, pinned::locationToIndex, { pinned.getCellBounds(it, it) }, { pinned.model.getElementAt(it) },
            // As WideSelectionListUI paints a row: the renderer leaves an unselected row's background to the list UI, and
            // a null hover background means hover painting is off.
            { row ->
                if (pinned.isSelectedIndex(row)) RenderingUtil.getSelectionBackground(pinned)
                else RenderingUtil.getHoverBackground(pinned).takeIf { ListHoverListener.getHoveredIndex(pinned) == row } ?: RenderingUtil.getBackground(pinned)
            },
            trailerButtons,
        )
        setContent(OnePixelSplitter(true, "dev.myair.pinnedSplitter", 0.25f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(pinned)
            secondComponent = ScrollPaneFactory.createScrollPane(tree)
        })
        applyFontSize()
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
        val showAll = object : ToggleAction("Show All Sessions", "Show settled sessions in the list and the tree too", AllIcons.Actions.Show), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = showSettled
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showSettled = state
                PropertiesComponent.getInstance().setValue(SHOW_SETTLED, state)
                renderPinned()
                render()
            }
        }
        val newFolder = FolderAction("New Folder", AllIcons.Actions.NewFolder, ::canEditFolders, ::newFolder)
        val rename = FolderAction("Rename Folder...", null, { canEditFolders() && selectedFolder() != null }, ::renameFolder)
        val delete = FolderAction("Delete Folder...", AllIcons.Actions.GC, { canEditFolders() && selectedFolder() != null }, ::deleteFolder)
        delete.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree)
        val inTree = { tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession } }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(pinAction(inTree), settleAction(inTree), Separator.getInstance(), newFolder, rename, delete), "ClaudeSessionsPopup")
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
        val pinnedPopup = DefaultActionGroup(pinAction { pinned.selectedValuesList }, settleAction { pinned.selectedValuesList }, remove)
        pinned.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val row = pinned.locationToIndex(Point(x, y))
                if (row >= 0 && !pinned.isSelectedIndex(row)) pinned.selectedIndex = row
                ActionManager.getInstance().createActionPopupMenu("ClaudePinnedPopup", pinnedPopup).component.show(comp, x, y)
            }
        })
        val newSession = ActionManager.getInstance().getAction("dev.myair.NewClaudeSession")
        val newSessionInWorktree = ActionManager.getInstance().getAction("dev.myair.NewClaudeSessionInWorktree")
        val options = DefaultActionGroup("View Options", true).apply {
            templatePresentation.icon = AllIcons.Actions.Properties
            addAll(
                mode, showAll, Separator.getInstance(),
                FontSize("Increase Font Size") { it + 1 }, FontSize("Decrease Font Size") { it - 1 }, FontSize("Reset Font Size") { 0 },
                Separator.getInstance(), refresh,
            )
        }
        val search = object : DumbAwareAction("Search", "Filter sessions by title or project path", AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) = showSearch(true)
        }
        search.registerCustomShortcutSet(CommonShortcuts.getFind(), this)
        fun bar(place: String, vararg actions: AnAction) =
            ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), true).apply { targetComponent = tree }.component
        val actions = JPanel(BorderLayout()).apply {
            add(bar("ClaudeSessions", newSession, newSessionInWorktree, newFolder), BorderLayout.CENTER)
            add(bar("ClaudeSessionsView", options, search), BorderLayout.EAST)
        }
        val close = InplaceButton(IconButton("Close Search", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { showSearch(false) }
        filter.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) showSearch(false)
            }
        })
        top.add(actions, TOOLBAR_CARD)
        top.add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
            add(filter, BorderLayout.CENTER)
            add(close, BorderLayout.EAST)
        }, SEARCH_CARD)
        toolbar = top
        onOpen(tree, tree.trailer, ::openSelected)
        onOpen(pinned, pinned.trailer) { pinned.selectedValue?.let { ClaudeSessionOpener.open(project, it) } != null }
        // Ages change with time alone, while the service notifies only on changes.
        val ages = Timer(60_000) {
            tree.repaint()
            pinned.repaint()
        }
        ages.start()
        Disposer.register(toolWindow.disposable) { ages.stop() }
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

    /** The search field takes the toolbar's place; closing it clears the filter. */
    private fun showSearch(on: Boolean) {
        (top.layout as CardLayout).show(top, if (on) SEARCH_CARD else TOOLBAR_CARD)
        if (on) filter.textEditor.requestFocusInWindow()
        else {
            filter.text = ""
            tree.requestFocusInWindow()
        }
    }

    /** Both session lists in the label font plus [FONT_SIZE] points; tree rows grow with the font's height. */
    private fun applyFontSize() {
        val base = JBFont.label()
        val font = base.deriveFont(base.size2D + JBUIScale.scale(PropertiesComponent.getInstance().getInt(FONT_SIZE, 0).toFloat()))
        tree.font = font
        pinned.font = font
        tree.rowHeight = JBUI.CurrentTheme.Tree.rowHeight() + tree.getFontMetrics(font).height - tree.getFontMetrics(base).height
    }

    /** Sets [FONT_SIZE] to [next] of it; disabled where that leaves it as it is or outside [FONT_SIZES]. */
    private inner class FontSize(text: String, private val next: (Int) -> Int) : DumbAwareAction(text) {
        init {
            // Several steps in a row, without reopening the menu.
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val size = PropertiesComponent.getInstance().getInt(FONT_SIZE, 0)
            e.presentation.isEnabled = next(size).let { it != size && it in FONT_SIZES }
        }
        override fun actionPerformed(e: AnActionEvent) {
            PropertiesComponent.getInstance().setValue(FONT_SIZE, next(PropertiesComponent.getInstance().getInt(FONT_SIZE, 0)), 0)
            applyFontSize()
        }
    }

    /** Double-click and Enter on [component] run [open]; a double-click on a [trailer] button does not. */
    private fun onOpen(component: JComponent, trailer: SessionTrailer, open: () -> Boolean) {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent) = !trailer.isButton(event.point) && open()
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
        pinned.emptyText.text = if (service.loaded) "No running or pinned sessions" else LOADING
        val items = pinnedSessions(shownSessions(), service.live, service.pins)
        pinned.setListData(items.toTypedArray())
        pinned.selectedIndices = items.indices.filter { items[it].id in selected }.toIntArray()
    }

    private fun snapshot() = listOf(service.loaded, foldersMode, showSettled, service.sessions, service.pins.settled, service.folders, service.foldersError)

    private fun shownSessions() = service.pins.shown(service.sessions, showSettled)

    private fun isSettled(session: ClaudeSession) = session.id in service.pins.settled

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
        service.updateFolders(ChangeSource.UI) { it.createFolder(parentId, UUID.randomUUID().toString(), name) }
    }

    private fun renameFolder() {
        val folder = selectedFolder()!!
        val parentId = ((tree.selectionPath!!.parentPath.lastPathComponent as DefaultMutableTreeNode).userObject as? Folder)?.id
        val name = askName("Rename Folder", folder.name, parentId, folder.id) ?: return
        service.updateFolders(ChangeSource.UI) { it.rename(folder.id, name) }
    }

    private fun deleteFolder() {
        val folder = selectedFolder()!!
        val count = service.folders.sessionCount(folder.id)
        val message = "Delete \"${folder.name}\" and its subfolders?" + if (count > 0) "\n$count session(s) filed there go back to \"sessions\"." else ""
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

    /**
     * Keeps expanded nodes and the selection across rebuilds; a non-empty filter expands everything, so all matches are
     * visible at once. Folders mode with an unreadable `structure.json` shows the error instead of the tree.
     */
    private fun render() {
        shown = snapshot()
        val query = filter.text.trim()
        val state = TreeState.createOn(tree)
        val error = service.foldersError.takeIf { foldersMode }
        tree.emptyText.text = when {
            !service.loaded -> LOADING
            error != null -> "Cannot read ${service.structureFile}: $error"
            else -> StatusText.getDefaultEmptyText()
        }
        tree.dragEnabled = foldersMode
        // The drop shown refers to nodes of the old model; the next pointer move computes it on the new one.
        tree.drop = null
        tree.model = DefaultTreeModel(
            when {
                error != null -> DefaultMutableTreeNode()
                foldersMode -> foldersTree(service.folders, shownSessions(), query)
                else -> projectsTree(shownSessions().filter { it.matches(query) })
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

    private fun pinAction(selected: () -> List<ClaudeSession>) = PinsToggle(PIN, selected)

    private fun settleAction(selected: () -> List<ClaudeSession>) =
        PinsToggle(SETTLE, selected).apply {
            // A toggle keeps its popup open by default; settling hides the row the menu was opened on.
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

    /** Checked when all [selected] sessions carry the [mark]; toggling sets or clears it on them. */
    private inner class PinsToggle(private val mark: Mark, private val selected: () -> List<ClaudeSession>) :
        ToggleAction(mark.text, mark.description, mark.icon), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = selected().isNotEmpty()
        }
        override fun isSelected(e: AnActionEvent) = selected().let { it.isNotEmpty() && it.all { session -> session.id in mark.marked(service.pins) } }
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val ids = selected().map { it.id }
            service.updatePins { mark.change(it, ids, state) }
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
            service.updateFolders(ChangeSource.UI, drop.change)
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
        private const val SHOW_SETTLED = "dev.myair.showSettled"
        /** Points added to the label font, in [FONT_SIZES]. */
        private const val FONT_SIZE = "dev.myair.fontSize"
        private val FONT_SIZES = -4..12
        private const val TOOLBAR_CARD = "toolbar"
        private const val SEARCH_CARD = "search"
        private const val LOADING = "Loading sessions..."
    }
}

/** A mark kept in [Pins], toggled from the row menus and the row buttons; [change] sets it on the ids or clears it. */
private class Mark(val text: String, val description: String, val icon: Icon, val marked: (Pins) -> Set<String>, val change: (Pins, List<String>, Boolean) -> Pins)

/** A running session is listed anyway; a pin keeps it there after its process ends. */
private val PIN = Mark("Pin Session", "Keep the session in the list above the tree after its process ends", AllIcons.General.Pin_tab, Pins::sessions) { pins, ids, on ->
    if (on) pins.pin(ids) else pins.unpin(ids)
}

private val SETTLE = Mark("Settled", "Hide the session from the list and the tree; Show All Sessions shows it again", AllIcons.Actions.Checked, Pins::settled) { pins, ids, on ->
    if (on) pins.settle(ids) else pins.unsettle(ids)
}

private class TrailerList : JBList<ClaudeSession>() {
    lateinit var trailer: SessionTrailer

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        trailer.paint(g)
    }
}

/**
 * Draws a drop itself: a line between rows, a frame around the row dropped onto. IntelliJ's tree UI would instead fill the
 * row of the drop's parent, which flickers as the pointer crosses rows, so the drop handler keeps it from showing.
 */
private class DropLineTree : SimpleTree() {
    lateinit var trailer: SessionTrailer
    var drop: Drop? = null
        set(value) {
            field = value
            repaint()
        }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        trailer.paint(g)
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

/** A session's row in the tree and in the pinned list: title, status icon and label, settled mark, optionally its project; [SessionTrailer] adds the age. */
private fun SimpleColoredComponent.appendSession(item: ClaudeSession, live: LiveSession?, settled: Boolean, showProject: Boolean) {
    // A session without a live process is greyed out, so the running ones stand out in a long list.
    append(item.title, if (live == null) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
    live?.status?.let { icon = it.icon }
    when (live?.status) {
        SessionStatus.WORKING -> append("  working", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        SessionStatus.NEEDS_INPUT -> append("  needs input" + (live.waitingFor?.let { ": $it" } ?: ""), SimpleTextAttributes.ERROR_ATTRIBUTES)
        SessionStatus.IDLE -> append("  waiting for prompt", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        null -> {}
    }
    if (settled) append("  settled", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
    if (showProject) append("  ${item.cwd.fileName ?: item.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
}

/** Sessions from every project, so each row names its project; a row without a process is there because it is pinned. */
private class PinnedRenderer(private val liveOf: (ClaudeSession) -> LiveSession?, private val settled: (ClaudeSession) -> Boolean) :
    ColoredListCellRenderer<ClaudeSession>() {
    override fun customizeCellRenderer(list: JList<out ClaudeSession>, value: ClaudeSession, index: Int, selected: Boolean, hasFocus: Boolean) {
        val live = liveOf(value)
        appendSession(value, live, settled(value), showProject = true)
        if (live == null) icon = AllIcons.General.Pin_tab
    }
}

private class SessionRenderer(private val liveOf: (ClaudeSession) -> LiveSession?, private val settled: (ClaudeSession) -> Boolean) :
    ColoredTreeCellRenderer() {
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
            is ClaudeSession -> appendSession(item, liveOf(item), settled(item), showProject = (node.parent as DefaultMutableTreeNode).userObject is Folder)
            is ChangedFile -> {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(item.path.fileName.toString()).icon
                // Files inside the session's directory read relative to it; anything else keeps its full path.
                append(if (item.path.startsWith(item.cwd)) item.cwd.relativize(item.path).toString() else item.path.toString())
            }
        }
    }
}
