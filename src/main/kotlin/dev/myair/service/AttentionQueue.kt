package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus

/**
 * The sessions waiting for the user, in the order "Next Session Needing Me" visits them: those that need input, longest
 * waiting first, then those with an [unread] reply, oldest reply first.
 */
fun attentionQueue(sessions: List<ClaudeSession>, live: Map<String, LiveSession>, unread: Set<String>): List<ClaudeSession> =
    sessions.filter { live[it.id]?.status == SessionStatus.NEEDS_INPUT }.sortedBy { live.getValue(it.id).since } +
        sessions.filter { it.id in unread && it.id in live }.sortedBy { it.lastMessage }

/**
 * The session after the current one in [queue], wrapping round; the first without a current one. The current one is
 * [shown] (the session of the selected tab) when queued, else [last], where the action went before: a session running
 * outside an IDE tab is never shown.
 */
fun nextInQueue(queue: List<String>, shown: String?, last: String?): String? {
    if (queue.isEmpty()) return null
    val current = listOf(shown, last).map { queue.indexOf(it) }.firstOrNull { it >= 0 } ?: return queue.first()
    return queue[(current + 1) % queue.size]
}
