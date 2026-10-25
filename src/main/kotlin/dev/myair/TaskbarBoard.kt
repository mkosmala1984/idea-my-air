package dev.myair

import com.google.gson.Gson

/**
 * A running session as the taskbar widget (`taskbar/`) shows it; [project] and [lastMessage] (epoch ms) are null while its
 * transcript is not scanned yet. [unread]: idle, with a message after the user last looked at it.
 */
data class BoardSession(
    val id: String, val title: String, val project: String?, val status: SessionStatus, val waitingFor: String?,
    val lastMessage: Long?, val unread: Boolean,
)

/**
 * Every session with a running process, for `~/.my-air/taskbar/board.json`: waiting for the user first, then idle, then
 * working; newest first within a group, as [sessions] arrive. A process whose session has no transcript yet comes last
 * in its group, titled [ClaudeSessionsService.NEW_SESSION_TITLE]. [seen] is [SeenStore.read].
 */
fun boardSessions(sessions: List<ClaudeSession>, live: Map<String, LiveSession>, seen: Map<String, Long>): List<BoardSession> {
    val scanned = sessions.mapNotNull { s ->
        val at = s.lastMessage.toEpochMilli()
        live[s.id]?.let {
            val unread = it.status == SessionStatus.IDLE && seen[s.id]?.let { seenAt -> at > seenAt } == true
            BoardSession(s.id, s.title, s.cwd.fileName.toString(), it.status, it.waitingFor, at, unread)
        }
    }
    val unscanned = (live.keys - sessions.map { it.id }.toSet()).map { id ->
        live.getValue(id).let { BoardSession(id, ClaudeSessionsService.NEW_SESSION_TITLE, null, it.status, it.waitingFor, null, false) }
    }
    return (scanned + unscanned).sortedBy {
        when (it.status) {
            SessionStatus.NEEDS_INPUT -> 0
            SessionStatus.IDLE -> 1
            SessionStatus.WORKING -> 2
        }
    }
}

/** A session for the flyout's "Recent" group, with or without a process; [lastMessage] is epoch ms. */
data class RecentSession(val id: String, val title: String, val project: String, val lastMessage: Long)

/** Sessions the "Recent" group shows at most. */
const val RECENT = 5

/**
 * The [RECENT] newest of [sessions] (newest first, as the scanner returns them) that no other group of the flyout
 * lists: idle and read, or without a process. Settled sessions are left out, as from the list and the tree.
 */
fun recentSessions(sessions: List<ClaudeSession>, board: List<BoardSession>, settled: Set<String>): List<RecentSession> {
    val grouped = board.filter { it.status != SessionStatus.IDLE || it.unread }.map { it.id }.toSet()
    return sessions.asSequence().filter { it.id !in grouped && it.id !in settled }.take(RECENT)
        .map { RecentSession(it.id, it.title, it.cwd.fileName.toString(), it.lastMessage.toEpochMilli()) }.toList()
}

/** The file the widget reads; status names are the enum constants, a null field is left out. */
fun boardJson(board: List<BoardSession>, recent: List<RecentSession>): String = Gson().toJson(mapOf("sessions" to board, "recent" to recent))
