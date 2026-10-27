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
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
import dev.myair.editor.projectBase
import dev.myair.store.EFFORTS
import dev.myair.store.PresetStore
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Instant
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import kotlin.io.path.isDirectory

private val CLAUDE_ICON = IconLoader.getIcon("/icons/claude.svg", PromptPanel::class.java)

/** The choices of the last start, so the next panel opens on them, and under `.draft` a message not yet sent. */
private const val LAST = "dev.myair.prompt"

private val CARD = JBColor(0xEEF0F4, 0x2F3134)
private val BAR = JBColor(0xE6E8EC, 0x292B2E)
private val SEND = JBColor(0xD3D5DB, 0x4A4D52)

/** The repository of the configuration's directory: its branches and the one checked out. */
private data class Repo(val git: GitRepo, val current: String)

/**
 * A first message and how the session starts: a configuration (Default, then `presets.json`), the model, the effort,
 * the workspace (the configuration's directory, or a new worktree off a base branch) and that base. A configuration
 * sets the rest; each may then be changed, and the next panel opens on the last start's choices, or on the message
 * typed and not sent with the choices it was typed with. Enter starts it,
 * Shift+Enter breaks the line, and an empty message starts the session without one. [started] runs before the session
 * starts, with the message already taken.
 */
class PromptPanel(private val project: Project, private val started: () -> Unit) : JPanel(VerticalLayout(0)) {
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
    private val draft = loadDraft()
    private var choice = opening(presets, loadChoice(LAST), draft)
    private var repo: Result<Repo>? = null
    private var base: String? = null

    /** The directory whose repository [repo] holds or is being read; a read for another one is dropped. */
    private var repoOf: Path? = null

    private val presetChip = Chip(AllIcons.Actions.Lightning) { chip ->
        choose(chip, presets, choice.preset, { it.name }, { it.summary() }) { selectPreset(choiceOf(it)) }
    }
    private val modelChip = Chip(CLAUDE_ICON) { chip ->
        choose(chip, (MODELS + choice.model).distinct(), choice.model, ::modelLabel) { update(choice.copy(model = it)) }
    }
    private val effortChip = Chip(AllIcons.Actions.ProfileCPU) { chip ->
        choose(chip, EFFORTS, choice.effort, { it.replaceFirstChar(Char::uppercase) }) { update(choice.copy(effort = it)) }
    }
    private val workspaceChip = Chip(null) { chip ->
        choose(chip, listOf(false, true), choice.worktree, { if (it) "New worktree" else "Current checkout" }, title = "Workspace") {
            update(choice.copy(worktree = it))
        }
    }
    private val branchChip = Chip(AllIcons.Vcs.Branch) { chip -> chooseBase(chip) }
    private val send = SendButton { start() }
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
            add(row(workspaceChip), BorderLayout.WEST)
            add(row(branchChip), BorderLayout.EAST)
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
        draft?.let { prompt.text = it.text }
        prompt.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = saveDraft()
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
        val dir = new.preset.project?.let(Path::of) ?: projectBase(project)
        repoOf = dir
        ApplicationManager.getApplication().executeOnPooledThread {
            val read = runCatching {
                check(dir.isDirectory()) { "$dir is not a directory." }
                Repo(Git.repo(dir), Git.currentBranch(dir))
            }
            ApplicationManager.getApplication().invokeLater({
                if (repoOf != dir) return@invokeLater
                repo = read
                read.getOrNull()?.let { base = base?.takeIf { b -> b in it.git.branches } ?: choicesOf(it).let { c -> c.items[c.selected] } }
                update(choice)
            }, ModalityState.stateForComponent(this))
        }
    }

    private fun update(new: Choice) {
        choice = new
        presetChip.text = new.preset.name
        modelChip.text = modelLabel(new.model)
        effortChip.text = new.effort.replaceFirstChar(Char::uppercase)
        workspaceChip.icon = if (new.worktree) AllIcons.Actions.NewFolder else AllIcons.Nodes.Folder
        workspaceChip.text = if (new.worktree) "New worktree" else "Current checkout"
        val found = repo?.getOrNull()
        // In the checkout the branch is only shown; a worktree is made off the base picked here.
        branchChip.text = when {
            repo == null -> "Reading branches..."
            found == null -> "No repository"
            new.worktree -> base!!
            else -> found.current
        }
        branchChip.chevron = new.worktree && found != null
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
    private fun saveDraft() = storeDraft(prompt.text.takeIf { it.isNotBlank() }?.let { Draft(it, choice.toLast(), base) })

    private fun choicesOf(repo: Repo) = baseChoices(repo.git.branches, repo.git.default, recentBases(repo.git.mainRoot))

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

    private fun start() {
        val text = prompt.text.trim().ifEmpty { null }
        saveChoice(LAST, choice.toLast())
        // Taken from the screen: [started] may close the popup holding the button, and the base popup of a worktree
        // started before its branches are read opens here.
        val where = RelativePoint.fromScreen(RelativePoint(send, Point(0, send.height)).screenPoint)
        prompt.text = ""
        started()
        startPreset(project, choice.toPreset(base), where, text)
    }
}

