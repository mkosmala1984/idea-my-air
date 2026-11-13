package dev.myair.prompt

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.impl.EditorEmptyStateComponentProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.impl.EditorsSplitters
import com.intellij.openapi.fileEditor.impl.buildEditorEmptyStateComponentOnUiThread
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.listCellRenderer.LcrInitParams
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.myair.actions.Git
import dev.myair.actions.GitRepo
import dev.myair.actions.MODELS
import dev.myair.actions.baseChoices
import dev.myair.actions.recentBases
import dev.myair.actions.startPreset
import dev.myair.actions.summary
import dev.myair.editor.CLAUDE_ICON
import dev.myair.editor.projectBase
import dev.myair.service.LatestRead
import com.intellij.util.concurrency.AppExecutorUtil
import dev.myair.store.EFFORTS
import dev.myair.store.Preset
import dev.myair.store.PresetStore
import dev.myair.editor.projectIcon
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.project.ProjectManager
import kotlin.io.path.isDirectory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** The choices of the last start, so the next panel opens on them, and under `.draft` a message not yet sent. */
private const val LAST = "dev.myair.prompt"

/** Where the session works: the configuration's directory, a new worktree on the base, or on a new branch off it. */
private enum class Workspace(val text: String, val worktree: Boolean, val newBranch: Boolean) {
    CHECKOUT("Current checkout", false, false),
    WORKTREE("New worktree", true, false),
    NEW_BRANCH("New worktree, new branch", true, true);

    companion object {
        fun of(choice: Choice) = entries.single { it.worktree == choice.worktree && it.newBranch == choice.newBranch }
    }
}

/** The repository of the configuration's directory: its branches and the one checked out. */
private data class Repo(val git: GitRepo, val current: String)

/**
 * A first message and how the session starts: a configuration (Default, then `presets.json`), the model, the effort,
 * the workspace (the configuration's directory, or a new worktree off a base branch) and that base. A configuration
 * sets the rest; each may then be changed, and the next panel opens on the last start's choices, or on the message
 * typed and not sent with the choices it was typed with. Enter starts it,
 * Shift+Enter breaks the line, and an empty message starts the session without one. [started] runs before the session
 * starts, with the message already taken. Opened [from] a configuration, it starts on that configuration with its
 * prompt typed in, and leaves the stored draft alone until the message is changed.
 */
class PromptPanel(private val project: Project, private val started: () -> Unit, from: Preset? = null) : JPanel(VerticalLayout(0)) {
    val prompt = JBTextArea(3, 60).apply {
        font = JBUI.Fonts.label().biggerOn(1f)
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = "First message to Claude, or nothing; paste images with Ctrl+V"
    }
    private val read = PresetStore.INSTANCE.read()
    private val presets = listOf(DEFAULT_PRESET) + read.getOrDefault(emptyList())
    private val draft = if (from == null) loadDraft() else null
    private var choice = from?.let(::choiceOf) ?: opening(presets, loadChoice(LAST), draft)

    /** Whether the message may go to the stored draft: not yet for a panel opened from a configuration. */
    private var edited = from == null
    private var repo: Result<Repo>? = null
    private var base: String? = null

    /** Reads the repository of a configuration's directory into [repo], keeping the base while it is among its branches. */
    private val repoReads = LatestRead.onPool<Path, Repo>(
        AppExecutorUtil.getAppExecutorService(), { ModalityState.stateForComponent(this) }, { Repo(Git.repo(it), Git.currentBranch(it)) },
    ) { _, read ->
        repo = read
        read.getOrNull()?.let { base = base?.takeIf { b -> b in it.git.branches } ?: choicesOf(it).let { c -> c.items[c.selected] } }
        update(choice)
    }

