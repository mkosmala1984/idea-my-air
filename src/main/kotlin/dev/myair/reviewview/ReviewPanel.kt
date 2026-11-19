package dev.myair.reviewview

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.myair.actions.FileChange
import dev.myair.actions.Git
import dev.myair.actions.showBaseDiff
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.SessionPane
import dev.myair.planview.CardPage
import dev.myair.planview.HEAD_GAP
import dev.myair.planview.ID_COLUMN
import dev.myair.planview.OpenEdit
import dev.myair.planview.action
import dev.myair.planview.buttons
import dev.myair.planview.card
import dev.myair.planview.chevron
import dev.myair.planview.greyCard
import dev.myair.planview.group
import dev.myair.planview.head
import dev.myair.planview.holder
import dev.myair.planview.inlineArea
import dev.myair.planview.inlineField
import dev.myair.planview.markdownHtml
import dev.myair.planview.markdownOrArea
import dev.myair.planview.more
import dev.myair.planview.selectable
import dev.myair.prompt.Chip
import dev.myair.prompt.choose
import dev.myair.review.ReviewService
import dev.myair.review.Reviewers
import dev.myair.review.RunStatus
import dev.myair.review.acceptIfOpen
import dev.myair.review.addRun
import dev.myair.review.blockerCount
import dev.myair.review.precision
import dev.myair.review.rangeFor
import dev.myair.review.removeRun
import dev.myair.review.resolveQuestion
import dev.myair.review.reviewerArgs
import dev.myair.review.reviewerPrompt
import dev.myair.review.setJob
import dev.myair.review.setState
import dev.myair.review.toDecide
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.BranchReview
import dev.myair.store.Finding
import dev.myair.store.FindingState
import dev.myair.store.ReviewProfile
import dev.myair.store.ReviewProfileStore
import dev.myair.store.Run
import dev.myair.store.Severity
import dev.myair.toolwindow.PaneFont
import dev.myair.toolwindow.commentLabel
import dev.myair.toolwindow.durationText
import dev.myair.toolwindow.shortAge
import java.awt.BorderLayout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The review side of a review tab, in the plan panel's look: a header (branch, PR, reviewed commit, HEAD), then the grey
 * cards Summary, Explanation, Reviewers, Findings and Questions, each folding on a click on its head. Texts are edited
 * in place from the menus only ([ReviewEdit]); every change goes through [ReviewService.update] by id. [ReviewEditor]
 * hands it the review on every change; a card is built again only when what it shows changed.
 * [pane] is the manager's terminal, [managerRuns] whether its process runs, which Publish and Post to PR need.
 */
