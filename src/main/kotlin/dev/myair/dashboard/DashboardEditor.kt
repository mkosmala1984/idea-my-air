package dev.myair.dashboard

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.sessionIcon
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.LatestRead
import dev.myair.store.Homes
import dev.myair.toolwindow.shortAge
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants

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
    private val pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("Sessions dashboard git", 1)
    private val store = AnalysisStore(Homes.myAir.resolve("analysis"))
    private var analyses: Map<String, Analysis> = emptyMap()
    private val failures = HashMap<String, String>()
    private val analysisPool = AppExecutorUtil.createBoundedApplicationPoolExecutor("Session analysis", 3)
    private var run: AnalysisRun? = null
    private var nothingToAnalyze = false
    private val daysBox = ComboBox(DAYS.map { it.first }.toTypedArray()).apply {
        val stored = PropertiesComponent.getInstance().getValue(DAYS_KEY, "14")
        selectedIndex = DAYS.indexOfFirst { (it.second?.toString() ?: "all") == stored }.also { check(it >= 0) { "unknown $DAYS_KEY $stored" } }
        addActionListener { PropertiesComponent.getInstance().setValue(DAYS_KEY, DAYS[selectedIndex].second?.toString() ?: "all") }
    }
    // Read on every change, as other IDEs write analyses too.
    private val storeReads = LatestRead<Unit, Map<String, Analysis>>(
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Session analyses store", 1), { ApplicationManager.getApplication().invokeLater(it) { project.isDisposed } }, { store.readAll() },
    ) { _, result ->
        analyses = result.getOrElse { e -> throw IllegalStateException("session analyses could not be read", e) }
        rebuild()
    }
    // Follows the viewport's width, so a long Left: line wraps instead of running off the right edge.
    private val content = object : JPanel(), Scrollable {
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } == true
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16, 24)
        background = UIUtil.getEditorPaneBackground()
    }
    private val root = JBScrollPane(content).apply { border = JBUI.Borders.empty() }
    private val reads = LatestRead<List<ClaudeSession>, Map<String, Result<SessionGit>>>(
        pool,
        { ApplicationManager.getApplication().invokeLater(it) { project.isDisposed } },
        { asked -> facts.read(asked) { partial -> landPartial(asked, partial) } },
    ) { asked, result ->
        if (inFlight == asked) inFlight = null
        val facts = result.getOrElse { e -> throw IllegalStateException("dashboard git facts failed", e) }
        git = asked.filter { it.id in facts }.associate { it.id to (it to facts.getValue(it.id)) }
        rebuild()
    }

    // Facts of a read still going on, gathered on the reader thread and landed together on the EDT, newest roots first.
    private val partials = HashMap<String, Result<SessionGit>>()
    private var partialsQueued = false

    private fun landPartial(asked: List<ClaudeSession>, partial: Map<String, Result<SessionGit>>) {
        if (partial.isEmpty()) return
        synchronized(partials) {
            partials += partial
            if (partialsQueued) return
            partialsQueued = true
        }
        ApplicationManager.getApplication().invokeLater({
            val landed = synchronized(partials) { HashMap(partials).also { partials.clear(); partialsQueued = false } }
            // Reads run one after another and land in order, so a read since asked lands after these; a session that
            // changed meanwhile is dropped by model()'s freshness check.
            git = git + asked.filter { it.id in landed }.associate { it.id to (it to landed.getValue(it.id)) }
            rebuild()
        }) { project.isDisposed }
    }

    init {
        service.subscribe(this, ::update)
        update()
    }

    private fun update() {
        nothingToAnalyze = false
        storeReads.request(Unit)
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
        return dashboardOf(listed, service.live, service.pins.settled, service.unread, service.folders, fresh.mapValues { it.value.second }, analyses, failures, Instant.now())
    }

    private fun rebuild(board: Dashboard = model()) {
        val now = Instant.now()
        content.removeAll()
        val running = run
        daysBox.isEnabled = running == null
        val analyze = ActionLink(if (running == null) "Analyze" else "Cancel") { toggleRun() }
        val progress = when {
            running != null -> grey("Analyzing ${running.done}/${running.total}")
            nothingToAnalyze -> grey("Nothing to analyze")
            else -> grey("")
        }
        content.add(line(
            JBLabel("Sessions").apply { font = JBUI.Fonts.label(20f).asBold() }, grey("${board.recent} in the last 2 days · ${board.older} older"),
            daysBox, analyze, progress,
        ))
        section("Now", board.now.size)
        board.now.forEach { content.add(row(it, emptyList(), now)) }
        section("Unfinished", board.unfinished.sumOf { it.rows.size })
        board.unfinished.forEach { area ->
            content.add(line(JBLabel(area.name).apply { font = font.deriveFont(java.awt.Font.BOLD) }).apply { border = JBUI.Borders.emptyTop(8) })
            area.rows.forEach { content.add(row(it.session, it.chips, now, it.analysis)) }
        }
        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        val toggle = ActionLink((if (finishedOpen) "▾ " else "▸ ") + "Finished (${board.finished.size})") { finishedOpen = !finishedOpen; rebuild() }
        content.add(line(toggle))
        if (finishedOpen) {
            val ids = board.finished.map { it.session.id }
            if (ids.isNotEmpty()) content.add(line(ActionLink("Settle all") {
                if (Messages.showYesNoDialog(project, "Settle ${ids.size} sessions?", "Settle Sessions", null) == Messages.YES) service.updatePins { it.settle(ids) }
            }))
            board.finished.forEach { content.add(row(it.session, it.chips, now, it.analysis)) }
        }
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    /** Starts a run over [analysisCandidates] of the chosen days, or cancels the one going on. */
    private fun toggleRun() {
        run?.let { it.cancel(); return }
        val board = model()
        val candidates = analysisCandidates(board, DAYS[daysBox.selectedIndex].second, Instant.now())
        if (candidates.isEmpty()) {
            nothingToAnalyze = true
            rebuild(board)
            return
        }
        val rows = (board.unfinished.flatMap { it.rows } + board.finished).associateBy { it.session.id }
        val folders = service.folders
        fun later(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action) { project.isDisposed }
        lateinit var started: AnalysisRun
        started = AnalysisRun(candidates, { session ->
            val chips = rows.getValue(session.id).chips.filter { it !is Chip.AnalysisFailed && it !is Chip.Pending }
            analyzeSession(session, chips, folders.taskOf(session)?.second?.let { "${it.id} ${it.status.label}" })
        }, analysisPool, { session, result ->
            val stored = result.mapCatching { store.write(session.id, it); it }
            later {
                if (run !== started) return@later
                stored.fold({ analyses = analyses + (session.id to it); failures -= session.id }, { failures[session.id] = it.message ?: it.toString() })
                rebuild()
            }
        }) {
            later {
                if (run === started) run = null
                rebuild()
            }
        }
        run = started
        started.start()
        rebuild(board)
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

    private fun row(session: ClaudeSession, chips: List<Chip>, now: Instant, analysis: RowAnalysis = RowAnalysis.None): JComponent {
        val title = JBLabel(session.title, sessionIcon(service.live[session.id]?.status, session.id in service.unread), JBLabel.LEFT)
        val chipLabels = chips.map { chip ->
            JBLabel(chipText(chip)).apply {
                val error = when (chip) {
                    is Chip.GitError -> chip.message
                    is Chip.AnalysisFailed -> chip.message
                    else -> null
                }
                foreground = if (error != null) JBColor.RED else UIUtil.getContextHelpForeground()
                toolTipText = error
            }
        }
        val settle = ActionLink("Settle") { service.updatePins { it.settle(listOf(session.id)) } }
        // The height of a wrapped Left: line depends on the width, so the row's limit is asked for each layout.
        val panel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = 0f
            border = JBUI.Borders.empty(1, 12, 1, 0)
            add(line(title, *chipLabels.toTypedArray()), BorderLayout.CENTER)
            add(line(grey(shortAge(session.lastMessage, now)), settle), BorderLayout.EAST)
            (analysis as? RowAnalysis.Current)?.takeIf { it.verdict != Verdict.DONE }?.let {
                add(panel { row { comment("Left: ${it.left}", MAX_LINE_LENGTH_WORD_WRAP) } }.apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(0, 28, 4, 0)
                }, BorderLayout.SOUTH)
            }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val open = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = ClaudeSessionOpener.open(project, session)
        }
        panel.addMouseListener(open)
        title.addMouseListener(open)
        // The wrapped Left: line takes its own clicks; they open the session as the rest of the row does.
        (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH)?.let { south ->
            UIUtil.uiTraverser(south).forEach { it.addMouseListener(open) }
        }
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
    override fun dispose() {
        reads.forget()
        storeReads.forget()
        run?.cancel()
    }
}

private const val DAYS_KEY = "dev.myair.dashboard.analysisDays"

/** The choices of how far back an analysis run reaches, by the session's last message; null: all. */
private val DAYS = listOf("7 days" to 7, "14 days" to 14, "30 days" to 30, "All" to null)