    private val presetChip = Chip(AllIcons.Actions.Lightning) { chip ->
        choose(chip, presets, choice.preset, { it.name }, { it.summary() }) {
            prompt.text = switchedMessage(prompt.text, choice.preset, it)
            selectPreset(choiceOf(it))
        }
    }
    private val modelChip = Chip(CLAUDE_ICON) { chip ->
        choose(chip, (MODELS + choice.model).distinct(), choice.model, ::modelLabel) { update(choice.copy(model = it)) }
    }
    private val effortChip = Chip(AllIcons.Actions.ProfileCPU) { chip ->
        choose(chip, EFFORTS, choice.effort, { it.replaceFirstChar(Char::uppercase) }) { update(choice.copy(effort = it)) }
    }
    private val workspaceChip = Chip(null) { chip ->
        choose(chip, Workspace.entries, Workspace.of(choice), { it.text }, title = "Workspace") {
            update(choice.copy(worktree = it.worktree, newBranch = it.newBranch))
        }
    }
    private val branchName = JBTextField(24).apply { emptyText.text = "TR-1234-short-description" }
    private val suggestChip = Chip(AllIcons.Actions.Lightning) { chip -> suggest(chip) }.apply {
        text = "Suggest"
        chevron = false
        toolTipText = "Haiku names the branch from the message, reading the Jira issue it names"
    }
    private val projectChip = Chip(null) { chip -> chooseProject(chip) }
    private val branchChip = Chip(AllIcons.Vcs.Branch) { chip -> chooseBase(chip) }
    private val send = SendButton("Start the session (Enter)") { start() }
    private val error = JBLabel().apply { foreground = UIUtil.getErrorForeground() }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)
        val card = Rounded(CARD, JBUI.scale(16), squareTop = false).apply {
            layout = BorderLayout(0, JBUI.scale(8))
            border = JBUI.Borders.empty(12, 14, 8, 10)
            add(JBScrollPane(prompt).apply { isOpaque = false; viewport.isOpaque = false; border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(row(presetChip, modelChip, effortChip), BorderLayout.WEST)
                add(send, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        val bar = Rounded(BAR, JBUI.scale(12), squareTop = true).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(2, 6)
            add(row(workspaceChip, branchName, suggestChip), BorderLayout.WEST)
            add(row(projectChip, branchChip), BorderLayout.EAST)
        }
        add(card)
        add(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(0, 24); add(bar) })
        add(error)
        prompt.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                e.consume()
                if (e.isShiftDown) prompt.replaceSelection("\n") else start()
            }
        })
        // On the text area itself, ahead of the IDE's paste, which takes only text from the clipboard.
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val mentions = pastedMentions(CopyPasteManager.getInstance().contents ?: return, PASTED_IMAGES) ?: return prompt.paste()
                prompt.replaceSelection(mentions.joinToString(" ", postfix = " "))
            }
        }.registerCustomShortcutSet(ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE).shortcutSet, prompt)
        prompt.text = from?.let(::openingMessage) ?: draft?.text.orEmpty()
        prompt.caretPosition = prompt.text.length
        prompt.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                edited = true
                saveDraft()
            }
        })
        selectPreset(choice, draft?.base ?: choice.preset.base)
        ApplicationManager.getApplication().executeOnPooledThread { pruneImages(PASTED_IMAGES, Instant.now() - IMAGES_KEPT) }
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = maxOf(width, JBUI.scale(720)) }

    /**
     * A configuration brings its own directory, whose repository is read again. [wanted] becomes the base while it is
     * among the branches read, else the one the base popup would preselect.
     */
    private fun selectPreset(new: Choice, wanted: String? = new.preset.base) {
        repo = null
        base = wanted
        update(new)
        repoReads.request(dirOf(new))
    }

    /** Another project's directory in place of the configuration's, whose repository is read again. */
    private fun selectProject(dir: String) {
        repo = null
        update(choice.copy(project = dir))
        repoReads.request(dirOf(choice))
    }

    /** Where a session of [choice] starts: the project chosen here, else the configuration's directory. */
    private fun dirOf(choice: Choice): Path = choice.project?.let(Path::of) ?: choice.preset.dir(projectBase(project))

    private fun update(new: Choice) {
        choice = new
        presetChip.text = new.preset.name
        modelChip.text = modelLabel(new.model)
        effortChip.text = new.effort.replaceFirstChar(Char::uppercase)
        workspaceChip.icon = if (new.worktree) AllIcons.Actions.NewFolder else AllIcons.Nodes.Folder
        workspaceChip.text = Workspace.of(new).text
        branchName.isVisible = new.newBranch
        suggestChip.isVisible = new.newBranch
        val found = repo?.getOrNull()
        // In the checkout the branch is only shown; a worktree is made off the base picked here.
        branchChip.text = when {
            repo == null -> "Reading branches..."
            found == null -> "No repository"
            new.worktree -> base!!
            else -> found.current
        }
        branchChip.chevron = new.worktree && found != null
        val dir = dirOf(new)
        projectChip.text = dir.fileName.toString()
        projectChip.icon = projectIcon(dir, dir.fileName.toString(), 16)
        workspaceChip.isEnabled = found != null
        error.text = listOfNotNull(
            repo?.exceptionOrNull()?.message?.takeIf { new.worktree },
            read.exceptionOrNull()?.let { "Broken ${PresetStore.INSTANCE.file}: ${it.message}" },
        ).joinToString("\n")
        error.isVisible = error.text.isNotEmpty()
        revalidate()
        repaint()
        // With no message, another panel's draft stays: the empty editor area keeps its panel while a popup is used.
        if (prompt.text.isNotBlank()) saveDraft()
    }

    /** The message with the choices it is typed with, until it is sent or emptied. */
    private fun saveDraft() = if (!edited) Unit else storeDraft(prompt.text.takeIf { it.isNotBlank() }?.let { Draft(it, choice.toLast(), base) })

    private fun choicesOf(repo: Repo) = baseChoices(repo.git.branches, repo.git.default, recentBases(repo.git.mainRoot))

    private fun chooseProject(chip: Chip) {
        val open = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }
        val recent = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
        val items = projectChoices(open, recent) { Path.of(it).isDirectory() }
        choose(chip, items, items.firstOrNull { Path.of(it) == dirOf(choice) }, { Path.of(it).fileName.toString() }, { it }, search = true) {
            selectProject(it)
        }
    }

    private fun chooseBase(chip: Chip) {
        val found = repo?.getOrNull() ?: return
        if (!choice.worktree) return
        val recent = recentBases(found.git.mainRoot)
        choose(chip, choicesOf(found).items, base, { it }, { branchTag(it, found.current, found.git.default, recent) }, search = true) {
            base = it
            update(choice)
        }
    }

    private fun <T> choose(
        chip: Chip, items: List<T>, selected: T?, text: (T) -> String, tag: (T) -> String? = { null },
        title: String? = null, search: Boolean = false, chosen: (T) -> Unit,
    ) {
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setRenderer(listCellRenderer {
                text(text(value))
                tag(value)?.let { text(it) { align = LcrInitParams.Align.RIGHT; foreground = greyForeground } }
            })
            .setItemChosenCallback(chosen)
        if (selected != null) builder.setSelectedValue(selected, true)
        if (title != null) builder.setTitle(title)
        if (search) builder.setNamerForFiltering(text).setFilterAlwaysVisible(true).setVisibleRowCount(12)
        builder.createPopup().show(RelativePoint(chip, Point(0, chip.height)))
    }

    /** Haiku's name for the branch, from the message; the message may be no more than a Jira key. */
    private fun suggest(chip: Chip) {
        chip.isEnabled = false
        chip.text = "Naming..."
        suggestInBackground(chip, dirOf(choice), prompt.text) { result ->
            chip.isEnabled = true
            chip.text = "Suggest"
            result.onSuccess { branchName.text = it }
            error.text = result.exceptionOrNull()?.message.orEmpty()
            error.isVisible = error.text.isNotEmpty()
        }
    }

    private fun start() {
        val branch = branchName.text.trim().takeIf { choice.newBranch }
        if (branch?.isEmpty() == true) {
            error.text = "Name the new branch, or let Suggest name it from the message"
            error.isVisible = true
            return
        }
        val text = prompt.text.trim().ifEmpty { null }
        saveChoice(LAST, choice.toLast())
        // Taken from the screen: [started] may close the popup holding the button, and the base popup of a worktree
        // started before its branches are read opens here.
        val where = RelativePoint.fromScreen(RelativePoint(send, Point(0, send.height)).screenPoint)
        prompt.text = ""
        started()
        startPreset(project, choice.toPreset(base), where, text, branch)
    }
}

