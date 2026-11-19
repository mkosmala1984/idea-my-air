package dev.myair.planview

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.myair.actions.NewSessionInDirAction
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.NEEDS_INPUT_AMBER
import dev.myair.editor.PlanStart
import dev.myair.editor.projectBase
import dev.myair.editor.sessionIcon
import dev.myair.folders.ChangeSource
import dev.myair.folders.PlanStatus
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders
import dev.myair.folders.progress
import dev.myair.prompt.Rounded
import dev.myair.service.ClaudeSessionsService
import dev.myair.toolwindow.PaneFont
import dev.myair.toolwindow.Urgency
import dev.myair.toolwindow.commentLabel
import dev.myair.toolwindow.deleteMilestone
import dev.myair.toolwindow.deleteTask
import dev.myair.toolwindow.icon
import dev.myair.toolwindow.shortAge
import dev.myair.toolwindow.shrinkable
import dev.myair.toolwindow.urgencyOf
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The plan side of a plan tab, in the composer's look: the plan's name and progress, the grey cards of its sections
 * ([sectionCard]), then a grey card per milestone listing its tasks one line each; an open task is a white card with its
 * next step, description and sessions. Each task's actions, status included, are one menu beside its title. Sections,
 * milestones and tasks fold by [Folding]; every text is edited in place ([PlanEdit]).
 * [PlanEditor] hands it each new [PlanView]; it rebuilds only when what it shows changed, keeping an open edit's text component.
 */
class PlanPanel(private val project: Project, internal val folderId: String, parent: Disposable) : CardPage(parent) {
    private val service = ClaudeSessionsService.getInstance()
    private var view: PlanView? = null
    internal var open: OpenEdit<PlanEdit>? = null
        private set
    /** The open edit's component takes the focus on the next build: it was just opened, or saved and left open empty. */
    private var focusOpen = false
    /** What the panel was last built from. */
    private var rendered: Any? = null
    internal var folding = Folding()
        private set
    private var hideClosed: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(HIDE_CLOSED_KEY)
        set(value) = PropertiesComponent.getInstance().setValue(HIDE_CLOSED_KEY, value)
    /**
     * The panel holding each section's and milestone's card, by section or milestone id, so a fold swaps that card alone.
     * A holder stays put: [VerticalLayout] keeps its own order and puts a component added again last, whatever its index.
     */
    private val cards = mutableMapOf<String, JPanel>()

    fun show(view: PlanView) {
        this.view = view
        render()
    }

    override fun render(force: Boolean) {
        val view = view ?: return
        // A task or milestone the manager deleted meanwhile takes its open edit along.
        open = open?.takeIf { it.edit.current(view.plan) != null }
        val sessions = view.milestones.flatMap { m -> m.tasks.flatMap { it.sessions } }
        val now = Instant.now()
        // What the panel shows, not whole sessions: a working session's transcript changes them on every refresh.
        val shown = listOf(view.plan, view.folder.name, open?.edit, view.milestones.map { m -> m.tasks.map { t -> t.sessions.map { it.id } } },
            sessions.map { listOf(it.title, service.live[it.id]?.status, it.id in service.unread, shortAge(it.lastMessage, now)) })
        if (!force && shown == rendered && !focusOpen) return
        rendered = shown
        val focused = open?.component?.isFocusOwner == true
        val content = JPanel(VerticalLayout(JBUI.scale(GAP))).apply {
            isOpaque = false
            add(card { header(view) })
            cards.clear()
            Section.entries.forEach { s -> add(holder(sectionCard(s, view.plan)).also { cards[s.id] = it }) }
            view.milestones.forEach { m -> add(holder(milestoneCard(m)).also { cards[m.milestone.id] = it }) }
            add(card { addRow(PlanEdit.NewMilestone, "+ Add milestone") })
        }
        PaneFont.growAll(content)
        removeAll()
        add(content, BorderLayout.NORTH)
        revalidate()
        repaint()
        open?.component?.takeIf { focused || focusOpen }?.let { IdeFocusManager.getInstance(project).requestFocus(it, true) }
        focusOpen = false
    }

    private fun milestones() = checkNotNull(view).plan.milestones