private fun loadChoice(key: String) = PropertiesComponent.getInstance().run {
    LastChoice(getValue("$key.preset"), getValue("$key.model"), getValue("$key.effort"), getValue("$key.worktree")?.toBooleanStrict())
}

/** Null removes the stored choices. */
private fun saveChoice(key: String, choice: LastChoice?) = PropertiesComponent.getInstance().run {
    setValue("$key.preset", choice?.preset)
    setValue("$key.model", choice?.model)
    setValue("$key.effort", choice?.effort)
    setValue("$key.worktree", choice?.worktree?.toString())
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

private fun row(vararg chips: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
    isOpaque = false
    chips.forEach(::add)
}

/** A flat dropdown: icon, text and chevron, with a hover background; a click runs [click]. */
private class Chip(icon: Icon?, private val click: (Chip) -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {
    private val label = JBLabel(icon)
    private val down = JBLabel(AllIcons.General.ChevronDown)
    private var hover = false
    var text: String
        get() = label.text
        set(value) { label.text = value }
    var icon: Icon?
        get() = label.icon
        set(value) { label.icon = value }
    var chevron: Boolean
        get() = down.isVisible
        set(value) { down.isVisible = value }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 6)
        add(label)
        add(down)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) click(this@Chip) }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        label.isEnabled = enabled
        down.isEnabled = enabled
    }

    override fun paintComponent(g: Graphics) {
        if (!hover || !isEnabled) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
        g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
        g2.dispose()
    }
}

/** A panel filled with [fill] and rounded, at the bottom only when [squareTop]. */
private class Rounded(private val fill: JBColor, private val arc: Int, private val squareTop: Boolean) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // A square top is drawn above the panel, outside the clip.
        val y = if (squareTop) -arc else 0
        g2.color = fill
        g2.fillRoundRect(0, y, width - 1, height - 1 - y, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(0, y, width - 1, height - 1 - y, arc, arc)
        g2.dispose()
    }
}

/** The round send button with an up arrow. */
private class SendButton(click: () -> Unit) : JButton() {
    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        toolTipText = "Start the session (Enter)"
        addActionListener { click() }
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(32), JBUI.scale(32))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = SEND
        g2.fillOval(0, 0, width - 1, height - 1)
        val (x, y, r) = Triple(width / 2, height / 2, JBUI.scale(6))
        g2.color = UIUtil.getLabelForeground()
        g2.stroke = BasicStroke(JBUI.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(x, y + r, x, y - r)
        g2.drawPolyline(intArrayOf(x - r + 1, x, x + r - 1), intArrayOf(y - 1, y - r, y - 1), 3)
        g2.dispose()
    }
}

/** The panel in place of an empty editor area; Air shows its own composer there too unless `air.inline.empty.state.prompt` is off. */
class PromptEmptyState : EditorEmptyStateComponentProvider {
    override fun isAvailable(splitters: EditorsSplitters) = splitters.manager.project.basePath != null
    override suspend fun createComponent(splitters: EditorsSplitters): JComponent =
        buildEditorEmptyStateComponentOnUiThread { PromptPanel(splitters.manager.project) {} }
    override fun claimsFocus(splitters: EditorsSplitters) = true
    override fun getPreferredFocusedComponent(component: JComponent) = (component as PromptPanel).prompt
}

/** The same panel in a popup, wherever the editor area is. */
class NewSessionWithPromptAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        lateinit var popup: JBPopup
        val panel = PromptPanel(e.project!!) { popup.cancel() }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.prompt)
            .setRequestFocus(true).setResizable(true).setMovable(true).setCancelOnClickOutside(true)
            .createPopup()
        popup.showCenteredInCurrentWindow(e.project!!)
    }
}