private fun loadChoice(key: String) = PropertiesComponent.getInstance().run {
    LastChoice(
        getValue("$key.preset"), getValue("$key.model"), getValue("$key.effort"), getValue("$key.worktree")?.toBooleanStrict(),
        getValue("$key.newBranch")?.toBooleanStrict(), getValue("$key.project"),
    )
}

/** Null removes the stored choices. */
private fun saveChoice(key: String, choice: LastChoice?) = PropertiesComponent.getInstance().run {
    setValue("$key.preset", choice?.preset)
    setValue("$key.model", choice?.model)
    setValue("$key.effort", choice?.effort)
    setValue("$key.worktree", choice?.worktree?.toString())
    setValue("$key.newBranch", choice?.newBranch?.toString())
    setValue("$key.project", choice?.project)
}

private fun loadDraft(): Draft? {
    val properties = PropertiesComponent.getInstance()
    val text = properties.getValue("$LAST.draft.text") ?: return null
    return Draft(text, loadChoice("$LAST.draft"), properties.getValue("$LAST.draft.base"))
}

/** Null removes the stored draft. */
private fun storeDraft(draft: Draft?) {
    PropertiesComponent.getInstance().setValue("$LAST.draft.text", draft?.text)
    PropertiesComponent.getInstance().setValue("$LAST.draft.base", draft?.base)
    saveChoice("$LAST.draft", draft?.choice)
}

/** The panel in place of an empty editor area; Air shows its own composer there too unless `air.inline.empty.state.prompt` is off. */
class PromptEmptyState : EditorEmptyStateComponentProvider {
    override fun isAvailable(splitters: EditorsSplitters) = splitters.manager.project.basePath != null
    override suspend fun createComponent(splitters: EditorsSplitters): JComponent =
        buildEditorEmptyStateComponentOnUiThread { PromptPanel(splitters.manager.project, {}) }
    override fun claimsFocus(splitters: EditorsSplitters) = true
    override fun getPreferredFocusedComponent(component: JComponent) = (component as PromptPanel).prompt
}

/** The same panel in a popup, wherever the editor area is. */
class NewSessionWithPromptAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = showPromptPopup(e.project!!)
}

/** The panel in a popup centered in [project]'s window, opened [from] a configuration when given. */
fun showPromptPopup(project: Project, from: Preset? = null) {
    lateinit var popup: JBPopup
    val panel = PromptPanel(project, { popup.cancel() }, from)
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.prompt)
        .setRequestFocus(true).setResizable(true).setMovable(true).setCancelOnClickOutside(true)
        .createPopup()
    popup.showCenteredInCurrentWindow(project)
}
