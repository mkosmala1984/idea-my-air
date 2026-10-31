package dev.myair.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.InplaceButton
import dev.myair.editor.ClaudeSessionEditor
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
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
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
import dev.myair.actions.plannotator
import dev.myair.actions.reviewInPlannotator
import dev.myair.actions.showBaseChangesTree
import dev.myair.actions.showCombinedBaseDiff
import dev.myair.actions.status
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.treeStructure.Tree
import dev.myair.claude.BackgroundTask
import dev.myair.claude.SkillFiles
import dev.myair.claude.TaskStatus
import dev.myair.claude.skillFiles
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.io.path.name
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.service.ClaudeSessionsService
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import java.awt.BorderLayout
import java.awt.Cursor
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
 * The environment, brief, files, outputs, artifacts, background tasks, MCP servers, skills and git changes of the session in the selected
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
    /** The folder the session works in, as [contextOf] takes it, read with [git]: null until read. */
    private var workTree: Path? = null
    /** The branch checked out in each added directory, null outside a repository, read with [git]. */
    private var addedBranches: Map<Path, Result<String?>> = emptyMap()
    /** The session and its live entry [git] was read for: notifications about other sessions read nothing again. */
    private var readFor: Any? = null
    /** Counts reads, so only the latest one lands. */
    private var reads = 0
    private val gitReader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Session context git", 1)
    /** The lists showing all their rows, by section; the others show [FILES_SHOWN]. */
    private val fullLists = HashSet<String>()
    /** The opened skills by the session's folder and name, with their files once read (null while reading). */
    private val skills = HashMap<Pair<Path, String>, Result<SkillFiles?>?>()
    /** The folders opened in each skill's tree. */
    private val skillFolders = HashMap<Pair<Path, String>, MutableSet<Path>>()
    /** What the pane was last built from, by [render]. */
    private var rendered: Any? = null

    init {
        border = JBUI.Borders.empty(CARD_GAP)
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = update()
        })
        service.subscribe(parent, ::update)
        PaneFont.subscribe(parent) { render(force = true) }
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
            workTree = null
            addedBranches = emptyMap()
            readFor = null
            fullLists.clear()
        }
        shownTab = tab
        shownId = id
        render()
        val session = service.sessions.firstOrNull { it.id == id } ?: return
        val key = session to service.live[session.id]
        if (!isShowing || key == readFor || !session.workDir.exists()) return
        readFor = key
        val read = ++reads
        gitReader.execute {
            val state = runCatching { Git.state(session.workDir) }
            val tree = state.getOrNull()?.let { workTreeOf(session.cwd, session.workDir, it.root) } ?: session.cwd
            val branches = session.addedDirs.associateWith { runCatching { if (it.exists()) Git.branchOf(it) else null } }
            ApplicationManager.getApplication().invokeLater({
                if (read == reads) {
                    git = state
                    workTree = tree
                    addedBranches = branches
                    render()
                }
            }, ModalityState.any())
        }
    }

    /**
     * Builds the pane anew, unless all it shows is as last built ([force]: the font changed): the service notifies about
     * every session, and a rebuild loses the scroll position of whatever the pane holds.
     */
    private fun render(force: Boolean = false) {
        val id = shownId
        val session = service.sessions.firstOrNull { it.id == id }
        val context = session?.let { contextOf(it, it.workDir.exists(), it.id in service.live, git?.getOrNull(), workTree ?: it.cwd) }
        val shown = listOf(
            id, context, context?.let { shortAge(it.lastMessage, Instant.now()) }, git, addedBranches, fullLists.toSet(), skills.toMap(),
            pinnedSkills(),
        )
        if (!force && shown == rendered) return
        rendered = shown
        val content = when {
            id == null -> panel { row { comment("No session tab selected") } }
            context == null -> panel { row { comment("Starting...") } }
            else -> content(context)
        }
        PaneFont.growAll(content)
        removeAll()
        add(content, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    /** One card per section, the session itself first, as artboard I of the panel's design lays them out. */
    private fun content(context: SessionContext) = JPanel(VerticalLayout(JBUI.scale(CARD_GAP))).apply {
        isOpaque = false
        add(ContextCard(header(context)))
        card("Environment") {
            // The project, the folder the session runs in when it is another one (a worktree as a rule), and opening that folder.
            row {
                icon(AllIcons.Nodes.Folder)
                label(context.project).applyToComponent { toolTipText = context.projectPath.toString() }
                if (context.worktree) comment(fileLabel(context.cwd, context.projectPath)).applyToComponent { toolTipText = context.cwd.toString() }.shrinkable()
                else comment(context.projectPath.toString()).shrinkable()
                if (context.cwdExists) {
                    link("IDEA") { openInIdea(project, context.cwd) }.applyToComponent { toolTipText = "Open in IDEA" }
                    link("Terminal") { openInTerminal(project, context.cwd) }.applyToComponent { toolTipText = "Open in Terminal" }
                }
            }
            context.git?.let { git ->
                // The branch of that folder and the base its changes are counted against; the base gives way first.
                row {
                    icon(AllIcons.Vcs.Branch)
                    val base = git.base?.let { base ->
                        // A detached HEAD has no branch to store a base for.
                        if (git.detached) commentLabel(base)
                        else ActionLink(base + if (git.baseStored) "" else " (default)") { pickBase(git, it.source as JComponent) }
                            .apply { toolTipText = "The base branch the changes are counted against" }
                    }
                    cell(ShrinkingLine(listOfNotNull(JBLabel(git.branch), base?.let { commentLabel(" → ") }, base))).shrinkable()
                }
            }
            if (context.startedIn != null || context.startBranch != null) row {
                // Where it was before EnterWorktree or a checkout moved it.
                val where = context.startedIn?.let { " in " + if (it == context.projectPath) context.project else fileLabel(it, context.projectPath) }
                comment("Started" + where.orEmpty() + context.startBranch?.let { " on $it" }.orEmpty())
                    .applyToComponent { toolTipText = context.startedIn?.toString() }.shrinkable()
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
                        if (changes.isNotEmpty()) {
                            link("Show diff") { showCombinedBaseDiff(project, git) }
                                .applyToComponent { toolTipText = "All files in one tab, against ${git.base}" }
                                .resizableColumn().align(AlignX.RIGHT)
                            link("Tree") { showBaseChangesTree(project, git) }
                                .applyToComponent { toolTipText = "The changed files as a tree of folders, against ${git.base}" }
                            if (plannotator != null) link("Plannotator") { reviewInPlannotator(project, git) }
                                .applyToComponent { toolTipText = "Review all changes against ${git.base} in Plannotator, in the browser" }
                        }
                    }
                    if (git.added + git.removed > 0) row { cell(ChangeBar(git.added, git.removed)).align(AlignX.FILL) }
                    paged("Changes", changes) { change, index ->
                        label(KIND_LETTERS.getValue(change.kind)).applyToComponent { foreground = change.kind.status.color }
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
        if (context.mcpServers.isNotEmpty()) card("MCP servers") {
            paged("MCP servers", context.mcpServers) { server, _ -> icon(AllIcons.Nodes.Plugin); label(server).shrinkable() }
        }
        if (context.skills.isNotEmpty()) card("Skills", indent = false) {
            val (pinned, groups) = skillSections(context.skills, pinnedSkills())
            for (skill in pinned) skill(context.cwd to skill, pinned = true)
            for ((plugin, skills) in groups) {
                section("$plugin (${skills.size})", key = "Skills/$plugin", expandedFirst = false, indent = false) {
                    for (skill in skills) skill(context.cwd to skill, pinned = false)
                }
            }
        }
    }

    /**
     * The row of a skill, with buttons pasting its command into the session and pinning it; once opened its description
     * and files. A pinned skill stands outside its plugin's group, so it is named in full.
     */
    private fun Panel.skill(key: Pair<Path, String>, pinned: Boolean) {
        val open = key in skills
        val name = key.second
        row {
            icon(if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight)
            link(if (pinned) name else name.substringAfterLast(':')) { toggleSkill(key) }.shrinkable()
            cell(InplaceButton("Paste /$name into the session", AllIcons.Actions.MenuPaste) { pasteCommand("/$name ") })
            cell(InplaceButton(if (pinned) "Unpin skill" else "Pin skill above the groups", if (pinned) AllIcons.General.PinSelected else AllIcons.General.Pin_tab) {
                setPinnedSkills(if (pinned) pinnedSkills() - name else pinnedSkills() + name)
                render()
            })
        }
        if (!open) return
        val read = skills[key]
        val files = read?.getOrNull()
        // The top-level builder: inside a Panel, `panel` would add a nested one to this panel's grid.
        val details = com.intellij.ui.dsl.builder.panel {
            when {
                read == null -> row { comment("Reading...") }
                read.isFailure -> row { comment(read.exceptionOrNull()?.message.orEmpty()) }
                files == null -> row { comment("Built into claude, no files") }
                else -> skillFiles(key, files)
            }
        }.apply { isOpaque = false }
        // In a plain panel: the grid of a card makes room for what its cells paint past their bounds, and the
        // description and the tree made it move every row of the card to the right.
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(details, BorderLayout.CENTER)
        row { cell(wrapper).align(AlignX.FILL) }
    }

    /** A skill's description and its files: a command's one file, a skill's directory as a tree. */
    private fun Panel.skillFiles(key: Pair<Path, String>, files: SkillFiles) {
        files.description.fold(
            { description -> row { if (description == null) comment("No description") else text(description, MAX_LINE_LENGTH_WORD_WRAP) } },
            { row { comment("Invalid frontmatter: ${it.message}") } },
        )
        files.origin?.let { origin ->
            row {
                comment(listOfNotNull(origin.source, origin.installed).joinToString(" ")).shrinkable()
                updateState(origin, ZoneId.systemDefault())?.let { state ->
                    if (origin.latest == origin.installed) comment(state) else label(state).bold()
                }
                origin.url?.let { url -> link("GitHub") { BrowserUtil.browse(url) }.applyToComponent { toolTipText = url } }
            }
        }
        row {
            if (files.files.isEmpty()) {
                icon(fileIcon(files.definition))
                link(files.definition.name) { openFile(project, files.definition) }.applyToComponent { toolTipText = files.definition.toString() }
            } else cell(skillTree(key, files)).align(AlignX.FILL)
        }
    }

    /** Selects the shown session's tab and types [command] into its prompt, to be completed and sent by hand. */
    private fun pasteCommand(command: String) {
        val tab = checkNotNull(shownTab) { "skills shown without a session tab" }
        val editor = FileEditorManager.getInstance(project).openFile(tab, true).filterIsInstance<ClaudeSessionEditor>().single()
        if (!editor.type(command)) Messages.showInfoMessage(project, "The session's tab does not run claude.", "Paste Skill Command")
    }

    /** Opens or closes the skill [key], reading its files off the EDT. */
    private fun toggleSkill(key: Pair<Path, String>) {
        if (key in skills) skills -= key
        else {
            skills[key] = null
            background(project, "Reading skill", { runCatching { skillFiles(key.second, CLAUDE_HOME, key.first) } }) {
                if (key in skills) skills[key] = it
                render()
            }
        }
        render()
    }

    /** The files under a skill's directory as a tree, folders first; a click on a file opens it, opened folders stay open across renders. */
    private fun skillTree(key: Pair<Path, String>, files: SkillFiles): Tree {
        val nodes = HashMap<Path, DefaultMutableTreeNode>()
        val root = DefaultMutableTreeNode(files.definition).also { nodes[files.definition] = it }
        for (path in files.files) nodes.getValue(path.parent).add(DefaultMutableTreeNode(path).also { nodes[path] = it })
        for (node in nodes.values) {
            val children = node.children().toList().map { it as DefaultMutableTreeNode }
                .sortedWith(compareBy({ it.isLeaf }, { (it.userObject as Path).name.lowercase() }))
            node.removeAllChildren()
            children.forEach(node::add)
        }
        val opened = skillFolders.getOrPut(key) { HashSet() }
        return Tree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = true
            isOpaque = false
            cellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                    val path = (value as DefaultMutableTreeNode).userObject as Path
                    icon = if (leaf) fileIcon(path) else AllIcons.Nodes.Folder
                    append(path.name)
                }
            }
            for (folder in opened.sortedBy { it.nameCount }) nodes[folder]?.let { expandPath(TreePath(it.path)) }
            addTreeExpansionListener(object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) { opened.add(folderOf(event)) }
                override fun treeCollapsed(event: TreeExpansionEvent) { opened.remove(folderOf(event)) }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    val node = getPathForLocation(event.x, event.y)?.lastPathComponent as? DefaultMutableTreeNode ?: return
                    if (node.isLeaf) openFile(project, node.userObject as Path)
                }
            })
        }
    }

    private fun folderOf(event: TreeExpansionEvent) = (event.path.lastPathComponent as DefaultMutableTreeNode).userObject as Path

    /** The session: its title, opening its tab on a click, the model and effort of its latest reply, and how long ago its last message came. */
    private fun header(context: SessionContext) = panel {
        row {
            text(context.title, MAX_LINE_LENGTH_WORD_WRAP).applyToComponent {
                font = JBFont.label().biggerOn(2f).asBold()
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open session tab"
                // The tab is read at the click: the pane is not rebuilt when only the tab showing the session changes.
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        FileEditorManager.getInstance(project).openFile(checkNotNull(shownTab), true)
                    }
                })
            }.customize(UnscaledGaps(bottom = 6)) // The text cell's default gaps without the top one, flush with the card's top.
        }
        row {
            listOfNotNull(context.model?.let(::modelName), context.effort).forEach { cell(Chip(it)) }
            comment(shortAge(context.lastMessage, Instant.now())).align(AlignX.RIGHT)
        }
    }.apply { isOpaque = false }

    /** A card holding a section that stays collapsed or expanded across renders. */
    private fun JPanel.card(title: String, indent: Boolean = true, init: Panel.() -> Unit) {
        add(ContextCard(panel { section(title, indent = indent, init = init) }.apply { isOpaque = false }))
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
        icon(fileIcon(file))
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

    /**
     * A collapsible group that stays collapsed or expanded across renders, sessions, windows and restarts, kept by [key]
     * in application properties; [expandedFirst] until toggled.
     */
    private fun Panel.section(title: String, key: String = title, expandedFirst: Boolean = true, indent: Boolean = true, init: Panel.() -> Unit) =
        collapsibleGroup(title, indent, init).apply {
            expanded = sectionExpanded(sectionStates(), key) ?: expandedFirst
            addExpandedListener { setSectionStates(withSection(sectionStates(), key, it)) }
        }

    companion object {
        private val CLAUDE_HOME = Path.of(System.getProperty("user.home"), ".claude")
        /** The skills pinned in every session's pane of this IDE, by full name. */
        private const val PINNED_SKILLS = "dev.myair.context.pinnedSkills"
        private fun pinnedSkills(): Set<String> = PropertiesComponent.getInstance().getList(PINNED_SKILLS).orEmpty().toSet()
        private fun setPinnedSkills(names: Set<String>) = PropertiesComponent.getInstance().setList(PINNED_SKILLS, names.sorted())
        private const val SECTIONS = "dev.myair.context.sections"
        private fun sectionStates(): List<String> = PropertiesComponent.getInstance().getList(SECTIONS).orEmpty()
        private fun setSectionStates(states: List<String>) = PropertiesComponent.getInstance().setList(SECTIONS, states)
        private const val FILES_SHOWN = 5
        private const val CARD_GAP = 4
        private val KIND_LETTERS = mapOf(Kind.ADDED to "A", Kind.MODIFIED to "M", Kind.DELETED to "D", Kind.UNTRACKED to "U")
        private val BACKGROUND_ICONS = mapOf(
            BackgroundTask.Kind.SHELL to AllIcons.Nodes.Console, BackgroundTask.Kind.MONITOR to AllIcons.Actions.Show,
            BackgroundTask.Kind.AGENT to AllIcons.Actions.Execute, BackgroundTask.Kind.WORKFLOW to AllIcons.Actions.RunAll,
        )
    }
}

/** Opens [path] in an editor of [project], or says it is gone. */
fun fileIcon(path: Path): Icon = FileTypeManager.getInstance().getFileTypeByFileName(path.name).icon ?: AllIcons.FileTypes.Any_type

fun openFile(project: Project, path: Path) {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    if (file == null) Messages.showErrorDialog(project, "File no longer exists:\n$path", "Cannot Open File")
    else FileEditorManager.getInstance(project).openFile(file, true)
}
