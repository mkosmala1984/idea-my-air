package dev.myair.toolwindow

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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.FolderTarget
import dev.myair.folders.Folder
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.pinnedSessions
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class SessionsPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val service = ClaudeSessionsService.getInstance()
    private var foldersMode = PropertiesComponent.getInstance().getBoolean(FOLDERS_MODE)
    private var showSettled = PropertiesComponent.getInstance().getBoolean(SHOW_SETTLED)
    private var shown: List<Any?> = emptyList()
    private val tree = DropLineTree().apply {
        isRootVisible = false
        // Double-click opens a session, so it must not also expand its changed files.
        toggleClickCount = 0
        cellRenderer = SessionRenderer({ service.live[it.id] }, ::isSettled, ::isUnread)
        dropMode = DropMode.ON_OR_INSERT
    }
    private val pinned = TrailerList().apply {
        cellRenderer = PinnedRenderer({ service.live[it.id] }, ::isSettled, ::isUnread)
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
        tree.transferHandler = FolderDrop(tree, service, ::canEditFolders)
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
        val editing = FolderEditing(project, tree, service)
        val newFolder = FolderAction("New Folder", AllIcons.Actions.NewFolder, ::foldersMode, ::canEditFolders, editing::newFolder)
        val rename = FolderAction("Rename Folder...", null, ::foldersMode, { canEditFolders() && editing.selectedFolder() != null }, editing::renameFolder)
        val delete = FolderAction("Delete Folder...", AllIcons.Actions.GC, ::foldersMode, { canEditFolders() && editing.selectedFolder() != null }, editing::deleteFolder)
        delete.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree)
        val inTree = { tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession } }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(pinAction(service, inTree), settleAction(service, inTree), Separator.getInstance(), newFolder, rename, delete), "ClaudeSessionsPopup")
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
        val pinnedPopup = DefaultActionGroup(pinAction(service) { pinned.selectedValuesList }, settleAction(service) { pinned.selectedValuesList }, remove)
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

    private fun isUnread(session: ClaudeSession) = session.id in service.unread

    private fun canEditFolders() = foldersMode && service.foldersError == null

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
