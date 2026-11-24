package dev.myair.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.UIUtil
import dev.myair.prompt.BAR
import dev.myair.prompt.CARD
import dev.myair.prompt.Chip
import dev.myair.prompt.Rounded
import dev.myair.prompt.SendButton
import dev.myair.prompt.row
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.projectBase
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Opens [FindBranchPanel] on the text in the clipboard: branches of the project's repository that it names, with their worktrees. */
class FindBranchAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        val dir = projectBase(project)
        val running = runningDirs()
        val clipboard = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()
        background(project, "Reading branches", { Git.repo(dir).let { repo -> Triple(repo, Git.remotes(repo.mainRoot).toSet(), Git.worktrees(repo.mainRoot)) } }) { (repo, remotes, worktrees) ->
            lateinit var popup: JBPopup
            val panel = FindBranchPanel(project, dir, repo, remotes, worktrees, running, clipboard) { popup.cancel() }
            popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.query)
                .setRequestFocus(true).setResizable(true).setMovable(true).setCancelOnClickOutside(true)
                .createPopup()
            popup.showCenteredInCurrentWindow(project)
        }
    }
}

/**
 * The composer's look (card, bar, chips, send button): a query field and the [findBranches] matches of its text in [repo]
 * (read before the panel opens, so typing reads no git), chips that start a session in the picked branch's worktree, make
 * one, or make it anew, and under the card the picked worktree's path. Enter and the send button do what the branch
 * allows first: start in its worktree, else make one. [close] closes the popup.
 */
private class FindBranchPanel(
    private val project: Project, private val dir: Path, private val repo: GitRepo, private val remotes: Set<String>,
    private val worktrees: List<Worktree>, private val running: List<Path>, text: String, private val close: () -> Unit,
) : JPanel(VerticalLayout(0)) {
    val query = JBTextField(text).apply {
        font = JBUI.Fonts.label().biggerOn(1f)
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = "A Jira key, a branch name or a task: the matching branches and their worktrees"
    }
    private val model = DefaultListModel<BranchMatch>()
    private val list = JBList(model).apply { isOpaque = false }
    private val start = Chip(AllIcons.Actions.Execute) { start() }.apply { this.text = "Start Here"; chevron = false }
    private val new = Chip(AllIcons.General.Add) { new() }.apply { this.text = "New Worktree"; chevron = false }
    private val recreate = Chip(AllIcons.Actions.Refresh) { recreate() }.apply { this.text = "Recreate Worktree"; chevron = false }
    private val where = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)
        list.cellRenderer = SimpleListCellRenderer.create("") { it.describe() }
        val card = Rounded(CARD, JBUI.scale(16), squareTop = false).apply {
            layout = BorderLayout(0, JBUI.scale(8))
            border = JBUI.Borders.empty(12, 14, 8, 10)
            add(query, BorderLayout.NORTH)
            add(JBScrollPane(list).apply {
                isOpaque = false; viewport.isOpaque = false; border = JBUI.Borders.empty(); preferredSize = JBUI.size(600, 160)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(row(start, new, recreate), BorderLayout.WEST)
                add(SendButton("Start in the worktree, else make one (Enter)") { primary() }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        val bar = Rounded(BAR, JBUI.scale(12), squareTop = true).apply {
            layout = BorderLayout(JBUI.scale(6), 0)
            border = JBUI.Borders.empty(6, 12)
            add(JBLabel(AllIcons.Vcs.Branch), BorderLayout.WEST)
            add(where, BorderLayout.CENTER)
        }
        add(card)
        add(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(0, 24); add(bar) })
        query.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        query.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> { e.consume(); primary() }
                    KeyEvent.VK_DOWN -> { e.consume(); list.selectedIndex = minOf(list.selectedIndex + 1, model.size - 1) }
                    KeyEvent.VK_UP -> { e.consume(); list.selectedIndex = maxOf(list.selectedIndex - 1, 0) }
                }
            }
        })
        list.addListSelectionListener { updateButtons() }
        refresh()
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = maxOf(width, JBUI.scale(720)) }

    private fun BranchMatch.describe(): String = when {
        main -> "$name  (main checkout)"
        worktree != null -> "$name" + (if (worktree.locked) "  [locked]" else "") + (if (running.any { it.startsWith(worktree.path) }) "  [session running]" else "")
        remoteOnly -> "$name  (remote only, no worktree)"
        else -> "$name  (no worktree)"
    }

    private fun refresh() {
        model.clear()
        findBranches(query.text, repo.branches, remotes, worktrees).forEach(model::addElement)
        if (!model.isEmpty) list.selectedIndex = 0
        updateButtons()
    }

    private fun updateButtons() {
        val m = list.selectedValue
        start.isEnabled = m?.worktree != null
        new.isEnabled = m != null && m.worktree == null
        recreate.isEnabled = m?.worktree != null && !m.main
        where.text = when {
            m == null -> "No branch matches"
            m.worktree != null -> m.worktree.path.toString()
            else -> "No worktree: New Worktree makes one on ${m.ref}"
        }
    }

    private fun primary() {
        val m = list.selectedValue ?: return
        if (m.worktree != null) start() else new()
    }

    private fun start() = picked { m -> open(m.worktree!!.path, UUID.randomUUID().toString()) }

    private fun new() = picked { m -> create("Creating worktree") { id -> createWorktree(repo, m.ref, null, id, running) } }

    private fun recreate() = picked { m ->
        create("Recreating worktree") { id ->
            Git.removeWorktree(repo.mainRoot, m.worktree!!) { tree -> running.any { it.startsWith(tree) } }
            createWorktree(repo, m.name, null, id, running)
        }
    }

    private fun picked(action: (BranchMatch) -> Unit) {
        val m = checkNotNull(list.selectedValue) { "a chip is enabled with no match picked" }
        close()
        action(m)
    }

    private fun open(cwd: Path, id: String) = ClaudeSessionOpener.startNew(project, dir, cwd, id)

    private fun create(title: String, make: (id: String) -> Path) {
        val id = UUID.randomUUID().toString()
        background(project, title, { make(id) }) { path -> open(path, id) }
    }
}
