package dev.myair

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ide.util.treeView.TreeState
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class SessionsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, null, false))
    }
}

private val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
private val scanner = ClaudeSessionScanner(claudeHome.resolve("projects"))
private val registry = LiveSessionRegistry(claudeHome.resolve("sessions"))

private class SessionsPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    /** Session id to status of its running `claude` process; a session absent here has no process. */
    @Volatile
    private var live = emptyMap<String, LiveSession>()
    private val tree = SimpleTree().apply {
        isRootVisible = false
        cellRenderer = SessionRenderer { live[it.id] }
    }
    private var sessions = emptyList<ClaudeSession>()
    /** Ids on the list, for the poller; null until the first scan, so its own startup does not count as new sessions. */
    @Volatile
    private var listed: Set<String>? = null
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
            override fun actionPerformed(e: AnActionEvent) = reload()
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
        // Statuses change within seconds; repainting keeps the tree's expansion and selection, unlike a model rebuild.
        // The poller runs serially, so `previous` needs no synchronisation.
        var previous = emptyMap<String, LiveSession>()
        val poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            try {
                val current = registry.read()
                val known = listed
                if (known != null && LiveSessionRegistry.hasNewUnlisted(previous, current, known)) reload()
                previous = current
                if (current != live) ApplicationManager.getApplication().invokeLater {
                    live = current
                    tree.repaint()
                }
            } catch (e: Throwable) {
                thisLogger().error("Reading Claude session statuses failed", e)
            }
        }, 0, 2, TimeUnit.SECONDS)
        Disposer.register(parent) { poll.cancel(false) }
        reload()
    }

    private fun openSelected(): Boolean {
        val session = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ClaudeSession ?: return false
        ClaudeSessionOpener.open(project, session)
        return true
    }

    private fun reload() {
        // The Future is discarded, so an exception would vanish with it and leave an empty list; log it as an IDE error instead.
        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = try {
                scanner.scan()
            } catch (e: Throwable) {
                thisLogger().error("Claude session scan failed", e)
                return@executeOnPooledThread
            }
            listed = scanned.mapTo(HashSet()) { it.id }
            ApplicationManager.getApplication().invokeLater {
                sessions = scanned
                render()
            }
        }
    }

    /** Keeps expanded projects across rebuilds; a non-empty filter expands every group, so all matches are visible at once. */
    private fun render() {
        val query = filter.text.trim()
        val state = TreeState.createOn(tree)
        tree.model = DefaultTreeModel(buildRoot(sessions.filter { it.matches(query) }))
        if (query.isNotEmpty()) TreeUtil.expandAll(tree) else state.applyTo(tree)
    }

    /** Projects ordered by their most recent session; [sessions] arrive newest first. */
    private fun buildRoot(sessions: List<ClaudeSession>) = DefaultMutableTreeNode().apply {
        sessions.groupBy { it.cwd }.forEach { (cwd, group) ->
            add(DefaultMutableTreeNode(cwd).apply { group.forEach { add(DefaultMutableTreeNode(it)) } })
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
        }
    }
}
