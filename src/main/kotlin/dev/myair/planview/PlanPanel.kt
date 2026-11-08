package dev.myair.planview

import com.intellij.icons.AllIcons
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.JBUI
import dev.myair.actions.NewSessionInDirAction
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.PlanStart
import dev.myair.editor.projectBase
import dev.myair.editor.sessionIcon
import dev.myair.folders.ChangeSource
import dev.myair.folders.PlanStatus
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import dev.myair.toolwindow.ContextCard
import dev.myair.toolwindow.PaneFont
import dev.myair.toolwindow.deleteMilestone
import dev.myair.toolwindow.deleteTask
import dev.myair.toolwindow.icon
import dev.myair.toolwindow.shortAge
import dev.myair.toolwindow.shrinkable
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The plan side of a plan tab: the plan's description, then a card per milestone with its tasks and the sessions working
 * on each, every text edited in place ([PlanEdit]). [PlanEditor] hands it each new [PlanView]; it rebuilds only when what
 * it shows changed, keeping an open edit's text component.
 */
class PlanPanel(private val project: Project, private val folderId: String, parent: Disposable) : JPanel(BorderLayout()), Scrollable {
    private val service = ClaudeSessionsService.getInstance()
    private var view: PlanView? = null
    private var open: OpenEdit? = null
    /** The open edit's component takes the focus on the next build: it was just opened, or saved and left open empty. */
    private var focusOpen = false
    /** What the panel was last built from. */
    private var rendered: Any? = null

    init {
        border = JBUI.Borders.empty(CARD_GAP)
        PaneFont.subscribe(parent) { render(force = true) }
    }

    fun show(view: PlanView) {
        this.view = view
        render()
    }

