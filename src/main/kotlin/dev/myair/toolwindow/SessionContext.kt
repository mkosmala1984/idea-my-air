package dev.myair.toolwindow

import dev.myair.actions.GitState
import dev.myair.claude.Artifact
import dev.myair.claude.BackgroundTask
import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.extension

/** The file types of the session's own writing, as Claude Desktop's Outputs lists them: plans, notes, pages. */
val OUTPUT_EXTENSIONS = setOf("md", "html")

/**
 * What the context pane shows of a session. [project] is the name of its project folder, [worktree] whether it runs in
 * a linked worktree of it; [files] the files it wrote outside [git]'s work tree (all of them while git is unknown),
 * first touch first, since the work tree's show among the changes; [outputs] the [OUTPUT_EXTENSIONS] ones of all it wrote,
 * the newest first; [background] its tasks, the unended ones first in the order launched, then the ended ones, the
 * latest end first; [live] whether it has a process; [git] null outside a work tree and while it is being read.
 */
data class SessionContext(
    val title: String, val lastMessage: Instant, val model: String?, val effort: String?,
    val project: String, val projectPath: Path, val cwd: Path, val worktree: Boolean, val desktop: Boolean,
    val addedDirs: List<Path>, val brief: Brief?, val files: List<Path>, val outputs: List<Path>, val artifacts: List<Artifact>,
    val background: List<BackgroundTask>, val live: Boolean, val cwdExists: Boolean, val git: GitState?,
)

/** [ClaudeSession.changedFiles] keeps the order files were first written, without repeats. */
fun contextOf(session: ClaudeSession, cwdExists: Boolean, live: Boolean, git: GitState?) = SessionContext(
    session.title, session.lastMessage, session.model, session.effort,
    (session.project.fileName ?: session.project).toString(), session.project, session.cwd, session.cwd != session.project,
    session.desktop, session.addedDirs, session.brief, session.changedFiles.filterNot { git != null && it.startsWith(git.root) },
    session.changedFiles.filter { it.extension.lowercase() in OUTPUT_EXTENSIONS }.reversed(), session.artifacts,
    session.background.sortedWith(compareBy(nullsFirst(reverseOrder())) { it.end?.time }),
    live, cwdExists, git,
)

private val MODEL_ID = Regex("""claude-([a-z]+)((?:-\d{1,2})+)(?:-\d{8})?""")

/** `claude-opus-5-5` as `Opus 5.5`, a date suffix dropped; an id of another form as it is. */
fun modelName(id: String): String = MODEL_ID.matchEntire(id)?.let { m ->
    m.groupValues[1].replaceFirstChar { it.uppercase() } + " " + m.groupValues[2].drop(1).replace('-', '.')
} ?: id

private val EXIT_CODE = Regex("exit code (\\d+)")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What the pane says of [task] after its name: an agent's model; while it runs, the time it started in [zone]; once
 * ended, how, with the exit code of a failed command, and how long it took; a monitor's events; what an agent or
 * workflow used. A task without an end in a session without its process died with it, as the transcript does not record.
 */
fun backgroundState(task: BackgroundTask, live: Boolean, zone: ZoneId): String {
    fun clock(time: Instant) = CLOCK.format(time.atZone(zone))
    val end = task.end
    val state = when {
        end != null -> listOfNotNull(
            end.status.name.lowercase(),
            EXIT_CODE.find(end.summary)?.takeIf { end.status == BackgroundTask.Status.FAILED }?.let { "exit ${it.groupValues[1]}" },
            durationText(Duration.between(task.started, end.time)),
        )
        live -> listOf("since ${clock(task.started)}")
        else -> listOf("ended with its process")
    }
    val events = if (task.kind != BackgroundTask.Kind.MONITOR) null
    else task.lastEvent?.let { "${task.events} event${if (task.events == 1) "" else "s"}, last ${clock(it)}" } ?: "no events"
    val usage = task.usage?.let { u ->
        listOfNotNull(
            u.agents?.let { a -> "${a.done}/${a.count} agents" + if (a.failed > 0) ", ${a.failed} failed" else "" },
            "${countText(u.tokens)} tokens", "${u.toolUses} tools",
        )
    }.orEmpty()
    return (listOfNotNull(task.model?.removePrefix("claude-")) + state + listOfNotNull(events) + usage).joinToString(" · ")
}

/** `950`, `154k`, `1.4M`. */
fun countText(n: Long): String = when {
    n < 1000 -> "$n"
    n < 1_000_000 -> "${n / 1000}k"
    else -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
}

/** Whole seconds under a minute, else the two largest units: `45 s`, `2 min 40 s`, `1 h 5 min`. */
fun durationText(duration: Duration): String {
    val s = duration.seconds
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${s % 3600 / 60} min"
    }
}

/** Files inside the session's folder read relative to it; anything else keeps its full path. */
fun fileLabel(path: Path, cwd: Path) = if (path.startsWith(cwd)) cwd.relativize(path).toString() else path.toString()
