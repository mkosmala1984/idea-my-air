package dev.myair.dashboard

import dev.myair.actions.BranchFate
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import dev.myair.folders.PlanStatus
import dev.myair.folders.SessionFolders
import java.time.Duration
import java.time.Instant

/** Git facts of one session: a file it wrote is uncommitted; its branch's fate (null: no branch, no ref, or its base). */
data class SessionGit(val uncommitted: Boolean, val fate: BranchFate?)

/** What a dashboard row shows about a session, one chip per open signal or end. */
sealed interface Chip {
    data class BriefTasks(val done: Int, val total: Int) : Chip
    data object Uncommitted : Chip
    data object Unpushed : Chip
    data class PlanTask(val id: String, val status: PlanStatus) : Chip
    data object Asks : Chip
    data class Merged(val pr: Int?) : Chip
    data object Pending : Chip
    data class GitError(val message: String) : Chip
    data class Verdict(val verdict: dev.myair.dashboard.Verdict) : Chip
    data class AnalysisFailed(val message: String) : Chip
}

fun chipText(chip: Chip): String = when (chip) {
    is Chip.BriefTasks -> "brief ${chip.done}/${chip.total}"
    Chip.Uncommitted -> "uncommitted"
    Chip.Unpushed -> "unpushed"
    is Chip.PlanTask -> "${chip.id} ${chip.status.label}"
    Chip.Asks -> "asks"
    is Chip.Merged -> chip.pr?.let { "PR #$it" } ?: "merged"
    Chip.Pending -> "…"
    is Chip.GitError -> "git error"
    is Chip.Verdict -> chip.verdict.label
    is Chip.AnalysisFailed -> "analysis failed"
}

/** What Haiku said about a row's session: nothing current, its verdict and what is left, or why it could not tell. */
sealed interface RowAnalysis {
    data object None : RowAnalysis
    data class Current(val verdict: Verdict, val left: String) : RowAnalysis
    data class Failed(val message: String) : RowAnalysis
}

data class Row(val session: ClaudeSession, val chips: List<Chip>, val analysis: RowAnalysis = RowAnalysis.None)

/** A group of Unfinished: a plan folder, a folder at depth one or two, or a project. */
data class Area(val name: String, val rows: List<Row>)

/**
 * [now]: needing input, then working, then unread; [checking]: sessions whose git is still being read, which may yet
 * show them merged; [unfinished]: sessions with an open signal by area; [finished]: the rest; [recent]/[older]: sessions
 * not settled whose last message is within / before [RECENT] of the time asked.
 */
data class Dashboard(
    val now: List<ClaudeSession>, val checking: List<Row>, val unfinished: List<Area>, val finished: List<Row>, val recent: Int, val older: Int,
)

private val RECENT = Duration.ofDays(2)

/**
 * [git]: per session id; absent means not read yet, a failure means git could not be read. [analyses] count only while
 * the session has no newer message; [failures] are the messages of the last analyses that failed. [sessions] arrive newest first.
 */
fun dashboardOf(
    sessions: List<ClaudeSession>, live: Map<String, LiveSession>, settled: Set<String>, unread: Set<String>,
    folders: SessionFolders, git: Map<String, Result<SessionGit>>, analyses: Map<String, Analysis>, failures: Map<String, String>, now: Instant,
): Dashboard {
    val shown = sessions.filter { it.id !in settled }
    fun nowRank(s: ClaudeSession) = when {
        live[s.id]?.status == SessionStatus.NEEDS_INPUT -> 0
        live[s.id]?.status == SessionStatus.WORKING -> 1
        s.id in unread -> 2
        else -> null
    }
    val current = shown.filter { nowRank(it) != null }.sortedBy { nowRank(it) }
    val rows = (shown - current.toSet()).map { session ->
        val chips = chips(session, folders, git[session.id])
        val analysis = analyses[session.id]?.takeIf { it.lastMessage == session.lastMessage }
        when {
            analysis != null -> Row(session, chips + Chip.Verdict(analysis.verdict), RowAnalysis.Current(analysis.verdict, analysis.left))
            session.id in failures -> failures.getValue(session.id).let { Row(session, chips + Chip.AnalysisFailed(it), RowAnalysis.Failed(it)) }
            else -> Row(session, chips)
        }
    }
    val (checking, known) = rows.partition { row -> row.chips.any { it is Chip.Pending } }
    val (open, done) = known.partition { row -> row.chips.any { it !is Chip.Merged && it !is Chip.Verdict && it !is Chip.AnalysisFailed } }
    val areas = open.groupBy { areaOf(it.session, folders) }.map { (name, rows) -> Area(name, rows) }
    val recent = shown.count { it.lastMessage > now - RECENT }
    return Dashboard(current, checking, areas, done, recent, shown.size - recent)
}

/** The chips of a session not in Now: its open signals, Pending or GitError while git is unknown, Merged when merged. */
private fun chips(session: ClaudeSession, folders: SessionFolders, git: Result<SessionGit>?): List<Chip> {
    val facts = git?.getOrNull()
    val merged = facts?.fate?.merged
    val chips = ArrayList<Chip>()
    if (merged == null) {
        session.brief?.tasks?.takeIf { it.isNotEmpty() }?.let { tasks ->
            val done = tasks.count { it.status == TaskStatus.COMPLETED }
            if (done < tasks.size) chips += Chip.BriefTasks(done, tasks.size)
        }
        folders.taskOf(session)?.second?.takeIf { it.status in OPEN_TASK }?.let { chips += Chip.PlanTask(it.id, it.status) }
        if (session.asks) chips += Chip.Asks
        if ((facts?.fate?.unpushed ?: 0) > 0) chips += Chip.Unpushed
    }
    if (facts?.uncommitted == true) chips += Chip.Uncommitted
    when {
        git == null -> chips += Chip.Pending
        git.isFailure -> chips += Chip.GitError(git.exceptionOrNull()?.message.orEmpty())
    }
    if (merged != null) chips += Chip.Merged(merged.pr)
    return chips
}

/** The sessions an analysis run takes: listed out of Now, not known merged (a first git read of every work tree takes minutes), no current analysis, last message within [days] (null: any). */
fun analysisCandidates(board: Dashboard, days: Int?, now: Instant): List<ClaudeSession> {
    val since = days?.let { now - Duration.ofDays(it.toLong()) }
    return (board.checking + board.unfinished.flatMap { it.rows } + board.finished)
        .filter { row -> row.chips.none { it is Chip.Merged } && row.analysis !is RowAnalysis.Current }
        .map { it.session }.filter { since == null || it.lastMessage > since }.sortedByDescending { it.lastMessage }
}

private val OPEN_TASK = setOf(PlanStatus.IN_PROGRESS, PlanStatus.WAITING, PlanStatus.BLOCKED)

/** The plan folder the session's place lies in, else its place's ancestor at depth two, else its project's folder name. */
fun areaOf(session: ClaudeSession, folders: SessionFolders): String {
    val place = folders.placeOf(session) ?: return session.project.fileName?.toString() ?: session.project.toString()
    val ancestry = folders.ancestry(place)
    val plan = ancestry.indexOfLast { it.plan != null }
    val depth = if (plan >= 0) plan + 1 else minOf(ancestry.size, 2)
    return ancestry.take(depth).joinToString(" / ") { it.name }
}
