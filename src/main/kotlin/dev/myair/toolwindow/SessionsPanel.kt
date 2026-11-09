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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SearchTextField
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.ui.JBFont
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.openapi.application.ApplicationManager
import dev.myair.planview.PlanOpener
import dev.myair.actions.Git
import dev.myair.actions.NewSessionInDirAction
import dev.myair.actions.openInIdeaAction
import dev.myair.actions.openInTerminalAction
import dev.myair.actions.PresetsGroup
import dev.myair.actions.SameSettingsAction
import dev.myair.actions.SaveAsPresetAction
import dev.myair.actions.ShowChangesVsBaseAction
import dev.myair.claude.ClaudeSession
import dev.myair.claude.SessionStatus
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.projectBase
import dev.myair.folders.Folder
import dev.myair.service.ActiveSession
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.gitWorktrees
import dev.myair.store.pinnedSessions
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import kotlin.io.path.exists
import java.time.Instant
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import kotlin.math.max
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class SessionsPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val service = ClaudeSessionsService.getInstance()
    private var foldersMode = PropertiesComponent.getInstance().getBoolean(FOLDERS_MODE)
    private var showSettled = PropertiesComponent.getInstance().getBoolean(SHOW_SETTLED)
    /** The tree hides sessions whose last message is this many days old or older; 0 hides none. */
    private var maxAgeDays = PropertiesComponent.getInstance().getInt(MAX_AGE_DAYS, 0)
    private var density = PropertiesComponent.getInstance().getValue(DENSITY)?.let(Density::valueOf) ?: Density.COMPACT
    /** A single click on a session's row opens it, as a double-click does. */
    private var singleClick = PropertiesComponent.getInstance().getBoolean(SINGLE_CLICK)
    /** The tree selects, and both lists scroll to, the session of the window's active session tab when it changes. */
    private var followActive = PropertiesComponent.getInstance().getBoolean(FOLLOW_ACTIVE, true)
    /** The session of this window's active session tab ([ActiveSession]); its rows carry a bar and its pinned row a tint. */
    private var activeId: String? = null
    /** A session [followActive] has yet to select, as the tree did not show it yet; selected by the [render] that shows it. */
    private var pendingReveal: String? = null
    /** The tree shows only the sessions of [windowRoot] ([inWorkTree]); kept per project, so each window has its own. */
    private var workTreeOnly = PropertiesComponent.getInstance(project).getBoolean(WORK_TREE_ONLY)
    /** The work tree this window's project lies in, read off the EDT once; null until read and outside a repository. */
    private var windowRoot: Path? = null
    /** Whether [windowRoot] has been read. */
    private var rootRead = false
    private var shown: List<Any?> = emptyList()
    /** The nodes the tree shows expanded outside a search, by [stateKey]; kept in application properties. */
    private val states = HashMap<String, TreeState>()
    /** While [render] swaps the model and applies the state, whose expansion events must not replace it. */
    private var rendering = false
    /** The branch checked out in each project and folder directory the tree shows, read off the EDT by [readBranches]. */
    private var branches: Map<Path, String?> = emptyMap()

    /** The linked worktrees of every directory a folder is bound to, newest first, read with [branches]. */
    private var worktrees: Map<Path, List<WorktreeRow>> = emptyMap()

    /** The directories of [branches] that do not exist, read with them. */
    private var gone: Set<Path> = emptySet()
    /** The number of [readBranches] calls; only the latest read lands. */
    private var branchReads = 0
    private val tree = DropLineTree().apply {
        isRootVisible = false
        // Double-click opens a session, so it must not also expand its changed files.
        toggleClickCount = 0
        cellRenderer = SessionRenderer({ service.live[it.id] }, ::isSettled, ::isUnread, ::density, branches::get) { it in gone }
        allowSpinner()
        dropMode = DropMode.ON_OR_INSERT
        // The room above the first row the design leaves.
        border = JBUI.Borders.emptyTop(4)
    }
    private val pinned: TrailerList = TrailerList().apply {
        cellRenderer = PinnedRenderer({ service.live[it.id] }, ::isSettled, ::isUnread, service::branchOf, ::pinnedLayout, ::pinnedBackground) { repaint() }
        allowSpinner()
    }
    /** The pinned list's rows for the font and [density]; set by [applyFontSize]. */
    private lateinit var pinnedLayout: PinnedLayout
    private val lists = JPanel(BorderLayout())
    /** The height, in unscaled pixels, the user dragged the pinned list's lower edge to; 0 until dragged, [PINNED_MAX_SHARE] of the panel then. */
    private var pinnedMax = PropertiesComponent.getInstance().getInt(PINNED_MAX, 0)
    /** The pinned list as high as dragged to ([pinnedMax]), else as its rows, scrolling beyond ([pinnedHeight]); hidden when loaded and empty. */
    private val pinnedPane = object : JBScrollPane(pinned) {
        override fun getPreferredSize(): Dimension {
            // The list's own height counts its insets; before the first load it shows its empty text in one row.
            val content = if (service.loaded) pinned.preferredSize.height else pinned.fixedCellHeight
            val dragged = if (pinnedMax > 0) JBUI.scale(pinnedMax) else null
            val share = (lists.height * PINNED_MAX_SHARE).toInt()
            return Dimension(0, pinnedHeight(content, pinned.fixedCellHeight, dragged, share, lists.height, JBUI.scale(TREE_MIN)))
        }
    }.apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }
    /** The pinned list's lower edge: a line the user drags to set [pinnedMax]. */
    private val pinnedEdge = object : JComponent() {
        init {
            cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
            preferredSize = Dimension(0, JBUI.scale(EDGE))
            val drag = object : MouseAdapter() {
                private var startY = 0
                private var startHeight = 0
                override fun mousePressed(e: MouseEvent) {
                    startY = e.yOnScreen
                    startHeight = pinnedPane.height
                }
                override fun mouseDragged(e: MouseEvent) {
                    pinnedMax = max(1, ((startHeight + e.yOnScreen - startY) / JBUIScale.scale(1f)).toInt())
                    fitPinned()
                }
                override fun mouseReleased(e: MouseEvent) = PropertiesComponent.getInstance().setValue(PINNED_MAX, pinnedMax, 0)
            }
            addMouseListener(drag)
            addMouseMotionListener(drag)
        }
        override fun paintComponent(g: Graphics) {
            g.color = JBColor.border()
            g.fillRect(0, height / 2, width, 1)
        }
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
        // Both take their tooltips from the renderer, which sets one per session row.
        listOf(tree, pinned).forEach(ToolTipManager.sharedInstance()::registerComponent)
        tree.transferHandler = FolderDrop(tree, service, ::canEditFolders)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = rememberState()
            override fun treeCollapsed(event: TreeExpansionEvent) = rememberState()
        })
        tree.trailer = SessionTrailer(
            tree, { tree.getClosestRowForLocation(it.x, it.y) }, tree::getRowBounds,
            { (tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession },
            { folderSummary(tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode) { s -> urgencyOf(service.live[s.id], isUnread(s)) } },
            { DefaultTreeUI.getBackground(tree, tree.getPathForRow(it), it, tree.isRowSelected(it)) ?: tree.background }, trailerButtons, ::density,
        )
        pinned.trailer = SessionTrailer(
            pinned, pinned::locationToIndex, { pinned.getCellBounds(it, it) }, { pinned.model.getElementAt(it) }, { null },
            ::pinnedBackground, trailerButtons, ::density, { pinnedLayout.firstLine(it) },
        )
        tree.activeRows = {
            (0 until tree.rowCount).filter { sessionAt(tree.getPathForRow(it))?.id == activeId }.map(tree::getRowBounds)
        }
        pinned.activeRows = { activePinnedRow()?.let { listOf(pinned.getCellBounds(it, it)) }.orEmpty() }
        lists.add(JPanel(BorderLayout()).apply {
            add(pinnedPane, BorderLayout.CENTER)
            add(pinnedEdge, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        lists.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        setContent(lists)
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
        val workTree = object : ToggleAction("This Work Tree", null, AllIcons.Vcs.Branch), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isEnabled = windowRoot != null
                e.presentation.description = when {
                    !rootRead -> "Reading the work tree of this project..."
                    windowRoot == null -> "Not in a Git work tree"
                    else -> "Show only sessions that work or started in $windowRoot"
                }
            }
            override fun isSelected(e: AnActionEvent) = workTreeOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                workTreeOnly = state
                PropertiesComponent.getInstance(project).setValue(WORK_TREE_ONLY, state)
                render()
            }
        }
        val editing = FolderEditing(project, tree, service)
        val newFolder = FolderAction("New Folder", AllIcons.Actions.NewFolder, ::foldersMode, ::canEditFolders, editing::newFolder)
        val rename = FolderAction("Rename...", null, ::foldersMode, { canEditFolders() && editing.canRename() }, editing::rename)
        val delete = FolderAction("Delete...", AllIcons.Actions.GC, ::foldersMode, { canEditFolders() && editing.canRename() }, editing::delete)
        delete.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree)
        fun on(test: (Any?) -> Boolean) = { foldersMode && test(editing.selected()) }
        val planActions = listOf(
            FolderAction("Open Plan", AllIcons.Nodes.Target, on { editing.selectedPlanFolder() != null }, { true }) { PlanOpener.open(project, editing.selectedPlanFolder()!!.id) },
            FolderAction("New Plan...", AllIcons.Nodes.Target, ::foldersMode, { canEditFolders() && editing.canNewPlan() }, editing::newPlan),
            FolderAction("Make Folder a Plan", null, on { it is Folder && editing.canAddPlan() }, ::canEditFolders, editing::addPlan),
            FolderAction("Remove Plan", null, on { it is Folder && it.plan != null }, { canEditFolders() && editing.canRemovePlan() }, editing::removePlan),
            FolderAction("Bind Directory...", null, on { it is Folder }, ::canEditFolders, editing::bindDirectory),
            FolderAction("Unbind Directory...", null, on { it is Folder && it.dirs.isNotEmpty() }, ::canEditFolders, editing::unbindDirectory),
            FolderAction("Set Working Directory...", null, on { it is Folder }, ::canEditFolders, editing::setWorkingDirectory),
            FolderAction("Clear Working Directory", null, on { it is Folder && it.workdir != null }, ::canEditFolders, editing::clearWorkingDirectory),
            FolderAction("Add Milestone...", AllIcons.Nodes.Bookmark, on { (it is Folder && it.plan != null) || it is MilestoneRow }, ::canEditFolders, editing::addMilestone),
            FolderAction("Add Task...", AllIcons.General.Add, on { it is MilestoneRow || it is PlanTaskRow }, ::canEditFolders, editing::addTask),
            FolderAction("Mark Milestone Done", AllIcons.Actions.Checked, on { it is MilestoneRow && !it.milestone.done }, ::canEditFolders) { editing.setMilestoneDone(true) },
            FolderAction("Reopen Milestone", null, on { it is MilestoneRow && it.milestone.done }, ::canEditFolders) { editing.setMilestoneDone(false) },
            editing.statusGroup(on { it is PlanTaskRow }, ::canEditFolders),
            FolderAction("Edit Next Step...", null, on { it is PlanTaskRow }, ::canEditFolders, editing::editNext),
            FolderAction("Edit Description...", AllIcons.Actions.Edit, on { (it is Folder && it.plan != null) || it is MilestoneRow || it is PlanTaskRow }, ::canEditFolders, editing::editDescription),
        )
        val inTree = { tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession } }
        val selectedDir = { tree.selectionPaths?.singleOrNull()?.let(::nodeDir) }
        val selectedItem = { (tree.selectionPaths?.singleOrNull()?.lastPathComponent as? DefaultMutableTreeNode)?.userObject }
        val selectedOpenDir = { tree.selectionPaths?.singleOrNull()?.let(::openDir) }
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(
                NewSessionInDirAction("New Claude Session", false, selectedDir, { tree.selectionPaths?.singleOrNull()?.let(::nodeProject) }, editing::selectedPlanStart) { editing.selectedFolder()?.id },
                NewSessionInDirAction("New Claude Session in Worktree...", true, { selectedDir().takeUnless { selectedItem() is WorktreeRow } }, selectedDir, editing::selectedPlanStart) { editing.selectedFolder()?.id },
                openInIdeaAction(selectedOpenDir), openInTerminalAction(selectedOpenDir), Separator.getInstance(),
                pinAction(service, inTree), settleAction(service, inTree), Separator.getInstance(), SameSettingsAction(inTree), SaveAsPresetAction(inTree), ShowChangesVsBaseAction(inTree),
                Separator.getInstance(), newFolder, rename, delete, Separator.getInstance(), *planActions.toTypedArray(),
            ),
            "ClaudeSessionsPopup",
        )
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
        val inList = { pinned.selectedValuesList }
        val pinnedPopup = DefaultActionGroup(
            pinAction(service, inList), settleAction(service, inList), remove, Separator.getInstance(), SameSettingsAction(inList), SaveAsPresetAction(inList), ShowChangesVsBaseAction(inList),
        )
        pinned.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val row = pinned.locationToIndex(Point(x, y))
                if (row >= 0 && !pinned.isSelectedIndex(row)) pinned.selectedIndex = row
                ActionManager.getInstance().createActionPopupMenu("ClaudePinnedPopup", pinnedPopup).component.show(comp, x, y)
            }
        })
        val withPrompt = ActionManager.getInstance().getAction("dev.myair.NewSessionWithPrompt")
        val newSession = ActionManager.getInstance().getAction("dev.myair.NewClaudeSession")
        val newSessionInWorktree = ActionManager.getInstance().getAction("dev.myair.NewClaudeSessionInWorktree")
        val clickToOpen = object : ToggleAction("Open Sessions with Single Click"), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = singleClick
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                singleClick = state
                PropertiesComponent.getInstance().setValue(SINGLE_CLICK, state)
            }
        }
        val follow = object : ToggleAction("Select Session of Active Tab"), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = followActive
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                followActive = state
                PropertiesComponent.getInstance().setValue(FOLLOW_ACTIVE, state, true)
                if (state) activeId?.let(::reveal)
            }
        }
        val options = DefaultActionGroup("View Options", true).apply {
            templatePresentation.icon = AllIcons.Actions.Properties
            addAll(
                mode, showAll, clickToOpen, follow, Separator.create("Hide Sessions Older Than"),
                MaxAge(3), MaxAge(7), MaxAge(14), MaxAge(30), Separator.create("Density"),
                *Density.entries.map(::DensityOption).toTypedArray(), Separator.getInstance(),
                FontSize("Increase Font Size") { it + 1 }, FontSize("Decrease Font Size") { it - 1 }, FontSize("Reset Font Size") { 0 },
                Separator.getInstance(), refresh,
            )
        }
        val search = object : DumbAwareAction("Search", "Filter sessions by title, project path or branch", AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) = showSearch(true)
        }
        search.registerCustomShortcutSet(CommonShortcuts.getFind(), this)
        fun bar(place: String, vararg actions: AnAction) =
            ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), true).apply { targetComponent = tree }.component
        val actions = JPanel(BorderLayout()).apply {
            add(bar("ClaudeSessions", withPrompt, newSession, newSessionInWorktree, PresetsGroup(), newFolder), BorderLayout.CENTER)
            add(bar("ClaudeSessionsView", workTree, options, search), BorderLayout.EAST)
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
        onClick(tree, tree.trailer, { tree.getRowForLocation(it.x, it.y) >= 0 }) {
            tree.selectionPaths?.singleOrNull()?.let { sessionOf((it.lastPathComponent as DefaultMutableTreeNode).userObject) }
        }
        onClick(pinned, pinned.trailer, { p -> pinned.locationToIndex(p).let { it >= 0 && pinned.getCellBounds(it, it).contains(p) } }) {
            pinned.selectedValuesList.singleOrNull()
        }
        // Ages change with time alone, while the service notifies only on changes; with [maxAgeDays] they also drop sessions.
        val ages = Timer(60_000) {
            updateTree()
            pinned.repaint()
        }
        ages.start()
        Disposer.register(toolWindow.disposable) { ages.stop() }
        PaneFont.subscribe(toolWindow.disposable, ::applyFontSize)
        service.subscribe(toolWindow.disposable) {
            renderPinned()
            updateTree()
        }
        renderPinned()
        render()
        ActiveSession.getInstance(project).subscribe(toolWindow.disposable, ::activeChanged)
        activeChanged()
        val base = projectBase(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val root = Git.head(base)?.root
            ApplicationManager.getApplication().invokeLater({
                windowRoot = root
                rootRead = true
                render()
            }, project.disposed)
        }
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

    /** Both session lists in the label font grown by [PaneFont]; their rows are as high as [density] says, grown with the font's height. */
    private fun applyFontSize() {
        val base = JBFont.label()
        val font = PaneFont.grow(base)
        tree.font = font
        pinned.font = font
        tree.rowHeight = JBUI.scale(density.rowHeight) + tree.getFontMetrics(font).height - tree.getFontMetrics(base).height
        pinnedLayout = PinnedLayout(font, density, pinned::getFontMetrics)
        pinned.fixedCellHeight = pinnedLayout.height
        fitPinned()
    }

    /** Lays the panel out again for [pinnedPane]'s height; the scroll pane is a validate root, so the panel above it is revalidated. */
    private fun fitPinned() {
        pinnedPane.isVisible = !service.loaded || pinned.model.size > 0
        pinnedEdge.isVisible = pinnedPane.isVisible
        // Invalidates from the pane up: its parent caches its preferred size while valid, which lists.revalidate() keeps.
        pinnedPane.revalidate()
        lists.repaint()
    }

    /** Sets [density] to [option] and lays both lists out anew. */
    private inner class DensityOption(private val option: Density) :
        ToggleAction(option.name.lowercase().replaceFirstChar(Char::uppercase)), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = density == option
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            density = option
            PropertiesComponent.getInstance().setValue(DENSITY, option.name)
            applyFontSize()
            tree.repaint()
            pinned.repaint()
        }
    }

    /** Sets [PaneFont.points] to [next] of it; disabled where that leaves it as it is or outside [PaneFont.RANGE]. */
    private class FontSize(text: String, private val next: (Int) -> Int) : DumbAwareAction(text) {
        init {
            // Several steps in a row, without reopening the menu.
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val size = PaneFont.points
            e.presentation.isEnabled = next(size).let { it != size && it in PaneFont.RANGE }
        }
        override fun actionPerformed(e: AnActionEvent) {
            PaneFont.points = next(PaneFont.points)
        }
    }

    /** Sets [maxAgeDays] to [days]; choosing the selected one again shows sessions of every age. */
    private inner class MaxAge(private val days: Int) : ToggleAction("$days Days"), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = maxAgeDays == days
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            maxAgeDays = if (state) days else 0
            PropertiesComponent.getInstance().setValue(MAX_AGE_DAYS, maxAgeDays, 0)
            render()
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

    /**
     * While [singleClick] is on, a plain left click on a row of [component] ([onRow]) opens the session [selected] gives
     * for the selection the click made; a click with a modifier extends the selection and a click on a [trailer] button
     * runs it, so neither opens.
     */
    private fun onClick(component: JComponent, trailer: SessionTrailer, onRow: (Point) -> Boolean, selected: () -> ClaudeSession?) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!singleClick || e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e) || e.modifiersEx != 0) return
                if (trailer.isButton(e.point) || !onRow(e.point)) return
                selected()?.let { ClaudeSessionOpener.open(project, it) }
            }
        })
    }

    /** Marks the session of the active session tab anew; when it is another one and [followActive] is on, [reveal]s it. */
    private fun activeChanged() {
        val id = ActiveSession.getInstance(project).tab?.sessionId
        if (id != activeId) {
            activeId = id
            if (followActive && id != null) reveal(id)
        }
        tree.repaint()
        pinned.repaint()
    }

    /**
     * Scrolls the pinned list to session [id] and selects its first row in the tree, expanding the rows above it; a tree
     * that shows it nowhere yet (not loaded, filtered out) selects it once a [render] does ([pendingReveal]). A selected
     * row of it stays, so a click on one of a session's several rows does not jump to another.
     */
    private fun reveal(id: String) {
        activePinnedRow()?.let(pinned::ensureIndexIsVisible)
        pendingReveal = null
        if (tree.selectionPaths.orEmpty().any { sessionAt(it)?.id == id }) return
        val node = (tree.model.root as DefaultMutableTreeNode).preorderEnumeration().asSequence()
            .firstOrNull { ((it as DefaultMutableTreeNode).userObject as? ClaudeSession)?.id == id }
        if (node == null) {
            pendingReveal = id
            return
        }
        val path = TreePath((node as DefaultMutableTreeNode).path)
        tree.makeVisible(path)
        TreeUtil.selectPath(tree, path)
    }

    private fun activePinnedRow() = (0 until pinned.model.size).firstOrNull { pinned.model.getElementAt(it).id == activeId }

    /** The session of a session's own row; null for any other. */
    private fun sessionAt(path: TreePath) = (path.lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession

    /** Rebuilt on every change, since a status change reorders it; the selection follows the session ids. */
    private fun renderPinned() {
        val selected = pinned.selectedValuesList.map { it.id }.toSet()
        pinned.emptyText.text = if (service.loaded) "No running or pinned sessions" else LOADING
        val items = pinnedSessions(shownSessions(), service.live, service.pins, service.unread)
        pinned.setListData(items.toTypedArray())
        pinned.selectedIndices = items.indices.filter { items[it].id in selected }.toIntArray()
        fitPinned()
    }

    /** Statuses alone only repaint, which keeps expansion and selection; anything else rebuilds the model. */
    private fun updateTree() = if (snapshot() != shown) render() else {
        tree.repaint()
        readBranches()
    }

    /**
     * Reads the branch of every directory a project, folder or session row shows and which of them are gone, then the
     * linked worktrees of every bound directory (of all folders, as a filter may hide some), off the EDT, and rebuilds the
     * tree when either changed, since a session's directories have [BranchRow]s of their own. The two land apart, so a
     * worktree whose files cannot be read (it throws) leaves the branches current. Runs
     * with every refresh of the tree, so a checkout or a removal shows within the minute of [updateTree]'s timer.
     */
    private fun readBranches() {
        val dirs = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList().flatMap {
            when (val item = (it as DefaultMutableTreeNode).userObject) {
                is Folder -> (item.dirs + listOfNotNull(item.workdir)).map(Path::of)
                is Path -> listOf(item)
                is WorkDirRow -> listOf(item.session.workDir)
                is AddedDir -> listOf(item.path)
                else -> emptyList()
            }
        }.toSet()
        val bound = service.folders.all().flatMap { it.dirs }.map(Path::of).toSet()
        val read = ++branchReads
        fun land(changed: () -> Boolean, apply: () -> Unit) = ApplicationManager.getApplication().invokeLater {
            if (read == branchReads && changed()) {
                apply()
                render()
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = dirs.associateWith(Git::headBranch)
            val missing = dirs.filterNot { it.exists() }.toSet()
            land({ found != branches || missing != gone }) {
                branches = found
                gone = missing
            }
            val trees = bound.associateWith { root ->
                gitWorktrees(root).map { WorktreeRow(it.path, root, Git.headBranch(it.path), it.created, !it.path.exists()) }
            }
            land({ trees != worktrees }) { worktrees = trees }
        }
    }

    // The heads, since a checkout changes what the work tree filter and a search by branch keep.
    private fun snapshot() = listOf(
        service.loaded, foldersMode, showSettled, treeSessions(), service.pins.settled, service.folders, service.foldersError, service.heads,
    )

    /** The work tree the tree is narrowed to: [windowRoot] while [workTreeOnly] is on, else null. */
    private fun workTreeRoot() = windowRoot.takeIf { workTreeOnly }

    /** What the tree keeps of [treeSessions]: those of [workTreeRoot] matching [query]; null keeps all. */
    private fun treeFilter(query: String): ((ClaudeSession) -> Boolean)? {
        val root = workTreeRoot()
        if (root == null && query.isEmpty()) return null
        val heads = service.heads
        return { (root == null || inWorkTree(it, root, heads)) && it.matches(query, service.branchOf(it)) }
    }

    private fun shownSessions() = service.pins.shown(service.listed, showSettled)

    /** [shownSessions] less those older than [maxAgeDays]; the list above keeps them, as it holds running and pinned ones. */
    private fun treeSessions(): List<ClaudeSession> {
        if (maxAgeDays == 0) return shownSessions()
        val now = Instant.now()
        return shownSessions().filter { it.isWithin(maxAgeDays, now) }
    }

    /**
     * A pinned row's background, which its renderer paints over the list UI's, so a selected row shows none: hovered, the
     * hover colour (null when hover painting is off), else amber when its session needs input, else the list's.
     */
    private fun pinnedBackground(row: Int): Color =
        RenderingUtil.getHoverBackground(pinned).takeIf { ListHoverListener.getHoveredIndex(pinned) == row }
            ?: NEEDS_INPUT_TINT.takeIf { service.live[pinned.model.getElementAt(row).id]?.status == SessionStatus.NEEDS_INPUT }
            ?: RenderingUtil.getBackground(pinned)

    private fun isSettled(session: ClaudeSession) = session.id in service.pins.settled

    private fun isUnread(session: ClaudeSession) = session.id in service.unread

    private fun canEditFolders() = foldersMode && service.foldersError == null

    private fun openSelected(): Boolean {
        val path = tree.selectionPath ?: return false
        val item = (path.lastPathComponent as DefaultMutableTreeNode).userObject
        val session = sessionOf(item)
        when {
            session != null -> ClaudeSessionOpener.open(project, session)
            item is ChangedFile -> openFile(project, item.path)
            else -> if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        }
        return true
    }

    /** The session a row opens: a session's own, or that of a row about it other than a file; null for any other row. */
    private fun sessionOf(item: Any?): ClaudeSession? = when (item) {
        is ClaudeSession -> item
        is BriefGoal -> item.session
        is BriefTaskRow -> item.session
        is AddedDir -> item.session
        is WorkDirRow -> item.session
        is BranchRow -> item.session
        else -> null
    }

    /** The narrowed tree keeps its own state, so turning the filter off brings back the tree as it was. */
    private fun stateKey() = "$TREE_STATE." + (if (foldersMode) "folders" else "projects") + if (workTreeRoot() != null) ".workTree" else ""

    private fun state() = stateKey().let { key -> states.getOrPut(key) { treeStateOf(PropertiesComponent.getInstance().getValue(key)) } }

    /**
     * Takes what the user expanded or collapsed as the tree's state; not during a search, which expands everything, so
     * closing it brings back the tree as it was before.
     */
    private fun rememberState() {
        if (rendering || filter.text.isNotBlank()) return
        val state = TreeState.createOn(tree)
        states[stateKey()] = state
        PropertiesComponent.getInstance().setValue(stateKey(), treeStateXml(state))
    }

    /**
     * Keeps expanded nodes across rebuilds and restarts ([state]); a non-empty filter expands
     * everything, so all matches are visible at once. Folders mode with an unreadable `structure.json` shows the error
     * instead of the tree.
     */
    private fun render() {
        shown = snapshot()
        val query = filter.text.trim()
        val error = service.foldersError.takeIf { foldersMode }
        tree.emptyText.text = when {
            !service.loaded -> LOADING
            error != null -> "Cannot read ${service.structureFile}: $error"
            workTreeRoot() != null -> "No sessions in this work tree"
            else -> StatusText.getDefaultEmptyText()
        }
        val filter = treeFilter(query)
        tree.dragEnabled = foldersMode
        // The drop shown refers to nodes of the old model; the next pointer move computes it on the new one.
        tree.drop = null
        rendering = true
        try {
            tree.model = DefaultTreeModel(
                when {
                    error != null -> DefaultMutableTreeNode()
                    foldersMode -> foldersTree(service.folders, treeSessions(), branches::get, { worktrees[it].orEmpty() }, filter)
                    else -> projectsTree(filter?.let(treeSessions()::filter) ?: treeSessions(), branches::get)
                },
            )
            if (query.isNotEmpty()) TreeUtil.expandAll(tree) else state().applyTo(tree)
        } finally {
            rendering = false
        }
        pendingReveal?.let(::reveal)
        readBranches()
    }

    companion object {
        private const val FOLDERS_MODE = "dev.myair.foldersMode"
        private const val SHOW_SETTLED = "dev.myair.showSettled"
        private const val MAX_AGE_DAYS = "dev.myair.maxAgeDays"
        private const val TREE_STATE = "dev.myair.treeState"
        private const val WORK_TREE_ONLY = "dev.myair.thisWorkTree"
        private const val DENSITY = "dev.myair.density"
        private const val PINNED_MAX_SHARE = 0.4
        private const val PINNED_MAX = "dev.myair.pinnedMaxHeight"
        /** The least the tree keeps below a pinned list dragged long. */
        private const val TREE_MIN = 60
        /** The pinned list's lower edge, its line in the middle, high enough to grab. */
        private const val EDGE = 5
        private const val SINGLE_CLICK = "dev.myair.singleClick"
        private const val FOLLOW_ACTIVE = "dev.myair.followActiveTab"
        private const val TOOLBAR_CARD = "toolbar"
        private const val SEARCH_CARD = "search"
        private const val LOADING = "Loading sessions..."
        /** A tree row at the label font, taller than the platform's for the design's spacing. */
    }
}

/** A working session's spinner turns only where the renderer's parent allows it. */
private fun JComponent.allowSpinner() = putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
