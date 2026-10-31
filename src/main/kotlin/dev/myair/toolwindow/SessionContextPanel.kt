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
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.myair.actions.FileChange.Kind
import dev.myair.actions.Git
import dev.myair.actions.GitState
import dev.myair.actions.background
import dev.myair.actions.BaseChoices
import dev.myair.actions.basePopup
import dev.myair.actions.openInIdea
import dev.myair.actions.openInTerminal
import dev.myair.actions.showBaseDiff
import dev.myair.claude.BackgroundTask
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.service.ClaudeSessionsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import kotlin.io.path.exists

/**
 * The environment, brief, files, outputs, artifacts, background tasks and git changes of the session in the selected
 * session tab of [project]'s window. Follows tab selection and every service change, which also carries a tab's switch
 * to another session (`/clear`); a selected file that is no session tab keeps the session shown while its tab stays
 * open, so an output opened from here does not empty the pane.
 */
class SessionContextPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Scrollable {
    private val service = ClaudeSessionsService.getInstance()
    private var shownTab: ClaudeSessionVirtualFile? = null
    /** The session the pane shows and the git state last read for it. */
    private var shownId: String? = null
    private var git: Result<GitState?>? = null
    /** The branch checked out in each added directory, null outside a repository, read with [git]. */
    private var addedBranches: Map<Path, Result<String?>> = emptyMap()
    /** The session and its live entry [git] was read for: notifications about other sessions read nothing again. */
    private var readFor: Any? = null
    /** Counts reads, so only the latest one lands. */
    private var reads = 0
    private val gitReader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Session context git", 1)
    private val expanded = HashMap<String, Boolean>()
    /** The lists showing all their rows, by section; the others show [FILES_SHOWN]. */
    private val fullLists = HashSet<String>()