    private fun Panel.header(view: PlanView) {
        row {
            label(view.folder.name).applyToComponent { font = JBFont.h3().asBold() }.shrinkable()
            checkBox("Hide done").applyToComponent {
                isSelected = hideClosed
                addActionListener { hideClosed = isSelected; render(force = true) }
            }
            actions(
                action("Expand All", AllIcons.Actions.Expandall) { foldAll(true) },
                action("Collapse All", AllIcons.Actions.Collapseall) { foldAll(false) },
            )
        }
        val tasks = milestones().flatMap { it.tasks }
        val counts = statusCounts(tasks)
        row {
            cell(commentLabel(progress(tasks)))
            cell(ProgressLine(counts, PLAN_BAR))
            cell(commentLabel(openWork(counts)))
            requirementsSummary(view.plan.requirements)?.let { cell(commentLabel(it)) }
            cell(commentLabel(reviewSummary(view.plan.review)))
        }
    }

    private fun milestoneCard(m: MilestoneView): JComponent {
        val milestone = m.milestone
        val unfolded = folding.open(milestone) || open?.edit?.let { it.itemId == milestone.id || it.itemId in m.tasks.map { t -> t.task.id } } == true
        val rows = JPanel(VerticalLayout(0)).apply {
            isOpaque = false
            add(head(milestone.id, chevron(unfolded), title(PlanEdit.Title(milestone.id), milestone.title),
                activity(m.tasks.flatMap { it.sessions }) + listOf(commentLabel(m.progress), ProgressLine(statusCounts(milestone.tasks)), buttons(more(
                    action("Rename") { openEdit(PlanEdit.Title(milestone.id)) },
                    action(if (milestone.done) "Reopen" else "Mark Done") { change { it.editMilestone(milestone.id) { x -> x.copy(done = !milestone.done) } } },
                    action("Edit Description") { openEdit(PlanEdit.Description(milestone.id)) },
                    action("Delete") { deleteMilestone(project, milestone) },
                )))))
            if (unfolded) {
                add(card { markdown(PlanEdit.Description(milestone.id), milestone.description, null) }.apply { border = JBUI.Borders.emptyLeft(ID_COLUMN) })
                m.tasks.filter { !hideClosed || !it.task.status.closed || open?.edit?.itemId == it.task.id }.forEach {
                    add(if (folding.open(it.task) || open?.edit?.itemId == it.task.id) taskCard(it) else taskHead(it, unfolded = false))
                }
                add(card { indent { addRow(PlanEdit.NewTask(milestone.id), "+ Add task") } })
            }
        }
        return greyCard(rows)
    }

    /** A section's, milestone's or task's head ([head]); a click folds [id]. */
    internal fun head(id: String, lead: JComponent, title: JComponent, trail: List<JComponent>, label: String? = id) = head(lead, title, trail, label) { fold(id) }

    /** The icon of the most urgent of [sessions] and the age of the newest; nothing without a session. */
    private fun activity(sessions: List<ClaudeSession>): List<JComponent> {
        val urgent = sessions.minByOrNull { urgencyOf(service.live[it.id], it.id in service.unread)?.ordinal ?: Urgency.entries.size } ?: return emptyList()
        return listOf(JBLabel(sessionIcon(service.live[urgent.id]?.status, urgent.id in service.unread)), commentLabel(shortAge(sessions.maxOf { it.lastMessage }, Instant.now())))
    }

