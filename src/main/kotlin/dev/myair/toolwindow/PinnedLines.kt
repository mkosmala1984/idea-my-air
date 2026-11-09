package dev.myair.toolwindow

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import java.nio.file.Path

/** The second line of a session's row in the pinned list, chosen by its status ([secondLine]). */
sealed interface SecondLine {
    /** What a session needing input waits for: the registry's `waitingFor`, else "Needs input". */
    data class NeedsInput(val text: String) : SecondLine

    /**
     * Where the session works: its [project], the [branch] checked out in its work dir and its brief's task count ([briefCount]);
     * [progress] (done over all tasks) only while it works and has tasks, drawn as a bar.
     */
    data class Place(val project: Path, val branch: String?, val count: String?, val progress: Double?) : SecondLine {
        /** The parts after the project, joined by " · ", so a missing one leaves no separator behind. */
        val rest get() = listOfNotNull(branch, count)
    }
}

fun secondLine(session: ClaudeSession, live: LiveSession?, branch: String?): SecondLine = when (live?.status) {
    SessionStatus.NEEDS_INPUT -> SecondLine.NeedsInput(live.waitingFor ?: "Needs input")
    else -> {
        val brief = session.brief?.takeIf { it.tasks.isNotEmpty() }
        SecondLine.Place(
            session.project, branch, briefCount(brief),
            brief?.takeIf { live?.status == SessionStatus.WORKING }?.let { it.done.toDouble() / it.tasks.size },
        )
    }
}

/**
 * How high the pinned list is: the height its lower edge was [dragged] to, else as its [content] up to [share] of the panel,
 * but at least one [row] and never so high that the tree below keeps less than [treeMin] of the [panel].
 */
fun pinnedHeight(content: Int, row: Int, dragged: Int?, share: Int, panel: Int, treeMin: Int): Int =
    (dragged ?: minOf(content, share)).coerceIn(row, maxOf(row, panel - treeMin))
