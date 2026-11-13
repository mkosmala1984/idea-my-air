package dev.myair.dashboard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.sessionIcon
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.LatestRead
import dev.myair.toolwindow.shortAge
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class DashboardEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is DashboardVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DashboardEditor(project, file as DashboardVirtualFile)
    override fun getEditorTypeId() = "myair-dashboard"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The sessions dashboard: Now, Unfinished by area, Finished folded. Rebuilt on every service change and whenever git
 * facts land; the facts are read off the EDT ([GitFacts]) for the sessions neither in Now nor settled.
 */
class DashboardEditor(private val project: Project, private val file: DashboardVirtualFile) : UserDataHolderBase(), FileEditor {
    private val service = ClaudeSessionsService.getInstance()
    private val facts = GitFacts()
    // The landed facts with the session each was read for: a session that wrote on since counts as not read (R15).
    private var git: Map<String, Pair<ClaudeSession, Result<SessionGit>>> = emptyMap()
    private var inFlight: List<ClaudeSession>? = null
    private var finishedOpen = false
    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16, 24)
        background = UIUtil.getEditorPaneBackground()
    }
    private val root = JBScrollPane(content).apply { border = JBUI.Borders.empty() }
    private val reads = LatestRead<List<ClaudeSession>, Map<String, Result<SessionGit>>>(
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Sessions dashboard git", 1),
        { ApplicationManager.getApplication().invokeLater(it) { project.isDisposed } },
        facts::read,
    ) { asked, result ->
        if (inFlight == asked) inFlight = null
        val facts = result.getOrElse { e -> throw IllegalStateException("dashboard git facts failed", e) }
        git = asked.filter { it.id in facts }.associate { it.id to (it to facts.getValue(it.id)) }
        rebuild()
    }

    init {
        service.subscribe(this, ::update)
        update()
    }

    private fun update() {
        val board = model()
        val asked = board.unfinished.flatMap { it.rows } + board.finished
        val sessions = asked.map { it.session }
        // A read of the same sessions still under way answers this change too; queueing another would only delay it.
        if (sessions != inFlight) {
            inFlight = sessions
            reads.request(sessions)
        }
        rebuild(board)
    }

    private fun model(): Dashboard {
        val listed = service.listed
        val current = listed.associateBy { it.id }
        val fresh = git.filterValues { (read, _) -> current[read.id]?.let { it.lastMessage == read.lastMessage && it.changedFiles == read.changedFiles } == true }
        return dashboardOf(listed, service.live, service.pins.settled, service.unread, service.folders, fresh.mapValues { it.value.second }, Instant.now())
    }

    private fun rebuild(board: Dashboard = model()) {
        val now = Instant.now()
        content.removeAll()
        content.add(line(JBLabel("Sessions").apply { font = JBUI.Fonts.label(20f).asBold() }, grey("${board.recent} in the last 2 days · ${board.older} older")))
        section("Now", board.now.size)
        board.now.forEach { content.add(row(it, emptyList(), now)) }
        section("Unfinished", board.unfinished.sumOf { it.rows.size })
        board.unfinished.forEach { area ->
            content.add(line(JBLabel(area.name).apply { font = font.deriveFont(java.awt.Font.BOLD) }).apply { border = JBUI.Borders.emptyTop(8) })
            area.rows.forEach { content.add(row(it.session, it.chips, now)) }
        }
        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        val toggle = ActionLink((if (finishedOpen) "▾ " else "▸ ") + "Finished (${board.finished.size})") { finishedOpen = !finishedOpen; rebuild() }
        content.add(line(toggle))
        if (finishedOpen) {
            val ids = board.finished.map { it.session.id }
            if (ids.isNotEmpty()) content.add(line(ActionLink("Settle all") {
                if (Messages.showYesNoDialog(project, "Settle ${ids.size} sessions?", "Settle Sessions", null) == Messages.YES) service.updatePins { it.settle(ids) }
            }))
            board.finished.forEach { content.add(row(it.session, it.chips, now)) }
        }
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun section(title: String, count: Int) {
        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        content.add(line(JBLabel(title).apply { font = JBUI.Fonts.label(15f).asBold() }, grey("$count")))
    }

    private fun grey(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun line(vararg parts: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
        isOpaque = false
        alignmentX = 0f
        parts.forEach(::add)
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun row(session: ClaudeSession, chips: List<Chip>, now: Instant): JComponent {
        val title = JBLabel(session.title, sessionIcon(service.live[session.id]?.status, session.id in service.unread), JBLabel.LEFT)
        val chipLabels = chips.map { chip ->
            JBLabel(chipText(chip)).apply {
                foreground = if (chip is Chip.GitError) JBColor.RED else UIUtil.getContextHelpForeground()
                if (chip is Chip.GitError) toolTipText = chip.message
            }
        }
        val settle = ActionLink("Settle") { service.updatePins { it.settle(listOf(session.id)) } }
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = 0f
            border = JBUI.Borders.empty(1, 12, 1, 0)
            add(line(title, *chipLabels.toTypedArray()), BorderLayout.CENTER)
            add(line(grey(shortAge(session.lastMessage, now)), settle), BorderLayout.EAST)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val open = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = ClaudeSessionOpener.open(project, session)
        }
        panel.addMouseListener(open)
        title.addMouseListener(open)
        return panel
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = root
    override fun getName() = "Sessions"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() = reads.forget()
}
