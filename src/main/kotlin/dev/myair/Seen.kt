package dev.myair

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `~/.my-air/seen/<sessionId>` holds when the user last looked at a session, in epoch ms. One file per session, so the
 * IDEs and the taskbar app never write the same file.
 */
class SeenStore(val dir: Path) {
    init {
        dir.createDirectories()
    }

    // Session ids have no dot; writeAtomically's temp files do.
    fun read(): Map<String, Long> = dir.listDirectoryEntries().filter { '.' !in it.name }.associate { it.name to it.readText().toLong() }

    fun mark(sessionId: String, at: Long) = writeAtomically(dir.resolve(sessionId), at.toString())
}

/**
 * Running sessions never seen, at their last message: a reply that came before this plugin first saw the session is not
 * news, and every later one is.
 */
fun firstSight(sessions: List<ClaudeSession>, live: Map<String, LiveSession>, seen: Map<String, Long>): Map<String, Long> =
    sessions.filter { it.id in live && it.id !in seen }.associate { it.id to it.lastMessage.toEpochMilli() }

/** The sessions among [visible] with a message the user has not seen; marking only those keeps the files still. */
fun newToUser(visible: Collection<String>, sessions: List<ClaudeSession>, seen: Map<String, Long>): List<String> =
    sessions.filter { it.id in visible && (seen[it.id] ?: Long.MIN_VALUE) < it.lastMessage.toEpochMilli() }.map { it.id }