    init {
        border = JBUI.Borders.empty(CARD_GAP)
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = update()
        })
        service.subscribe(parent, ::update)
        PaneFont.subscribe(parent, ::render)
        // A hidden pane reads nothing; it catches up when shown.
        addHierarchyListener { if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) update() }
        update()
    }

    // The pane takes the width of its scroll pane, never scrolling sideways: long rows give way through [shrinkable] cells.
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width

    /** Renders at once with what is known, then again when `git` has read the session's folder. */
    private fun update() {
        val editors = FileEditorManager.getInstance(project)
        val tab = editors.selectedFiles.firstOrNull() as? ClaudeSessionVirtualFile ?: shownTab?.takeIf { editors.isFileOpen(it) }
        val id = tab?.sessionId
        if (id != shownId) {
            git = null
            addedBranches = emptyMap()
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
            val branches = session.addedDirs.associateWith { runCatching { if (it.exists()) Git.branchOf(it) else null } }
            ApplicationManager.getApplication().invokeLater({
                if (read == reads) {
                    git = state
                    addedBranches = branches
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
            else -> content(
                contextOf(session, session.cwd.exists(), session.id in service.live, git?.getOrNull()),
                service.live[session.id], session.id in service.unread,
            )
        }
        PaneFont.growAll(content)
        removeAll()
        add(content, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    /** One card per section, the session itself first, as artboard I of the panel's design lays them out. */
    private fun content(context: SessionContext, live: LiveSession?, unread: Boolean) = JPanel(VerticalLayout(JBUI.scale(CARD_GAP))).apply {
        isOpaque = false
        add(ContextCard(header(context, live, unread)))
        card("Environment") {
            row {
                icon(AllIcons.Nodes.Folder)
                label(context.project)
                comment(context.projectPath.toString()).shrinkable()
            }
            context.git?.let { git ->
                // The folder the session runs in, named after the branch it has checked out, when it is not the project's.
                row {
                    icon(AllIcons.Vcs.Branch)
                    label(git.branch)
                    if (context.worktree) {
                        comment(fileLabel(context.cwd, context.projectPath)).applyToComponent { toolTipText = context.cwd.toString() }.shrinkable()
                        comment("Worktree").align(AlignX.RIGHT)
                    }
                }
                git.base?.let { base ->
                    indent {
                        row {
                            // A detached HEAD has no branch to store a base for.
                            if (git.detached) comment("base: $base").shrinkable()
                            else link("base: $base" + if (git.baseStored) "" else " (default)") { pickBase(git, it.source as JComponent) }
                                .applyToComponent { toolTipText = "The branch the changes are counted against" }.shrinkable()
                        }
                    }
                }
            }
            if (context.desktop) row { comment("Runs in Claude Desktop") }
            if (!context.cwdExists) row { comment("Folder no longer exists") }
            for (dir in context.addedDirs) {
                val branch = addedBranches[dir]
                row {
                    val name = branch?.getOrNull()
                    icon(if (name == null) AllIcons.Nodes.Folder else AllIcons.Vcs.Branch)
                    name?.let { label(it) }
                    comment(dir.toString()).shrinkable()
                }
                branch?.exceptionOrNull()?.let { row { comment(it.message.orEmpty()) } }
            }
            if (context.cwdExists) row {
                link("Open in IDEA") { openInIdea(project, context.cwd) }
                link("Open in Terminal") { openInTerminal(project, context.cwd) }
            }
        }
        val failure = git?.exceptionOrNull()
        if (failure != null) card("Changes vs base") { row { comment(failure.message.orEmpty()) } }
        else context.git?.let { git ->
            card("Changes vs base") {
                val changes = git.changes
                if (changes == null) row { comment(checkNotNull(git.problem)) }
                else {
                    row {
                        label("+${git.added}").applyToComponent { foreground = FileStatus.ADDED.color }
                        label("−${git.removed}").applyToComponent { foreground = FileStatus.DELETED.color }
                        if (changes.isNotEmpty()) link("Show diff") { showBaseDiff(project, git, 0) }
                            .applyToComponent { toolTipText = git.base?.let { "Changes against $it" } }
                            .align(AlignX.RIGHT)
                    }
                    if (git.added + git.removed > 0) row { cell(ChangeBar(git.added, git.removed)).align(AlignX.FILL) }
                    paged("Changes", changes) { change, index ->
                        label(KIND_LETTERS.getValue(change.kind)).applyToComponent { foreground = KIND_STATUSES.getValue(change.kind).color }
                        link(change.path) { showBaseDiff(project, git, index) }.shrinkable()
                        comment("+${change.added} −${change.removed}")
                    }
                }
            }
        }
        if (context.files.isNotEmpty()) card(if (context.git == null) "Files" else "Files outside the repo") {
            files("Files", context.files) { fileLabel(it, context.cwd) }
        }
        if (context.outputs.isNotEmpty()) card("Outputs") { files("Outputs", context.outputs) { it.fileName.toString() } }
        if (context.artifacts.isNotEmpty()) card("Artifacts") {
            for (artifact in context.artifacts) row {
                icon(AllIcons.General.Web)
                link(artifact.title) { BrowserUtil.browse(artifact.url) }.applyToComponent { toolTipText = artifact.url }.shrinkable()
            }
        }
        context.brief?.let { brief ->
            card("Brief") {
                brief.goal?.let { goal -> row { text(goal, MAX_LINE_LENGTH_WORD_WRAP) } }
                for (task in brief.tasks) row {
                    when (task.status) {
                        TaskStatus.COMPLETED -> comment(task.line(), MAX_LINE_LENGTH_WORD_WRAP)
                        TaskStatus.IN_PROGRESS -> text(task.line(), MAX_LINE_LENGTH_WORD_WRAP).bold()
                        TaskStatus.PENDING -> text(task.line(), MAX_LINE_LENGTH_WORD_WRAP)
                    }
                }
            }
        }
        if (context.background.isNotEmpty()) card("Background") {
            paged("Background", context.background) { task, _ ->
                icon(BACKGROUND_ICONS.getValue(task.kind))
                val output = task.output
                if (output == null) label(task.description).shrinkable()
                else link(task.description) { openFile(project, output) }.applyToComponent { toolTipText = output.toString() }.shrinkable()
                comment(backgroundState(task, context.live, ZoneId.systemDefault())).applyToComponent { toolTipText = task.end?.summary }
            }
        }
    }

    /** The session: what it is doing and since when, its title, the model and effort of its latest reply. */
    private fun header(context: SessionContext, live: LiveSession?, unread: Boolean) = panel {
        row {
            icon(dotOf(live, unread))
            label(statusText(live, unread) ?: "No process").applyToComponent {
                foreground = if (live?.status == SessionStatus.NEEDS_INPUT) NEEDS_INPUT_TEXT else JBUI.CurrentTheme.Label.disabledForeground()
            }
            comment(shortAge(context.lastMessage, Instant.now())).align(AlignX.RIGHT)
        }
        row { text(context.title, MAX_LINE_LENGTH_WORD_WRAP).applyToComponent { font = JBFont.label().biggerOn(2f).asBold() } }
        val chips = listOfNotNull(context.model?.let(::modelName), context.effort)
        if (chips.isNotEmpty()) row { chips.forEach { cell(Chip(it)) } }
    }.apply { isOpaque = false }

    /** A card holding a section that stays collapsed or expanded across renders. */
    private fun JPanel.card(title: String, init: Panel.() -> Unit) {
        add(ContextCard(panel { section(title, init) }.apply { isOpaque = false }))
    }

    /**
     * Asks under [anchor] for a new base of [git]'s branch among the branches it contains ([Git.containedBases]) and
     * reads the changes again against it. The point is taken at the click: the pane may be rebuilt, and [anchor] gone,
     * while the branches are read.
     */
    private fun pickBase(git: GitState, anchor: JComponent) {
        val under = RelativePoint.fromScreen(Point(anchor.locationOnScreen).apply { y += anchor.height })
        background(project, "Reading branches", { Git.containedBases(git.root, git.branch) }) { bases ->
            if (bases.isEmpty()) return@background Messages.showInfoMessage(project, "${git.branch} contains no other branch.", "Base Branch")
            // No separators: getSeparatorAbove never meets an index past the last item.
            val choices = BaseChoices(bases, bases.size, bases.size, bases.indexOf(git.base).coerceAtLeast(0))
            basePopup("Base Branch of ${git.branch}", choices) { base ->
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
        link(label(file)) { openFile(project, file) }.applyToComponent { toolTipText = file.toString() }.shrinkable()
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
        private const val CARD_GAP = 4
        private val NEEDS_INPUT_TEXT = JBColor(0xB5520E, 0xF0883E)
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