    /** An unfolded task: a white card, its head folding it on a click, then its next step, description and sessions, if any. */
    private fun taskCard(t: TaskView): JComponent {
        val task = t.task
        val editing = open?.edit.let { it == PlanEdit.Next(task.id) || it == PlanEdit.Description(task.id) }
        val body = if (task.next == null && task.description == null && t.sessions.isEmpty() && !editing) null else card {
            indent {
                val next = PlanEdit.Next(task.id)
                if (open?.edit == next) row { inline(next) }
                else task.next?.let { row { comment("Next: $it", MAX_LINE_LENGTH_WORD_WRAP).selectable() } }
                markdown(PlanEdit.Description(task.id), task.description, null)
                for (session in t.sessions) row {
                    val unread = session.id in service.unread
                    icon(sessionIcon(service.live[session.id]?.status, unread))
                    link(session.title) { ClaudeSessionOpener.open(project, session) }.shrinkable().apply { if (unread) bold() }
                    comment(shortAge(session.lastMessage, Instant.now())).align(AlignX.RIGHT)
                }
            }
        }
        return Rounded(PAGE, JBUI.scale(TASK_ARC), squareTop = false).apply {
            layout = BorderLayout()
            add(taskHead(t, unfolded = true), BorderLayout.NORTH)
            body?.let { add(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(0, 4, 6, 4); add(it) }, BorderLayout.CENTER) }
        }
    }

    /** The statuses as toggles, the task's own selected, under the task's status icon: its status button and the Status of its menu. */
    private fun statusGroup(task: PlanTask) = DefaultActionGroup("Status", PlanStatus.entries.map { s ->
        object : DumbAwareToggleAction(s.label.replace('_', ' '), null, s.icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = s == task.status
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (s != task.status) change { it.editTask(task.id) { x -> x.copy(status = s) } }
            }
        }
    }).apply {
        isPopup = true
        templatePresentation.icon = task.status.icon
        templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
    }

    /** Every action on a task, its [status] among them, in one `⋯` menu beside its title. */
    private fun taskMenu(task: PlanTask, status: AnAction): AnAction {
        val plan = { PlanStart(folderId, task.id) }
        return more(
            status,
            Separator.getInstance(),
            NewSessionInDirAction("New Session", false, ::startDir, ::startDir, plan) { folderId },
            NewSessionInDirAction("New Session in Worktree...", true, ::startDir, ::startDir, plan) { folderId },
            Separator.getInstance(),
            action("Rename") { openEdit(PlanEdit.Title(task.id)) },
            action("Edit Next Step") { openEdit(PlanEdit.Next(task.id)) },
            action("Edit Description") { openEdit(PlanEdit.Description(task.id)) },
            Separator.getInstance(),
            action("Delete") { deleteTask(project, task) },
        )
    }

    /**
     * A task's head: its status as a button that changes it, id and title, then the most urgent of its sessions (a folded
     * task names its next step when it has none) and the menu of its actions ([taskMenu]). A folded task is this line alone.
     */
    private fun taskHead(t: TaskView, unfolded: Boolean): JComponent {
        val task = t.task
        val status = statusGroup(task)
        val next = task.next?.takeIf { !unfolded }?.let { commentLabel("Next: " + StringUtil.shortenTextWithEllipsis(it, NEXT_CHARS, 0)) }
        return head(task.id, buttons(status), title(PlanEdit.Title(task.id), task.title, bold = unfolded, grey = !unfolded && task.status.closed),
            activity(t.sessions).ifEmpty { listOfNotNull(next) } + buttons(taskMenu(task, status)))
    }

    /** A title, its text selectable; the field while its Rename is open. */
    internal fun title(edit: PlanEdit.Title, text: String, bold: Boolean = true, grey: Boolean = false): JComponent =
        if (open?.edit == edit) checkNotNull(open).component
        else SelectableTitle(text).apply {
            if (bold) font = font.deriveFont(Font.BOLD)
            if (grey) foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

    /** [markdown] as [markdownOrArea] shows it, the area while [edit] is open. */
    internal fun Panel.markdown(edit: PlanEdit, markdown: String?, empty: String?) =
        markdownOrArea(open?.takeIf { it.edit == edit }?.component, markdown, empty, colors, ::save, ::cancel)

    /** A `+ Add ...` link that opens [edit], the field while it is open. */
    internal fun Panel.addRow(edit: PlanEdit, text: String) = row {
        if (open?.edit == edit) inline(edit) else link(text) { openEdit(edit) }
    }

    private fun Row.inline(edit: PlanEdit) = cell(checkNotNull(open) { "no open edit for $edit" }.component).align(AlignX.FILL).resizableColumn()

    private fun Row.actions(vararg actions: AnAction) = cell(buttons(*actions))

    /** Flips section, milestone or task [id], rebuilding the card holding it. */
    private fun fold(id: String) {
        folding = folding.toggle(id)
        rebuild(listOf(if (id in cards) id else checkNotNull(view).milestones.first { m -> m.tasks.any { it.task.id == id } }.milestone.id))
    }

    private fun foldAll(open: Boolean) {
        folding = Folding.all(open, checkNotNull(view).plan)
        rebuild(cards.keys.toList())
    }

    /**
     * Builds the cards of the sections and milestones [ids] again in place, the rest of the panel kept as it is: a whole
     * rebuild lays out every Markdown text anew, whose height settles only on a second pass, and the panel flickers.
     */
    private fun rebuild(ids: List<String>) {
        val view = checkNotNull(view)
        for (id in ids) cards.getValue(id).apply {
            removeAll()
            val section = Section.entries.firstOrNull { it.id == id }
            add((if (section != null) sectionCard(section, view.plan) else milestoneCard(view.milestones.first { it.milestone.id == id })).also(PaneFont::growAll))
            revalidate()
            repaint()
        }
    }

    /** Where a session started on a task runs: as from the plan folder in the tree, else in the IDE project's directory. */
    private fun startDir(): Path = service.folders.startFolder(folderId)?.startDir?.let(Path::of) ?: projectBase(project)

    internal fun openEdit(edit: PlanEdit) {
        val text = checkNotNull(edit.current(checkNotNull(view).plan)) { "$edit names an item gone" }
        val component = if (edit.multiline) inlineArea(text, ::save, ::cancel) else inlineField(text, ::save, ::cancel)
        open = OpenEdit(edit, component)
        focusOpen = true
        render()
    }

    /** Applies the open edit; a blank title is refused and its field stays. A new item's field stays open, empty, for the next. */
    private fun save() {
        val open = checkNotNull(open) { "nothing to save" }
        val op = open.edit.apply(folderId, open.component.text, LocalDate.now()) ?: return
        if (open.edit.isNew) {
            open.component.text = ""
            focusOpen = true
        } else this.open = null
        change(op)
        render()
    }

    private fun cancel() {
        open = null
        render()
    }

    internal fun change(op: (SessionFolders) -> SessionFolders) = service.updateFolders(ChangeSource.UI, op)

    private companion object {
        const val GAP = 6
        const val PLAN_BAR = 160
        const val TASK_ARC = 12
        const val NEXT_CHARS = 48
        const val HIDE_CLOSED_KEY = "dev.myair.planView.hideClosed"
    }
}

