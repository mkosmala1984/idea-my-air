package dev.myair.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.myair.actions.FileChange.Kind
import dev.myair.actions.Git
import dev.myair.actions.GitState
import dev.myair.actions.background
import dev.myair.actions.baseChoices
import dev.myair.actions.basePopup
import dev.myair.actions.recentBases
import dev.myair.actions.showBaseDiff
import dev.myair.claude.BackgroundTask
import dev.myair.claude.TaskStatus
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.service.ClaudeSessionsService
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.io.path.exists

/**
 * The environment, brief, files, outputs, artifacts, background tasks and git changes of the session in the selected
 * session tab of [project]'s window. Follows tab selection and every service change, which also carries a tab's switch
 * to another session (`/clear`); a selected file that is no session tab keeps the session shown while its tab stays
 * open, so an output opened from here does not empty the pane.
 */
class SessionContextPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = ClaudeSessionsService.getInstance()
    private var shownTab: ClaudeSessionVirtualFile? = null
    /** The session the pane shows and the git state last read for it. */
    private var shownId: String? = null
    private var git: Result<GitState?>? = null
    /** The session and its live entry [git] was read for: notifications about other sessions read nothing again. */
    private var readFor: Any? = null
    /** Counts reads, so only the latest one lands. */
    private var reads = 0
    private val gitReader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Session context git", 1)
    private val expanded = HashMap<String, Boolean>()
    /** The file lists showing all their rows, by section; the others show [FILES_SHOWN]. */
    private val fullLists = HashSet<String>()

    init {
        border = JBUI.Borders.empty(4, 8)
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = update()
        })
        service.subscribe(parent, ::update)
        // A hidden pane reads nothing; it catches up when shown.
        addHierarchyListener { if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) update() }
        update()
    }

    /** Renders at once with what is known, then again when `git` has read the session's folder. */
    private fun update() {
        val editors = FileEditorManager.getInstance(project)
        val tab = editors.selectedFiles.firstOrNull() as? ClaudeSessionVirtualFile ?: shownTab?.takeIf { editors.isFileOpen(it) }
        val id = tab?.sessionId
        if (id != shownId) {
            git = null
            readFor = null
            fullLists.clear()
        }
        shownTab = tab
        shownId = id
        render()
        val session = service.sessions.firstOrNull { it.id == id } ?: return
        val key = session to service.live[session.id]
        if (!isShowing || key == readFor || !session.cwd.exists()) return
        readFor = key
        val read = ++reads
        gitReader.execute {
            val state = runCatching { Git.state(session.cwd) }
            ApplicationManager.getApplication().invokeLater({
                if (read == reads) {
                    git = state
                    render()
                }
            }, ModalityState.any())
        }
    }

    private fun render() {
        val id = shownId
        val session = service.sessions.firstOrNull { it.id == id }
        val content = when {
            id == null -> panel { row { comment("No session tab selected") } }
            session == null -> panel { row { comment("Starting...") } }
            else -> content(contextOf(session, session.cwd.exists(), session.id in service.live, git?.getOrNull()))
        }
        removeAll()
        add(content, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    private fun content(context: SessionContext) = panel {
        section("Environment") {
            row {
                icon(AllIcons.Nodes.Folder)
                label(context.project)
                comment(context.projectPath.toString())
            }
            context.git?.let { git ->
                row {
                    icon(AllIcons.Vcs.Branch)
                    label(git.branch)
                    git.base?.let { base ->
                        // A detached HEAD has no branch to store a base for.
                        if (git.detached) comment("← $base")
                        else link("← $base" + if (git.baseStored) "" else " (default)") { pickBase(git, it.source as JComponent) }
                            .applyToComponent { toolTipText = "The branch the changes are counted against" }
                    }
                    if (context.worktree) comment("Worktree").align(AlignX.RIGHT)
                }
            }
            if (context.desktop) row { comment("Runs in Claude Desktop") }
            if (!context.cwdExists) row { comment("Folder no longer exists") }
            for (dir in context.addedDirs) row {
                icon(AllIcons.Nodes.Folder)
                comment(dir.toString())
            }
        }
        context.brief?.let { brief ->
            section("Brief") {
                brief.goal?.let { goal ->
                    row {
                        icon(AllIcons.Actions.IntentionBulb)
                        text(goal)
                    }
                }
                for (task in brief.tasks) row {
                    when (task.status) {
                        TaskStatus.COMPLETED -> comment(task.line())
                        TaskStatus.IN_PROGRESS -> label(task.line()).bold()
                        TaskStatus.PENDING -> label(task.line())
                    }
                }
            }
        }
        if (context.outputs.isNotEmpty()) section("Outputs") { files("Outputs", context.outputs) { it.fileName.toString() } }
        if (context.files.isNotEmpty()) section("Files") { files("Files", context.files) { fileLabel(it, context.cwd) } }
        if (context.artifacts.isNotEmpty()) section("Artifacts") {
            for (artifact in context.artifacts) row {
                icon(AllIcons.General.Web)
                link(artifact.title) { BrowserUtil.browse(artifact.url) }.applyToComponent { toolTipText = artifact.url }
            }
        }
        if (context.background.isNotEmpty()) section("Background") {
            for (task in context.background) row {
                icon(BACKGROUND_ICONS.getValue(task.kind))
                label(task.description)
            }
        }
        val failure = git?.exceptionOrNull()
        if (failure != null) section("Changes") { row { comment(failure.message.orEmpty()) } }
        else context.git?.let { git ->
            section("Changes") {
                val changes = git.changes
                if (changes == null) row { comment(checkNotNull(git.problem)) }
                else {
                    row {
                        icon(AllIcons.Actions.Diff)
                        label("+${git.added}").applyToComponent { foreground = FileStatus.ADDED.color }
                        label("−${git.removed}").applyToComponent { foreground = FileStatus.DELETED.color }
                        if (changes.isNotEmpty()) link("Show diff") { showBaseDiff(project, git, 0) }
                            .applyToComponent { toolTipText = git.base?.let { "Changes against $it" } }
                    }
                    paged("Changes", changes) { change, index ->
                        label(KIND_LETTERS.getValue(change.kind)).applyToComponent { foreground = KIND_STATUSES.getValue(change.kind).color }
                        link(change.path) { showBaseDiff(project, git, index) }
                        comment("+${change.added} −${change.removed}")
                    }
                }
            }
        }
    }

    /**
     * Asks for a new base of [git]'s branch under [anchor] and reads the changes again against it. The point is taken at
     * the click: the pane may be rebuilt, and [anchor] gone, while the branches are read.
     */
    private fun pickBase(git: GitState, anchor: JComponent) {
        val under = RelativePoint.fromScreen(Point(anchor.locationOnScreen).apply { y += anchor.height })
        background(project, "Reading branches", { Git.repo(git.root) }) { repo ->
            basePopup("Base Branch of ${git.branch}", baseChoices(repo.branches, repo.default, recentBases(repo.mainRoot))) { base ->
                background(project, "Setting base branch", { Git.setBase(git.root, git.branch, base) }) {
                    readFor = null
                    update()
                }
            }.show(under)
        }
    }

    /** A row per file of [files], named by [label], opening it in the editor. */
    private fun Panel.files(key: String, files: List<Path>, label: (Path) -> String) = paged(key, files) { file, _ ->
        icon(FileTypeManager.getInstance().getFileTypeByFileName(file.fileName.toString()).icon ?: AllIcons.FileTypes.Any_type)
        link(label(file)) { openFile(project, file) }.applyToComponent { toolTipText = file.toString() }
    }

    /** A row per item of [items], filled by [fill] with the item and its index; [FILES_SHOWN] of them until Show more, kept by [key]. */
    private fun <T> Panel.paged(key: String, items: List<T>, fill: Row.(T, Int) -> Unit) {
        val all = key in fullLists
        for ((index, item) in (if (all) items else items.take(FILES_SHOWN)).withIndex()) row { fill(item, index) }
        if (items.size > FILES_SHOWN) row {
            link(if (all) "Show less" else "Show more (${items.size})") {
                if (all) fullLists -= key else fullLists += key
                render()
            }
        }
    }

    /** A collapsible group that stays collapsed or expanded across renders. */
    private fun Panel.section(title: String, init: Panel.() -> Unit) = collapsibleGroup(title, init = init).apply {
        expanded = this@SessionContextPanel.expanded[title] ?: true
        addExpandedListener { this@SessionContextPanel.expanded[title] = it }
    }

    companion object {
        private const val FILES_SHOWN = 5
        private val KIND_LETTERS = mapOf(Kind.ADDED to "A", Kind.MODIFIED to "M", Kind.DELETED to "D", Kind.UNTRACKED to "U")
        private val KIND_STATUSES = mapOf(
            Kind.ADDED to FileStatus.ADDED, Kind.MODIFIED to FileStatus.MODIFIED, Kind.DELETED to FileStatus.DELETED, Kind.UNTRACKED to FileStatus.UNKNOWN,
        )
        private val BACKGROUND_ICONS = mapOf(
            BackgroundTask.Kind.SHELL to AllIcons.Nodes.Console, BackgroundTask.Kind.MONITOR to AllIcons.Actions.Show,
            BackgroundTask.Kind.AGENT to AllIcons.Actions.Execute, BackgroundTask.Kind.WORKFLOW to AllIcons.Actions.RunAll,
        )
    }
}

/** Opens [path] in an editor of [project], or says it is gone. */
fun openFile(project: Project, path: Path) {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    if (file == null) Messages.showErrorDialog(project, "File no longer exists:\n$path", "Cannot Open File")
    else FileEditorManager.getInstance(project).openFile(file, true)
}
