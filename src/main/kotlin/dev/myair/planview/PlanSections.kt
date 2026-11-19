package dev.myair.planview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.myair.folders.Blocker
import dev.myair.folders.Decision
import dev.myair.folders.EvalKind
import dev.myair.folders.Plan
import dev.myair.folders.Requirement
import dev.myair.folders.RequirementStatus
import dev.myair.folders.ReviewStatus
import dev.myair.toolwindow.Chip
import dev.myair.toolwindow.commentLabel
import java.awt.BorderLayout
import java.awt.Color
import java.time.LocalDate
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The grey card of [section] of [plan]: a head with its chevron, name, a summary and a `...` menu, folding on a click as
 * a milestone's; open per [Folding] or while one of its edits is ([PlanEdit.section]).
 */
internal fun PlanPanel.sectionCard(section: Section, plan: Plan): JComponent {
    val unfolded = folding.open(section, plan) || open?.edit?.section == section
    val name = JBLabel(section.title).apply { font = JBFont.label().asBold() }
    val rows = JPanel(VerticalLayout(0)).apply {
        isOpaque = false
        add(head(section.id, chevron(unfolded), name, summary(section, plan).map(::commentLabel) + buttons(sectionMenu(section, plan)), label = null))
        if (unfolded) body(section, plan).forEach(::add)
    }
    return greyCard(rows)
}

private val Section.title
    get() = when (this) {
        Section.IDEAL -> "Ideal state"
        Section.CONSTRAINTS -> "Constraints"
        Section.DECISIONS -> "Decisions"
        Section.REQUIREMENTS -> "Requirements"
        Section.REVIEW -> "Code review"
    }

private fun summary(section: Section, plan: Plan): List<String> = when (section) {
    Section.IDEAL, Section.CONSTRAINTS -> emptyList()
    Section.DECISIONS -> listOfNotNull(plan.decisions.size.takeIf { it > 0 }?.toString())
    Section.REQUIREMENTS -> listOfNotNull(requirementsSummary(plan.requirements))
    Section.REVIEW -> listOf(reviewSummary(plan.review))
}

private fun PlanPanel.sectionMenu(section: Section, plan: Plan) = when (section) {
    Section.IDEAL -> more(action("Edit Ideal State") { openEdit(PlanEdit.Description(null)) })
    Section.CONSTRAINTS -> more(action("Edit Constraints") { openEdit(PlanEdit.Constraints) })
    Section.DECISIONS -> more(action("Add Decision") { openEdit(PlanEdit.NewDecision) })
    Section.REQUIREMENTS -> more(action("Add Requirement") { openEdit(PlanEdit.NewRequirement) })
    Section.REVIEW -> more(
        toggles("Status", ReviewStatus.entries, { it.label.replace('_', ' ') }, { it == (plan.review?.status ?: ReviewStatus.NOT_DONE) }) { s ->
            change { it.setReview(folderId, s, it.find(folderId)?.plan?.review?.commit, LocalDate.now()) }
        },
        action("Add Blocker") { openEdit(PlanEdit.NewBlocker) },
    )
}

private fun PlanPanel.body(section: Section, plan: Plan): List<JComponent> = when (section) {
    Section.IDEAL -> listOf(sectionText(PlanEdit.Description(null), plan.description, "+ Add ideal state"))
    Section.CONSTRAINTS -> listOf(sectionText(PlanEdit.Constraints, plan.constraints, "+ Add constraints"))
    Section.DECISIONS -> newestFirst(plan.decisions).map(::decisionRow) + addLine(PlanEdit.NewDecision, "+ Add decision")
    Section.REQUIREMENTS -> plan.requirements.flatMap(::requirementRows) + addLine(PlanEdit.NewRequirement, "+ Add requirement")
    Section.REVIEW -> listOf(reviewLine(plan)) + plan.review?.blockers.orEmpty().map(::blockerRow) + addLine(PlanEdit.NewBlocker, "+ Add blocker")
}

/** A Markdown text of the plan, the area while [edit] is open; without a text, a link opening [edit]. */
private fun PlanPanel.sectionText(edit: PlanEdit, markdown: String?, add: String) = indented(card {
    if (markdown == null && open?.edit != edit) row { link(add) { openEdit(edit) } } else markdown(edit, markdown, null)
})

private fun PlanPanel.addLine(edit: PlanEdit, text: String) = indented(card { addRow(edit, text) })

private fun indented(c: JComponent) = c.apply { border = JBUI.Borders.emptyLeft(ID_COLUMN) }