    private fun render(force: Boolean = false) {
        val view = view ?: return
        // A task or milestone the manager deleted meanwhile takes its open edit along.
        open = open?.takeIf { it.edit.current(view.folder.plan!!) != null }
        val sessions = view.milestones.flatMap { m -> m.tasks.flatMap { it.sessions } }
        val now = Instant.now()
        // What the panel shows, not whole sessions: a working session's transcript changes them on every refresh.
        val shown = listOf(view.folder.plan, open?.edit, view.milestones.map { m -> m.tasks.map { t -> t.sessions.map { it.id } } },
            sessions.map { listOf(it.title, service.live[it.id]?.status, it.id in service.unread, shortAge(it.lastMessage, now)) })
        if (!force && shown == rendered && !focusOpen) return
        rendered = shown
        val focused = open?.component?.isFocusOwner == true
        val content = JPanel(VerticalLayout(JBUI.scale(CARD_GAP))).apply {
            isOpaque = false
            add(ContextCard(card { description(view) }))
            view.milestones.forEach { add(ContextCard(card { milestone(it) })) }
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

    private fun card(init: Panel.() -> Unit) = panel(init).apply { isOpaque = false }

    private fun Panel.description(view: PlanView) {
        row {
            label("Description").bold()
            if (open?.edit != PlanEdit.Description(null)) link("Edit") { openEdit(PlanEdit.Description(null)) }.align(AlignX.RIGHT)
        }
        markdown(PlanEdit.Description(null), view.description, "No description")
    }

    private fun Panel.milestone(m: MilestoneView) {
        val milestone = m.milestone
        row {
            title(PlanEdit.Title(milestone.id), "${milestone.id} ${milestone.title}")
            comment(m.progress)
            checkBox("done").applyToComponent {
                isSelected = milestone.done
                addActionListener { change { it.editMilestone(milestone.id) { x -> x.copy(done = isSelected) } } }
            }
            actions(more(action("Edit Description") { openEdit(PlanEdit.Description(milestone.id)) }, action("Delete") { deleteMilestone(project, milestone) }))
        }
        markdown(PlanEdit.Description(milestone.id), milestone.description, null)
        indent {
            m.tasks.forEach { task(it) }
            addRow(PlanEdit.NewTask(milestone.id), "+ Add task")
        }
    }

    private fun Panel.task(t: TaskView) {
        val task = t.task
        row {
            icon(task.status.icon)
            title(PlanEdit.Title(task.id), "${task.id} ${task.title}")
            comboBox(PlanStatus.entries, listCellRenderer {
                val status = checkNotNull(value) { "no status selected" }
                icon(status.icon)
                text(status.label.replace('_', ' '))
            }).applyToComponent {
                selectedItem = task.status
                // After the selection above, so a rebuild changes nothing.
                addActionListener { (selectedItem as PlanStatus).takeIf { it != task.status }?.let { s -> change { it.editTask(task.id) { x -> x.copy(status = s) } } } }
            }
            val plan = { PlanStart(folderId, task.id) }
            actions(
                NewSessionInDirAction("New Session", false, ::startDir, ::startDir, plan) { folderId },
                NewSessionInDirAction("New Session in Worktree...", true, ::startDir, ::startDir, plan) { folderId },
                more(
                    action("Edit Next Step") { openEdit(PlanEdit.Next(task.id)) },
                    action("Edit Description") { openEdit(PlanEdit.Description(task.id)) },
                    action("Delete") { deleteTask(project, task) },
                ),
            )
        }
        indent {
            val next = PlanEdit.Next(task.id)
            if (open?.edit == next) row { inline(next) }
            else task.next?.let { row { comment("Next: $it", MAX_LINE_LENGTH_WORD_WRAP).applyToComponent { clickable { openEdit(next) } } } }
            markdown(PlanEdit.Description(task.id), task.description, null)
            for (session in t.sessions) row {
                val unread = session.id in service.unread
                icon(sessionIcon(service.live[session.id]?.status, unread))
                link(session.title) { ClaudeSessionOpener.open(project, session) }.shrinkable().apply { if (unread) bold() }
                comment(shortAge(session.lastMessage, Instant.now())).align(AlignX.RIGHT)
            }
        }
    }

    /** A title that turns into a field on a click; the field while its edit is open. */
    private fun Row.title(edit: PlanEdit.Title, text: String) {
        if (open?.edit == edit) inline(edit)
        else label(text).bold().shrinkable().applyToComponent {
            toolTipText = "Click to rename"
            clickable { openEdit(edit) }
        }
    }

    /** [markdown] rendered, else [empty] in grey (nothing when null); the text area with Save and Cancel while its edit is open. */
    private fun Panel.markdown(edit: PlanEdit.Description, markdown: String?, empty: String?) {
        if (open?.edit == edit) {
            row { cell(JBScrollPane(checkNotNull(open).component)).align(AlignX.FILL) }
            row {
                button("Save") { save() }
                button("Cancel") { cancel() }
            }
        } else if (markdown != null) row { text(convertMarkdownToHtml(markdown), MAX_LINE_LENGTH_WORD_WRAP) }
        else if (empty != null) row { comment(empty) }
    }

    /** A `+ Add ...` link that opens [edit], the field while it is open. */
    private fun Panel.addRow(edit: PlanEdit, text: String) = row {
        if (open?.edit == edit) inline(edit) else link(text) { openEdit(edit) }
    }

    private fun Row.inline(edit: PlanEdit) = cell(checkNotNull(open) { "no open edit for $edit" }.component).align(AlignX.FILL).resizableColumn()

    /** [actions] as toolbar buttons, which update them and give them the events a toolbar does, as the worktree popup needs. */
    private fun Row.actions(vararg actions: AnAction) = cell(
        ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, DefaultActionGroup(*actions), true).apply {
            targetComponent = this@PlanPanel
            setReservePlaceAutoPopupIcon(false)
            component.isOpaque = false
            component.border = JBUI.Borders.empty()
        }.component,
    )

    /** The `⋯` menu. */
    private fun more(vararg actions: AnAction) = DefaultActionGroup("More", actions.toList()).apply {
        isPopup = true
        templatePresentation.icon = AllIcons.Actions.More
    }

    private fun action(text: String, run: () -> Unit) = DumbAwareAction.create(text) { run() }

    private fun JComponent.clickable(onClick: () -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }

    /** Where a session started on a task runs: as from the plan folder in the tree, else in the IDE project's directory. */
    private fun startDir(): Path = service.folders.startFolder(folderId)?.startDir?.let(Path::of) ?: projectBase(project)

    private fun openEdit(edit: PlanEdit) {
        val text = checkNotNull(edit.current(checkNotNull(view).folder.plan!!)) { "$edit names an item gone" }
        val component = if (edit is PlanEdit.Description) inlineArea(text, ::save, ::cancel) else inlineField(text, ::save, ::cancel)
        open = OpenEdit(edit, component)
        focusOpen = true
        render()
    }

    /** Applies the open edit; a blank title is refused and its field stays. A new item's field stays open, empty, for the next. */
    private fun save() {
        val open = checkNotNull(open) { "nothing to save" }
        val op = open.edit.apply(folderId, open.component.text) ?: return
        if (open.edit is PlanEdit.NewTask || open.edit == PlanEdit.NewMilestone) {
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

    private fun change(op: (SessionFolders) -> SessionFolders) = service.updateFolders(ChangeSource.UI, op)

    // The panel takes the width of its scroll pane, never scrolling sideways, as the context pane does.
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width

    private companion object {
        const val CARD_GAP = 4
        const val TOOLBAR_PLACE = "MyAirPlanView"
    }
}