/** The milestone or task an edit is about; null for a new milestone and the edits of the sections. */
private val PlanEdit.itemId: String?
    get() = when (this) {
        is PlanEdit.Title -> id
        is PlanEdit.Next -> taskId
        is PlanEdit.Description -> id
        is PlanEdit.NewTask -> milestoneId
        PlanEdit.NewMilestone, PlanEdit.Constraints, PlanEdit.NewDecision, PlanEdit.NewRequirement, PlanEdit.NewBlocker -> null
    }

/**
 * The progress of the tasks counted in [counts] ([statusCounts]): a thin bar [px] wide, a segment each for those done, in
 * progress, waiting and blocked, the pending ones left as track.
 */
private class ProgressLine(private val counts: Map<PlanStatus, Int>, private val px: Int = 60) : JComponent() {
    init {
        require(PlanStatus.CANCELED !in counts) { "canceled tasks counted: $counts" }
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(px), JBUI.scale(4))

    override fun getMaximumSize() = Dimension(preferredSize.width, Int.MAX_VALUE)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val h = JBUI.scale(4)
            val y = (height - h) / 2
            val track = RoundRectangle2D.Float(0f, y.toFloat(), width.toFloat(), h.toFloat(), h.toFloat(), h.toFloat())
            g2.color = TRACK
            g2.fill(track)
            g2.clip(track)
            val total = counts.values.sum()
            var x = 0
            var seen = 0
            for ((status, color) in SEGMENTS) {
                seen += counts[status] ?: continue
                val end = width * seen / total
                g2.color = color
                g2.fillRect(x, y, end - x, h)
                x = end
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val TRACK = JBColor(0xD5D8DE, 0x4A4D52)
        val SEGMENTS = listOf(
            PlanStatus.DONE to JBColor(0x4C8D5A, 0x5FA06C),
            PlanStatus.IN_PROGRESS to Color(0xD97757),
            PlanStatus.WAITING to NEEDS_INPUT_AMBER,
            PlanStatus.BLOCKED to JBColor(0xC75450, 0xDB5C5C),
        )
    }
}
