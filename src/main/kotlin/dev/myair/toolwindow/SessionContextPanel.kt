package dev.myair.toolwindow

import com.intellij.markdown.utils.convertMarkdownToHtml
import dev.myair.planview.PlanOpener
import dev.myair.store.Homes
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.InplaceButton
import dev.myair.editor.SessionPaneHost
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.sessionIcon
import dev.myair.editor.NEW_SESSION_ICON
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
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
import com.intellij.util.ui.EmptyIcon
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.myair.actions.FileChange.Kind
import dev.myair.actions.FileChange.Stage
import com.intellij.ui.JBColor
import dev.myair.actions.Git
import dev.myair.actions.GitState
import dev.myair.actions.background
import dev.myair.actions.continueSession
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
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import dev.myair.claude.BackgroundTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
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
import dev.myair.service.ActiveSession
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.LatestRead
import dev.myair.planview.tabFileOf
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
 * The environment, brief, files, outputs, artifacts, background tasks, MCP servers, skills and git changes of the session in
 * [ActiveSession]'s tab, as Branch Diff shows it; a selected file that is no session tab keeps the session shown while
 * its tab stays open, so an output opened from here does not empty the pane. Follows that tab and every service change.
 */
class SessionContextPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Scrollable {
    private val service = ClaudeSessionsService.getInstance()
    // Before the panel subscribes to the service, so the active tab is current when the panel's own listener runs.
    private val active = ActiveSession.getInstance(project)
    /** The session the pane shows and what git last said of it. */
    private var shownId: String? = null
    private var git: GitRead? = null
    /**
     * Keyed by the session, its live entry and [ActiveSession.baseGeneration] (a base set here or in Branch Diff), so
     * notifications about other sessions read nothing again; on one thread, so reads never pile up.
     */
    private val gitReads = LatestRead.onPool<Triple<ClaudeSession, LiveSession?, Int>, GitRead>(
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Session context git", 1), { ModalityState.any() }, { gitRead(it.first) },
    ) { _, read ->
        git = read.getOrThrow()
        render()
    }
    /** The lists showing all their rows, by section; the others show [FILES_SHOWN]. */
    private val fullLists = HashSet<String>()
    /** The folders of the changes tree opened or closed by hand, by path relative to the work tree; the others as [openByDefault]. */
    private val changeFolders = HashMap<String, Boolean>()
    /** The opened skills by the session's folder and name, with their files once read (null while reading). */
    private val skills = HashMap<Pair<Path, String>, Result<SkillFiles?>?>()
    /** The folders opened in each skill's tree. */
    private val skillFolders = HashMap<Pair<Path, String>, MutableSet<Path>>()
    /** What the pane was last built from, by [render]. */
    private var rendered: Any? = null

    init {
        border = JBUI.Borders.empty(CARD_GAP)
        active.subscribe(parent, ::update)
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
        val id = active.tab?.sessionId
        if (id != shownId) {
            git = null
            gitReads.forget()
            fullLists.clear()
            changeFolders.clear()
        }
        shownId = id
        render()
        val session = service.sessions.firstOrNull { it.id == id } ?: return
        val key = Triple(session, service.live[session.id], active.baseGeneration)
        if (isShowing && key != gitReads.asked && session.workDir.exists()) gitReads.request(key)
    }

