package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.store.SeenStore
import dev.myair.store.firstSight
import dev.myair.store.newToUser
import dev.myair.store.writeAtomically
import dev.myair.tabs.SessionHosts
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Writes `<myAir>/taskbar/board.json` and keeps `<myAir>/seen/`. Every IDE writes the same content, derived from the same
 * files, so it needs no single owner.
 */
class BoardWriter(myAir: Path) {
    // In a directory of its own: the service watches `~/.my-air`, and every IDE would rescan on each write.
    private val file = myAir.resolve("taskbar").createDirectories().resolve("board.json")

    /** What this IDE last wrote to [file]; folder, pin and title changes refresh too, but leave the board as it is. */
    private var written: String? = null

    val seenStore = SeenStore(myAir.resolve("seen"))

    /**
     * Marks seen the sessions met for the first time and those whose tab the user looks at now, then writes the board.
     * Returns the running idle sessions with a message the user has not seen, as the taskbar's "Unread" group lists them.
     */
    fun update(sessions: List<ClaudeSession>, live: Map<String, LiveSession>, settled: Set<String>): Set<String> {
        val seen = seenStore.read()
        val now = System.currentTimeMillis()
        val marks = firstSight(sessions, live, seen) + newToUser(SessionHosts.visibleSessions(), sessions, seen).associateWith { now }
        marks.forEach { (id, at) -> seenStore.mark(id, at) }
        val running = boardSessions(sessions, live, seen + marks)
        val board = boardJson(running, recentSessions(sessions, running, settled))
        if (board != written) {
            writeAtomically(file, board)
            written = board
        }
        return running.filter { it.unread }.map { it.id }.toSet()
    }
}
