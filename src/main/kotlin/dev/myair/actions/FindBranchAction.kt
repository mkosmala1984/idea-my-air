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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.projectBase
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JButton
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
                .setTitle("Find Branch and Worktree").setRequestFocus(true).setResizable(true).setMovable(true).setCancelOnClickOutside(true)
                .createPopup()
            popup.showCenteredInCurrentWindow(project)
        }
    }
}

/**
 * A query field and the [findBranches] matches of its text in [repo] (read before the panel opens, so typing reads no git),
 * with the buttons that start a session in the picked branch's worktree, make one, or make it anew. [close] closes the popup.
 */
private class FindBranchPanel(
    private val project: Project, private val dir: Path, private val repo: GitRepo, private val remotes: Set<String>,
    private val worktrees: List<Worktree>, private val running: List<Path>, text: String, private val close: () -> Unit,
) : JPanel(BorderLayout(0, 6)) {
    val query = JBTextField(text)
    private val model = DefaultListModel<BranchMatch>()
    private val list = JBList(model)
    private val start = JButton("Start Here")
    private val new = JButton("New Worktree")
    private val recreate = JButton("Recreate Worktree")

    init {
        border = JBUI.Borders.empty(8)
        list.cellRenderer = SimpleListCellRenderer.create("") { it.describe() }
        add(query, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(640, 240) }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { listOf(start, new, recreate).forEach(::add) }, BorderLayout.SOUTH)
        query.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        list.addListSelectionListener { updateButtons() }
        start.addActionListener { picked { m -> open(m.worktree!!.path, UUID.randomUUID().toString()) } }
        new.addActionListener { picked { m -> create("Creating worktree") { id -> createWorktree(repo, m.ref, null, id, running) } } }
        recreate.addActionListener {
            picked { m ->
                create("Recreating worktree") { id ->
                    Git.removeWorktree(repo.mainRoot, m.worktree!!) { tree -> running.any { it.startsWith(tree) } }
                    createWorktree(repo, m.name, null, id, running)
                }
            }
        }
        refresh()
    }

    private fun BranchMatch.describe(): String = when {
        main -> "$name  (main checkout)"
        worktree != null -> "$name  ${worktree.path}" + (if (worktree.locked) "  [locked]" else "") + (if (running.any { it.startsWith(worktree.path) }) "  [session running]" else "")
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
    }

    private fun picked(action: (BranchMatch) -> Unit) {
        val m = checkNotNull(list.selectedValue) { "a button is enabled with no match picked" }
        close()
        action(m)
    }

    private fun open(cwd: Path, id: String) = ClaudeSessionOpener.startNew(project, dir, cwd, id)

    private fun create(title: String, make: (id: String) -> Path) {
        val id = UUID.randomUUID().toString()
        background(project, title, { make(id) }) { path -> open(path, id) }
    }
}
