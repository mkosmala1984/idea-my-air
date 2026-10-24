package dev.myair

import com.google.gson.Gson

/** A running session as the taskbar widget (`taskbar/`) shows it; [project] is null while its transcript has no `cwd` yet. */
data class BoardSession(val id: String, val title: String, val project: String?, val status: SessionStatus, val waitingFor: String?)

/**
 * Every session with a running process, for `~/.my-air/taskbar/board.json`: waiting for the user first, then idle, then
 * working; newest first within a group, as [sessions] arrive. A process whose session has no transcript yet comes last
 * in its group, titled [ClaudeSessionsService.NEW_SESSION_TITLE].
 */
fun boardSessions(sessions: List<ClaudeSession>, live: Map<String, LiveSession>): List<BoardSession> {
    val scanned = sessions.mapNotNull { s -> live[s.id]?.let { BoardSession(s.id, s.title, s.cwd.fileName.toString(), it.status, it.waitingFor) } }
    val unscanned = (live.keys - sessions.map { it.id }.toSet()).map { id ->
        live.getValue(id).let { BoardSession(id, ClaudeSessionsService.NEW_SESSION_TITLE, null, it.status, it.waitingFor) }
    }
    return (scanned + unscanned).sortedBy {
        when (it.status) {
            SessionStatus.NEEDS_INPUT -> 0
            SessionStatus.IDLE -> 1
            SessionStatus.WORKING -> 2
        }
    }
}

/** The file the widget reads; status names are the enum constants, a null field is left out. */
fun boardJson(board: List<BoardSession>): String = Gson().toJson(mapOf("sessions" to board))