    /**
     * Builds the pane anew, unless all it shows is as last built ([force]: the font changed): the service notifies about
     * every session, and a rebuild loses the scroll position of whatever the pane holds.
     */
    private fun render(force: Boolean = false) {
        val id = shownId
        val session = service.sessions.firstOrNull { it.id == id }
        val context = session?.let { contextOf(it, it.workDir.exists(), it.id in service.live, git) }
        val plan = session?.let { planContextOf(it, service.folders, service.sessions) }
        val shown = listOf(id, context, plan, context?.let { shortAge(it.lastMessage, Instant.now()) }, fullLists.toSet(), skills.toMap(), pinnedSkills(), changesAsTree(), changesFilter())
        if (!force && shown == rendered) return
        rendered = shown
        val content = when {
            id == null -> panel { row { comment("No session tab selected") } }
            context == null -> panel { row { comment("Starting...") } }
            else -> content(context, plan)
        }
        PaneFont.growAll(content)
        removeAll()
        add(content, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    /** One card per section, the session itself first, as artboard I of the panel's design lays them out. */
    private fun content(context: SessionContext, plan: PlanContext?) = JPanel(VerticalLayout(JBUI.scale(CARD_GAP))).apply {
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
                row {
                    icon(if (dir.branch == null) AllIcons.Nodes.Folder else AllIcons.Vcs.Branch)
                    dir.branch?.let { label(it) }
                    comment(dir.path.toString()).shrinkable()
                }
                dir.error?.let { row { comment(it) } }
            }
        }
        if (context.gitError != null) card("Changes vs base") { row { comment(context.gitError) } }
        else context.git?.let { git ->
            card("Changes vs base") {
                val changes = git.changes
                val asTree = changesAsTree()
                val filter = changesFilter()
                if (changes == null) row { comment(checkNotNull(git.problem)) }
                else {
                    val shown = changes.withIndex().filter { filter.shows(it.value) }
                    val added = shown.sumOf { it.value.added }
                    val removed = shown.sumOf { it.value.removed }
                    row {
                        label("+$added").applyToComponent { foreground = FileStatus.ADDED.color }
                        label("−$removed").applyToComponent { foreground = FileStatus.DELETED.color }
                        if (changes.isNotEmpty()) {
                            cell(InplaceButton(if (asTree) "Show as list" else "Show as tree", if (asTree) AllIcons.Actions.ListFiles else AllIcons.Actions.ShowAsTree) {
                                PropertiesComponent.getInstance().setValue(CHANGES_AS_TREE, !asTree)
                                render()
                            }).resizableColumn().align(AlignX.RIGHT)
                            link("Show diff") { showCombinedBaseDiff(project, git) }
                                .applyToComponent { toolTipText = "All files in one tab, against ${git.base}" }
                            link("Tree") { showBaseChangesTree(project, git) }
                                .applyToComponent { toolTipText = "The changed files as a tree of folders, against ${git.base}" }
                            if (plannotator != null) link("Plannotator") { reviewInPlannotator(project, git) }
                                .applyToComponent { toolTipText = "Review all changes against ${git.base} in Plannotator, in the browser" }
                        }
                    }
                    // Which changes show, each choice with its count; the chosen one is plain text.
                    if (changes.isNotEmpty()) row {
                        for (choice in ChangesFilter.entries) {
                            val text = "${choice.label} ${changes.count(choice::shows)}"
                            if (choice == filter) label(text).bold()
                            else link(text) {
                                PropertiesComponent.getInstance().setValue(CHANGES_FILTER, choice.name)
                                render()
                            }
                        }
                    }
                    if (added + removed > 0) row { cell(ChangeBar(added, removed)).align(AlignX.FILL) }
                    if (changes.isNotEmpty() && shown.isEmpty()) row { comment("No ${filter.label.lowercase()} changes") }
                    else if (asTree) {
                        if (shown.isNotEmpty()) row { cell(wrapped(changesTree(git, changeTree(changes, filter)))).align(AlignX.FILL) }
                    } else paged("Changes", shown) { (index, change), _ ->
                        row {
                            label(KIND_LETTERS.getValue(change.kind)).applyToComponent { foreground = change.kind.status.color }
                            link(change.path) { showBaseDiff(project, git, index) }.shrinkable()
                            comment("+${change.added} −${change.removed}")
                        }
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
        plan?.let {
            card("Plan") {
                row {
                    val folder = it.folderId
                    link(it.path) { PlanOpener.open(project, folder) }.shrinkable()
                    comment("› ${it.milestone.id} ${it.milestone.title}")
                }
                row {
                    icon(it.task.status.icon)
                    label("${it.task.id} ${it.task.title}").bold().shrinkable()
                    comment(it.task.status.label)
                }
                it.task.next?.let { next -> row { comment("Next: $next", MAX_LINE_LENGTH_WORD_WRAP) } }
                it.task.description?.let { description -> row { text(convertMarkdownToHtml(description), MAX_LINE_LENGTH_WORD_WRAP) } }
                for (other in it.others) row {
                    icon(sessionIcon(service.live[other.id]?.status, other.id in service.unread))
                    link(other.title) { ClaudeSessionOpener.open(project, other) }.shrinkable()
                }
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
                val kindIcon = BACKGROUND_ICONS.getValue(task.kind)
                row {
                    icon(kindIcon)
                    val output = task.output
                    if (output == null) label(task.description).shrinkable()
                    else link(task.description) { openFile(project, output) }.applyToComponent { toolTipText = output.toString() }.shrinkable()
                    comment(backgroundState(task, context.live, ZoneId.systemDefault())).applyToComponent { toolTipText = task.end?.summary }
                }
                // What it runs, under its description: a workflow's script file, else the command or prompt on one line.
                row {
                    icon(EmptyIcon.create(kindIcon))
                    val script = task.script
                    if (script != null) link(script.name) { openFile(project, script) }.applyToComponent { toolTipText = script.toString() }.shrinkable()
                    else {
                        val command = checkNotNull(task.command) { "${task.kind} task ${task.id} without a command" }
                        // Grey as a comment, but a click shows it whole, to select and copy.
                        cell(commentLabel(command.trim().replace(WHITESPACE, " ")).apply {
                            toolTipText = "Show the whole ${if (task.kind == BackgroundTask.Kind.AGENT) "prompt" else "command"}"
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            addMouseListener(object : MouseAdapter() {
                                override fun mouseClicked(e: MouseEvent) {
                                    CommandDialog(project, task.description, command).show()
                                }
                            })
                        }).shrinkable()
                    }
                }
            }
        }
        if (context.mcpServers.isNotEmpty()) card("MCP servers") {
            paged("MCP servers", context.mcpServers) { server, _ -> row { icon(AllIcons.Nodes.Plugin); label(server).shrinkable() } }
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
        row { cell(wrapped(details)).align(AlignX.FILL) }
    }

    /**
     * [component] in a plain panel: the grid of a card makes room for what its cells paint past their bounds, and a
     * tree or a wrapped description made it move every row of the card to the right.
     */
    private fun wrapped(component: JComponent) = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(component, BorderLayout.CENTER)
    }

    /**
     * [root]'s folders and files as Branch Diff shows them, every folder open but test folders ([openByDefault]) until opened
     * or closed here (kept by path across renders, until another session is shown); a click on a file opens its diff against the base.
     */
    private fun changesTree(git: GitState, root: ChangeDir): Tree {
        val nodes = HashMap<String, DefaultMutableTreeNode>()
        fun node(change: ChangeNode, path: String): DefaultMutableTreeNode = DefaultMutableTreeNode(change).also { node ->
            if (change is ChangeDir) {
                nodes[path] = node
                for (child in change.children) node.add(node(child, if (path.isEmpty()) child.name else "$path/${child.name}"))
            }
        }
        val top = node(root, "")
        val keys = nodes.entries.associate { (path, node) -> node to path }
        return Tree(DefaultTreeModel(top)).apply {
            isRootVisible = false
            showsRootHandles = true
            isOpaque = false
            cellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                    when (val change = (value as DefaultMutableTreeNode).userObject as ChangeNode) {
                        is ChangeDir -> {
                            icon = AllIcons.Nodes.Folder
                            append(change.name)
                            append("  " + if (change.files == 1) "1 file" else "${change.files} files", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            lineCounts(change.added, change.removed)
                        }
                        is ChangeFile -> {
                            icon = fileIcon(Path.of(change.name))
                            if (change.change.stage == Stage.UNCOMMITTED) append("● ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE))
                            val style = if (change.change.kind == Kind.DELETED) SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN
                            append(change.name, SimpleTextAttributes(style, change.change.kind.status.color))
                            lineCounts(change.change.added, change.change.removed)
                        }
                    }
                }

                private fun lineCounts(added: Int, removed: Int) {
                    if (added > 0) append("  +$added", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, FileStatus.ADDED.color))
                    if (removed > 0) append("  −$removed", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, FileStatus.DELETED.color))
                }
            }
            fun open(node: DefaultMutableTreeNode) = node.isRoot || changeFolders[keys.getValue(node)] ?: openByDefault(node.userObject as ChangeDir)
            // Only a folder with no closed folder above it: expanding one opens its parents too.
            for (node in nodes.values) {
                if (node.path.all { open(it as DefaultMutableTreeNode) }) expandPath(TreePath(node.path))
            }
            addTreeExpansionListener(object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) { changeFolders[keys.getValue(event.path.lastPathComponent as DefaultMutableTreeNode)] = true }
                override fun treeCollapsed(event: TreeExpansionEvent) { changeFolders[keys.getValue(event.path.lastPathComponent as DefaultMutableTreeNode)] = false }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    // The second click of a double-click would open the diff again; the preview tab is kept by a double-click on it.
                    if (event.clickCount != 1) return
                    val file = (getPathForLocation(event.x, event.y)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ChangeFile ?: return
                    showBaseDiff(project, git, file.index)
                }
            })
        }
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
        val tab = checkNotNull(active.tab) { "skills shown without a session tab" }
        val host = FileEditorManager.getInstance(project).openFile(tabFileOf(tab), true).filterIsInstance<SessionPaneHost>().single()
        val pane = checkNotNull(host.pane) { "the tab of session ${tab.sessionId} shows no terminal" }
        if (!pane.type(command)) Messages.showInfoMessage(project, "The session's tab does not run claude.", "Paste Skill Command")
    }

