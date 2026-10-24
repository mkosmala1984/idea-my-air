package dev.myair

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class SessionsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionsPanel(project, toolWindow)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, null, false))
    }
}

/** A file a session's agent wrote or edited; toString() is the node id TreeState keeps the selection by. */
private class ChangedFile(val path: Path, val cwd: Path) {
    override fun toString() = path.toString()
}

private class SessionsPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val service = ClaudeSessionsService.getInstance()
    private var shown = emptyList<ClaudeSession>()
    private val tree = SimpleTree().apply {
        isRootVisible = false
        // Double-click opens a session, so it must not also expand its changed files.
        toggleClickCount = 0
        cellRenderer = SessionRenderer { service.live[it.id] }
    }
    private val filter = SearchTextField(false).apply {
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = render()
        })
    }

    init {
        setContent(JPanel(BorderLayout()).apply {
            add(filter, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        })
        val refresh = object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = service.refresh()
        }
        val newSession = ActionManager.getInstance().getAction("dev.myair.NewClaudeSession")
        toolbar = ActionManager.getInstance().createActionToolbar("ClaudeSessions", DefaultActionGroup(newSession, refresh), true)
            .apply { targetComponent = tree }.component
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent) = openSelected()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
        // Statuses alone only repaint, which keeps expansion and selection; a changed list rebuilds the model.
        service.subscribe(toolWindow.disposable) {
            if (service.sessions != shown) render() else tree.repaint()
        }
        render()
    }

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

    /** Keeps expanded nodes and the selection across rebuilds; a non-empty filter expands every project, so all matches are visible at once. */
    private fun render() {
        shown = service.sessions
        val query = filter.text.trim()
        val state = TreeState.createOn(tree)
        tree.model = DefaultTreeModel(buildRoot(shown.filter { it.matches(query) }))
        if (query.isNotEmpty()) TreeUtil.expandAll(tree) else state.applyTo(tree)
    }

    /** Projects ordered by their most recent session; [sessions] arrive newest first. A session's children are its changed files. */
    private fun buildRoot(sessions: List<ClaudeSession>) = DefaultMutableTreeNode().apply {
        sessions.groupBy { it.cwd }.forEach { (cwd, group) ->
            add(DefaultMutableTreeNode(cwd).apply {
                group.forEach { session ->
                    add(DefaultMutableTreeNode(session).apply { session.changedFiles.forEach { add(DefaultMutableTreeNode(ChangedFile(it, cwd))) } })
                }
            })
        }
    }
}

private class SessionRenderer(private val liveOf: (ClaudeSession) -> LiveSession?) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        when (val item = (value as DefaultMutableTreeNode).userObject) {
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
