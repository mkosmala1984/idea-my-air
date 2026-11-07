package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession

/**
 * The running sessions of [live] that [scanned] lacks, since `claude` writes a transcript only with the first message:
 * each a [NEW_SESSION_TITLE] in the cwd its process started in, dated by that start, newest first.
 */
fun unscannedSessions(scanned: List<ClaudeSession>, live: Map<String, LiveSession>): List<ClaudeSession> {
    val ids = scanned.mapTo(HashSet()) { it.id }
    return live.filterKeys { it !in ids }
        .map { (id, session) -> ClaudeSession(id, session.cwd, NEW_SESSION_TITLE, session.started, emptyList(), emptyList()) }
        .sortedByDescending { it.lastMessage }
}

/** [scanned] and [unscanned] together, newest first, as the session lists take them. */
fun withUnscanned(scanned: List<ClaudeSession>, unscanned: List<ClaudeSession>) = (unscanned + scanned).sortedByDescending { it.lastMessage }