class ReviewPanel(
    private val project: Project, private val folderId: String, parent: Disposable, private val pane: () -> SessionPane?, private val managerRuns: () -> Boolean,
) : CardPage(parent) {
    private enum class Card { HEADER, SUMMARY, EXPLANATION, REVIEWERS, FINDINGS, QUESTIONS }

    private val reviews = ReviewService.getInstance()
    private val service = ClaudeSessionsService.getInstance()
    private var result: Result<BranchReview>? = null
    private var filter = FindingFilter.all
    private var open: OpenEdit<ReviewEdit>? = null
    /** The open edit's component takes the focus on the next build: it was just opened. */
    private var focusOpen = false
    /** Cards and the resolved questions ([RESOLVED]) folded or unfolded against their default. */
    private var toggled = emptySet<String>()
    /** Why a reviewer's job did not start at the last Run, by reviewer name; cleared by the next Run. */
    private var startErrors = emptyMap<String, String>()
    private var profileName: String?
        get() = PropertiesComponent.getInstance().getValue(PROFILE_KEY)
        set(value) = PropertiesComponent.getInstance().setValue(PROFILE_KEY, value)
    /** The holder of each card, built once per review shown; empty while the error card shows. */
    private val cards = mutableMapOf<Card, JPanel>()
    /** What each card was last built from. */
    private val built = mutableMapOf<Card, Any?>()

    fun show(result: Result<BranchReview>) {
        this.result = result
        render()
    }

    override fun render(force: Boolean) {
        val review = (result ?: return).getOrElse { return showError(it) }
        open = open?.takeIf { it.edit.current(review) != null }
        val git = reviews.facts[folderId]
        val view = reviewViewOf(review, git, reviews.liveJobs, precision(reviews.readable), filter, service.sessions.associateBy { it.id })
        val profiles = ReviewProfileStore.INSTANCE.read()
        val now = Instant.now()
        val shown = mapOf(
            Card.HEADER to listOf(view.header, git?.error),
            Card.SUMMARY to listOf(view.summary, open?.edit == ReviewEdit.Summary),
            Card.EXPLANATION to listOf(view.chapters, view.unexplained, git?.state?.problem),
            // Not whole sessions: a working reviewer's transcript changes them on every refresh.
            Card.REVIEWERS to listOf(view.runs.map { listOf(it.run, it.status, it.findings, it.precision, it.session?.id, it.session?.title, runTime(it.run, it.status, now)) }, profiles, profileName, startErrors, git?.head, git?.state?.mergeBase, git?.sinceReviewed),
            Card.FINDINGS to listOf(view.findings, filter, review.findings.map { it.state to it.severity }, open?.edit?.takeIf { it != ReviewEdit.Summary }),
            Card.QUESTIONS to listOf(view.questions, review.questions.filter { it.resolved }),
        ).mapValues { (card, v) -> listOf(v, card.name in toggled, if (card == Card.QUESTIONS) RESOLVED in toggled else null) }
        val changed = if (force || cards.isEmpty()) Card.entries else Card.entries.filter { shown[it] != built[it] }
        if (changed.isEmpty() && !focusOpen) return
        val focused = open?.component?.isFocusOwner == true
        if (cards.isEmpty()) {
            removeAll()
            add(JPanel(VerticalLayout(JBUI.scale(GAP))).apply {
                isOpaque = false
                Card.entries.forEach { add(holder(JPanel()).also { h -> cards[it] = h }) }
            }, BorderLayout.NORTH)
        }
        for (card in changed) {
            built[card] = shown[card]
            cards.getValue(card).apply {
                removeAll()
                add(build(card, review, view, profiles).also(PaneFont::growAll))
                revalidate()
                repaint()
            }
        }
        open?.component?.takeIf { focused || focusOpen }?.let { IdeFocusManager.getInstance(project).requestFocus(it, true) }
        focusOpen = false
    }

    /** Instead of every card: why `review.json` cannot be read, naming the file. */
    private fun showError(e: Throwable) {
        cards.clear()
        built.clear()
        removeAll()
        add(greyCard(card {
            row { label("Cannot read ${reviews.file(folderId)}").applyToComponent { font = JBFont.label().asBold() } }
            row { comment(StringUtil.escapeXmlEntities(e.toString()), MAX_LINE_LENGTH_WORD_WRAP) }
        }), BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    private fun build(card: Card, review: BranchReview, view: ReviewView, profiles: Result<List<ReviewProfile>>): JComponent = when (card) {
        Card.HEADER -> greyCard(header(view.header))
        Card.SUMMARY -> section(card, "Summary", emptyList(), more(
            action("Edit Summary") { openEdit(ReviewEdit.Summary) },
            action("Publish to PR", { pr() != null && managerRuns() }) {
                send("Set the description of PR #${checkNotNull(pr())} to the review's summary (my_air_review_get).")
            },
        )) { listOf(card { markdownOrArea(openArea(ReviewEdit.Summary), view.summary, "No summary yet.", colors, ::save, ::cancel) }) }
        Card.EXPLANATION -> section(card, "Explanation", listOf("${view.chapters.size} chapters", "${view.unexplained.size} not explained"), null) { explanation(view) }
        Card.REVIEWERS -> section(card, "Reviewers", emptyList(), null, reviewersTrail(profiles)) { reviewers(view, profiles) }
        Card.FINDINGS -> section(card, "Findings", listOf("${blockerCount(review)} blockers", "${toDecide(review)} to decide"), null) { findings(review, view) }
        Card.QUESTIONS -> section(card, "Questions", listOf("${view.questions.size} open"), null) { questions(review, view) }
    }

    /** A grey card: a head with its chevron, [name], [summary] and [trail] or a `...` [menu], then [body] while unfolded. */
    private fun section(card: Card, name: String, summary: List<String>, menu: DefaultActionGroup?, trail: List<JComponent> = emptyList(), body: () -> List<JComponent>): JComponent {
        val unfolded = card.name !in toggled
        val title = JBLabel(name).apply { font = JBFont.label().asBold() }
        val rows = JPanel(VerticalLayout(0)).apply {
            isOpaque = false
            add(head(chevron(unfolded), title, listOfNotNull(summary.takeIf { it.isNotEmpty() }?.let { commentLabel(it.joinToString(" · ")) }) + trail + listOfNotNull(menu?.let { buttons(it) }), null) { toggle(card.name) })
            // A section's items start under its name, as a plan section's do.
            if (unfolded) body().forEach { add(it.apply { border = JBUI.Borders.emptyLeft(ID_COLUMN) }) }
        }
        return greyCard(rows)
    }

    private fun header(h: Header) = card {
        row {
            label("${h.branch} → ${h.base}").applyToComponent { font = JBFont.h3().asBold() }
            h.pr?.let { pr -> if (h.prUrl != null) link("PR #$pr") { BrowserUtil.browse(h.prUrl) } else label("PR #$pr") }
        }
        row {
            comment(when {
                h.reviewed == null -> "Not reviewed yet"
                h.reviewedGone -> "Reviewed ${h.reviewed.take(SHA)}, a commit no longer in the history"
                else -> "Reviewed ${h.reviewed.take(SHA)}"
            })
            h.head?.let { comment("HEAD ${it.take(SHA)}") }
            h.sinceReviewed?.let { comment(if (it == 0) "HEAD is the reviewed commit" else "$it commits past it") }
        }
        reviews.facts[folderId]?.error?.let { row { comment(StringUtil.escapeXmlEntities("Git: $it"), MAX_LINE_LENGTH_WORD_WRAP) } }
    }

    private fun explanation(view: ReviewView): List<JComponent> {
        val problem = reviews.facts[folderId]?.state?.problem
        return view.chapters.map { c ->
            card {
                row { label(c.chapter.title).applyToComponent { font = JBFont.label().asBold() } }
                row { text(markdownHtml(c.chapter.text, colors), MAX_LINE_LENGTH_WORD_WRAP).selectable() }
                c.files.forEach { f -> fileRow(f.path, f.change, f.unchanged) }
            }
        } + listOfNotNull(
            view.unexplained.takeIf { it.isNotEmpty() }?.let { files ->
                card {
                    row { label("Not explained").applyToComponent { font = JBFont.label().asBold() } }
                    files.forEach { fileRow(it.path, it, false) }
                }
            },
            problem?.let { p -> card { row { comment(StringUtil.escapeXmlEntities(p), MAX_LINE_LENGTH_WORD_WRAP) } } },
        )
    }

    /** A file of the explanation: a link to its base diff with `+added −removed`, grey "not changed" when [unchanged]. */
    private fun Panel.fileRow(path: String, change: FileChange?, unchanged: Boolean) = row {
        when {
            change != null -> {
                link(path) { openDiff(path) }
                comment("+${change.added} −${change.removed}")
            }
            unchanged -> comment("$path · not changed")
            else -> label(path)
        }
    }

    private fun openDiff(path: String) {
        val state = checkNotNull(reviews.facts[folderId]?.state) { "git of review $folderId not read" }
        showBaseDiff(project, state, checkNotNull(state.changes).indexOfFirst { it.path == path }.also { check(it >= 0) { "$path is not changed" } })
    }

    private fun changed(path: String) = reviews.facts[folderId]?.state?.changes.orEmpty().any { it.path == path }

    /** The profile chooser and Run, which offers New commits only and Whole branch when HEAD moved past the reviewed commit (R27). */
    private fun reviewersTrail(profiles: Result<List<ReviewProfile>>): List<JComponent> {
        val list = profiles.getOrNull()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val profile = list.firstOrNull { it.name == profileName } ?: list.first()
        val chip = Chip(null) { choose(it, list, profile, ReviewProfile::name) { p -> profileName = p.name; render() } }.apply { text = profile.name }
        val git = reviews.facts[folderId]
        val ready = { git?.head != null && git.state?.mergeBase != null }
        val run = if ((git?.sinceReviewed ?: 0) > 0) DefaultActionGroup("Run", listOf(
            action("New commits only", ready) { run(profile, newOnly = true) },
            action("Whole branch", ready) { run(profile, newOnly = false) },
        )).apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.Execute
            templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        } else action("Run", ready) { run(profile, newOnly = false) }.apply { templatePresentation.icon = AllIcons.Actions.Execute }
        return listOf(chip, buttons(run))
    }

    /**
     * Records a run per reviewer of [profile] ([addRun]), then starts its job off the EDT and stores the job id
     * ([setJob]); a start that throws takes its run away again ([removeRun]), as it would count as starting for good,
     * and leaves its message on the reviewer's row until the next Run.
     */
    private fun run(profile: ReviewProfile, newOnly: Boolean) {
        val git = checkNotNull(reviews.facts[folderId]) { "git of review $folderId not read" }
        val review = reviews.review(folderId)
        val range = rangeFor(review, checkNotNull(git.state?.mergeBase), checkNotNull(git.head), newOnly)
        val started = Instant.now().toString()
        startErrors = emptyMap()
        for (reviewer in profile.reviewers) {
            lateinit var runId: String
            reviews.update(folderId) { r -> r.nextId("W").let { (id, next) -> runId = id; next.addRun(Run(id, reviewer.name, null, null, range, started)) } }
            val args = reviewerArgs(reviewer, reviewerPrompt(folderId, runId, review, range))
            ApplicationManager.getApplication().executeOnPooledThread {
                val job = try {
                    Result.success(Reviewers.start(review.dir, args))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                ApplicationManager.getApplication().invokeLater {
                    job.fold({ id -> reviews.update(folderId) { it.setJob(runId, id) } }) { e ->
                        startErrors = startErrors + (reviewer.name to (e.message ?: e.toString()))
                        reviews.update(folderId) { it.removeRun(runId) }
                    }
                }
            }
        }
    }

    private fun reviewers(view: ReviewView, profiles: Result<List<ReviewProfile>>): List<JComponent> {
        val file = ReviewProfileStore.INSTANCE.file
        val note = profiles.fold({ if (it.isEmpty()) "No profiles in $file" else null }) { "Cannot read $file: $it" }
        val now = Instant.now()
        return listOfNotNull(note?.let { n -> card { row { comment(StringUtil.escapeXmlEntities(n), MAX_LINE_LENGTH_WORD_WRAP) } } }) +
            startErrors.map { (name, message) ->
                card { row { label(name); comment(StringUtil.escapeXmlEntities("did not start: $message"), MAX_LINE_LENGTH_WORD_WRAP).applyToComponent { foreground = UIUtil.getErrorForeground() } } }
            } +
            view.runs.groupBy { it.run.started }.map { (started, runs) ->
                card {
                    row { comment("Run at " + TIME.format(Instant.parse(started))) }
                    for (r in runs) row {
                        label(r.status.text).applyToComponent { if (r.status == RunStatus.failed || r.status == RunStatus.stopped) foreground = UIUtil.getErrorForeground() }
                        val session = r.session
                        if (session != null) link(r.run.reviewer) { ClaudeSessionOpener.open(project, session) } else label(r.run.reviewer)
                        comment(listOfNotNull(
                            "${r.findings} findings", runTime(r.run, r.status, now), r.precision?.let { "${it.kept}/${it.triaged} · ${it.reviews} reviews" },
                        ).joinToString(" · "))
                    }
                }
            }
    }

    /** How long a reported run took; how long ago a running one started; nothing for a failed one. */
    private fun runTime(run: Run, status: RunStatus, now: Instant): String? = when (status) {
        RunStatus.reported -> durationText(Duration.between(Instant.parse(run.started), Instant.parse(checkNotNull(run.reported))))
        RunStatus.running, RunStatus.waiting -> "started " + shortAge(Instant.parse(run.started), now)
        RunStatus.stopped, RunStatus.failed -> null
    }

    private fun findings(review: BranchReview, view: ReviewView): List<JComponent> {
        val chips = group(FindingFilter.entries.map { f ->
            Chip(null) { filter = f; render() }.apply {
                text = "${f.name.replaceFirstChar(Char::uppercase)} ${review.findings.count(f::matches)}"
                chevron = false
                selected = f == filter
            }
        })
        return listOf(chips) + view.findings.flatMap(::findingRows)
    }

    /** A finding's line, and under it the field of its reason or commit while that edit is open. */
    private fun findingRows(v: FindingView): List<JComponent> {
        val f = v.finding
        val dim = v.maybeFixed
        val text = JBLabel("${f.file}:${f.line}  ${f.reports.last().summary}").apply {
            if (dim) foreground = UIUtil.getContextHelpForeground()
            toolTipText = tooltip(f)
        }
        val line = JPanel(BorderLayout(JBUI.scale(HEAD_GAP), 0)).apply {
            isOpaque = false
            add(group(listOf(JBLabel(f.severity.name).apply { foreground = if (f.severity == Severity.blocker) UIUtil.getErrorForeground() else foreground }, commentLabel(f.id))), BorderLayout.WEST)
            add(text)
            add(group(listOfNotNull(
                if (dim) commentLabel("maybe fixed") else null,
                commentLabel("${v.agreeing}/${v.ran}"),
                JBLabel(f.state.name),
                buttons(findingMenu(f)),
            )), BorderLayout.EAST)
        }
        val edit = open?.takeIf { it.edit == ReviewEdit.Reason(f.id) || it.edit == ReviewEdit.Commit(f.id) } ?: return listOf(line)
        val label = if (edit.edit is ReviewEdit.Reason) "Reason:" else "Fix commit:"
        return listOf(line, card { row(label) { cell(edit.component).resizableColumn().align(AlignX.FILL) } })
    }

    private fun tooltip(f: Finding) = "<html>" + StringUtil.escapeXmlEntities(f.reports.last().failureScenario) + "<br><br>" +
        f.reports.joinToString("<br>") { r -> StringUtil.escapeXmlEntities("${r.reviewer} (${r.run}, ${r.commit.take(SHA)}) line ${r.line}, ${r.severity}: ${r.summary}") } + "</html>"

    private fun findingMenu(f: Finding) = more(
        action("Accept") { stateTo(f, FindingState.accepted) },
        action("Reject...") { openEdit(ReviewEdit.Reason(f.id)) },
        action("Mark Fixed...") { openEdit(ReviewEdit.Commit(f.id)) },
        action("Reopen", { f.state != FindingState.open }) { stateTo(f, FindingState.open) },
        Separator.getInstance(),
        action("Fix with Claude") {
            val dir = reviews.review(folderId).dir
            ClaudeSessionOpener.startNew(project, Git.mainCheckout(dir), dir, args = listOf("--", findingMessage(f)), folderId = folderId)
            reviews.update(folderId) { it.acceptIfOpen(f.id) }
        },
        action("Post to PR", { pr() != null && managerRuns() }) {
            send("Post finding ${f.id} of the review (my_air_review_get) as a comment on PR #${checkNotNull(pr())} at ${f.file} line ${f.line}, then set it posted with the comment id (my_air_review_apply).")
        },
        action("Open Diff", { changed(f.file) }) { openDiff(f.file) },
    )

    /** The PR as stored now: the cards holding the actions are not built again when only the PR changes. */
    private fun pr() = reviews.review(folderId).pr

    private fun stateTo(f: Finding, state: FindingState) {
        reviews.update(folderId) { it.setState(f.id, state) }
    }

    private fun questions(review: BranchReview, view: ReviewView): List<JComponent> {
        val resolved = review.questions.filter { it.resolved }
        val open = view.questions.map { q ->
            card {
                row {
                    text(StringUtil.escapeXmlEntities(q.text), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().selectable()
                    q.reviewer?.let { comment(it) }
                    link("Resolve") { reviews.update(folderId) { it.resolveQuestion(q.id) } }
                }
            }
        }
        if (resolved.isEmpty()) return open
        val unfolded = RESOLVED in toggled
        val head = head(chevron(unfolded), commentLabel("${resolved.size} resolved"), emptyList(), null) { toggle(RESOLVED) }
        return open + head + if (unfolded) resolved.map { q -> card { row { comment(StringUtil.escapeXmlEntities(q.text), MAX_LINE_LENGTH_WORD_WRAP) } } } else emptyList()
    }

    private fun toggle(id: String) {
        toggled = if (id in toggled) toggled - id else toggled + id
        render()
    }

    private fun send(text: String) = check(checkNotNull(pane()) { "the review has no manager pane" }.send(text)) { "the manager's terminal shows no process" }

    private fun openArea(edit: ReviewEdit) = open?.takeIf { it.edit == edit }?.component

    private fun openEdit(edit: ReviewEdit) {
        val text = checkNotNull(edit.current(reviews.review(folderId))) { "$edit names a finding gone" }
        open = OpenEdit(edit, if (edit.multiline) inlineArea(text, ::save, ::cancel) else inlineField(text, ::save, ::cancel))
        focusOpen = true
        render()
    }

    /** Applies the open edit; a blank reason or commit is refused and its field stays. */
    private fun save() {
        val open = checkNotNull(open) { "nothing to save" }
        val op = open.edit.apply(open.component.text) ?: return
        this.open = null
        reviews.update(folderId, op)
        // update notifies only when the review changed; the field closes either way.
        render()
    }

    private fun cancel() {
        open = null
        render()
    }

    private companion object {
        const val GAP = 6
        const val SHA = 8
        const val RESOLVED = "resolved"
        const val PROFILE_KEY = "dev.myair.reviewView.profile"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