/**
 * An item of a section on one line: [lead], [body] taking the width left, then [trail]; the sides at the top when
 * [top], as beside a text of several lines, else centred.
 */
private fun PlanPanel.itemRow(lead: List<JComponent>, body: JComponent, trail: List<JComponent>, top: Boolean = false) =
    JPanel(BorderLayout(JBUI.scale(HEAD_GAP), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, ID_COLUMN, 2, HEAD_INSET)
        val side = { parts: List<JComponent> -> group(parts).let { if (top) JPanel(BorderLayout()).apply { isOpaque = false; add(it, BorderLayout.NORTH) } else it } }
        add(side(lead), BorderLayout.WEST)
        add(body)
        add(side(trail), BorderLayout.EAST)
    }

private fun PlanPanel.decisionRow(d: Decision): JComponent {
    val edit = PlanEdit.Title(d.id)
    val body = card { markdown(edit, d.text, null) }
    return itemRow(listOf(commentLabel(d.date), commentLabel(d.id)), body, listOf(buttons(more(
        action("Edit") { openEdit(edit) },
        action("Delete") { change { it.deleteEntry(folderId, d.id) } },
    ))), top = true)
}

/** A requirement's line, then its evidence, dim and read-only. */
private fun PlanPanel.requirementRows(r: Requirement): List<JComponent> {
    val line = itemRow(
        listOf(glyph(r.status.glyph, r.status.color), commentLabel(r.id)),
        title(PlanEdit.Title(r.id), r.text, bold = false),
        listOfNotNull(Chip(r.eval.label), r.tasks.takeIf { it.isNotEmpty() }?.let { commentLabel(it.joinToString(" ")) }, buttons(more(
            action("Edit") { openEdit(PlanEdit.Title(r.id)) },
            toggles("Eval", EvalKind.entries, { it.label }, { it == r.eval }) { k -> change { it.editRequirement(folderId, r.id) { x -> x.copy(eval = k) } } },
            action("Delete") { change { it.deleteEntry(folderId, r.id) } },
        ))),
    )
    if (r.evidence.isEmpty()) return listOf(line)
    val evidence = card { r.evidence.forEach { row { comment(StringUtil.escapeXmlEntities(it), MAX_LINE_LENGTH_WORD_WRAP) } } }
        .apply { border = JBUI.Borders.emptyLeft(ID_COLUMN + EVIDENCE_INDENT) }
    return listOf(line, evidence)
}

/** The review's status, then the commit and date it was set at, in grey. */
private fun PlanPanel.reviewLine(plan: Plan) = indented(card {
    row {
        label("Status: " + (plan.review?.status ?: ReviewStatus.NOT_DONE).label.replace('_', ' '))
        plan.review?.let { comment(listOfNotNull(it.commit, it.date).joinToString(" · ")) }
    }
})

private fun PlanPanel.blockerRow(b: Blocker) = itemRow(
    listOf(glyph(b.glyph, if (b.resolved) SUCCESS else UIUtil.getErrorForeground()), commentLabel(b.id)),
    title(PlanEdit.Title(b.id), b.text, bold = false),
    listOf(buttons(more(
        action(if (b.resolved) "Reopen" else "Resolve") { change { it.editBlocker(folderId, b.id) { x -> x.copy(resolved = !b.resolved) } } },
        action("Edit") { openEdit(PlanEdit.Title(b.id)) },
        action("Delete") { change { it.deleteEntry(folderId, b.id) } },
    ))),
)

private fun glyph(text: String, color: Color) = JBLabel(text).apply { foreground = color }

private val RequirementStatus.color: Color
    get() = when (this) {
        RequirementStatus.UNPROVEN -> JBUI.CurrentTheme.ContextHelp.FOREGROUND
        RequirementStatus.NOT_DONE -> UIUtil.getErrorForeground()
        RequirementStatus.PROVEN -> SUCCESS
    }

/** [values] as toggles in a submenu named [name], the [selected] one checked; picking another runs [set]. */
private fun <T> toggles(name: String, values: List<T>, label: (T) -> String, selected: (T) -> Boolean, set: (T) -> Unit) =
    DefaultActionGroup(name, values.map { v ->
        object : DumbAwareToggleAction(label(v)) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = selected(v)
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (!selected(v)) set(v)
            }
        }
    }).apply { isPopup = true }

private val SUCCESS = JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x50A661))

/** How far evidence lines start right of the requirement's glyph. */
private const val EVIDENCE_INDENT = 16