    /** Opens or closes the skill [key], reading its files off the EDT. */
    private fun toggleSkill(key: Pair<Path, String>) {
        if (key in skills) skills -= key
        else {
            skills[key] = null
            background(project, "Reading skill", { runCatching { skillFiles(key.second, Homes.claude, key.first) } }) {
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
                        FileEditorManager.getInstance(project).openFile(tabFileOf(checkNotNull(active.tab)), true)
                    }
                })
            }.customize(UnscaledGaps(bottom = 6)) // The text cell's default gaps without the top one, flush with the card's top.
        }
        row {
            listOfNotNull(context.model?.let(::modelName), context.effort).forEach { cell(Chip(it)) }
            // The session is looked up at the click, as the tab is: the pane is not rebuilt for every change of it.
            if (context.cwdExists) cell(InplaceButton("Continue in a new session: same model, effort and added directories, in ${context.cwd}", NEW_SESSION_ICON) {
                continueSession(project, service.sessions.first { it.id == shownId }, context.cwd)
            })
            comment(shortAge(context.lastMessage, Instant.now())).align(AlignX.RIGHT)
        }
    }.apply { isOpaque = false }

    /** A card holding a section that stays collapsed or expanded across renders. */
    private fun JPanel.card(title: String, indent: Boolean = true, init: Panel.() -> Unit) {
        add(ContextCard(panel { section(title, indent = indent, init = init) }.apply { isOpaque = false }))
    }

    /**
     * Asks under [anchor] for a new base of [git]'s branch among the other branches, nearest first ([Git.nearestBases]), and
     * reads the changes again against it. The point is taken at the click: the pane may be rebuilt, and [anchor] gone,
     * while the branches are read.
     */
    private fun pickBase(git: GitState, anchor: JComponent) {
        val under = RelativePoint.fromScreen(Point(anchor.locationOnScreen).apply { y += anchor.height })
        background(project, "Reading branches", { Git.nearestBases(git.root, git.branch) }) { bases ->
            if (bases.isEmpty()) return@background Messages.showInfoMessage(project, "The repository has no branch other than ${git.branch}.", "Base Branch")
            // No separators: getSeparatorAbove never meets an index past the last item.
            val choices = BaseChoices(bases, bases.size, bases.size, bases.indexOf(git.base).coerceAtLeast(0))
            basePopup("Base Branch of ${git.branch}", choices) { base ->
                background(project, "Setting base branch", { Git.setBase(git.root, git.branch, base) }) { active.baseChanged() }
            }.show(under)
        }
    }

    /** A row per file of [files], named by [label], opening it in the editor. */
    private fun Panel.files(key: String, files: List<Path>, label: (Path) -> String) = paged(key, files) { file, _ ->
        row {
            icon(fileIcon(file))
            link(label(file)) { openFile(project, file) }.applyToComponent { toolTipText = file.toString() }.shrinkable()
        }
    }

    /** The rows of each item of [items], added by [fill] with the item and its index; [FILES_SHOWN] items until Show more, kept by [key]. */
    private fun <T> Panel.paged(key: String, items: List<T>, fill: Panel.(T, Int) -> Unit) {
        val all = key in fullLists
        for ((index, item) in (if (all) items else items.take(FILES_SHOWN)).withIndex()) fill(item, index)
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
        /** The skills pinned in every session's pane of this IDE, by full name. */
        private const val PINNED_SKILLS = "dev.myair.context.pinnedSkills"
        private fun pinnedSkills(): Set<String> = PropertiesComponent.getInstance().getList(PINNED_SKILLS).orEmpty().toSet()
        private fun setPinnedSkills(names: Set<String>) = PropertiesComponent.getInstance().setList(PINNED_SKILLS, names.sorted())
        private const val SECTIONS = "dev.myair.context.sections"
        /** Whether Changes vs base shows its files as a tree of folders rather than a list, in every session's pane. */
        private const val CHANGES_AS_TREE = "dev.myair.context.changesAsTree"
        private fun changesAsTree() = PropertiesComponent.getInstance().getBoolean(CHANGES_AS_TREE)
        /** Which changes Changes vs base shows ([ChangesFilter]'s name), in every session's pane. */
        private const val CHANGES_FILTER = "dev.myair.context.changesFilter"
        private fun changesFilter() = ChangesFilter.valueOf(PropertiesComponent.getInstance().getValue(CHANGES_FILTER, ChangesFilter.ALL.name))
        private fun sectionStates(): List<String> = PropertiesComponent.getInstance().getList(SECTIONS).orEmpty()
        private fun setSectionStates(states: List<String>) = PropertiesComponent.getInstance().setList(SECTIONS, states)
        private const val FILES_SHOWN = 5
        private const val CARD_GAP = 4
        private val KIND_LETTERS = mapOf(Kind.ADDED to "A", Kind.MODIFIED to "M", Kind.DELETED to "D", Kind.UNTRACKED to "U")
        private val WHITESPACE = Regex("\\s+")

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
