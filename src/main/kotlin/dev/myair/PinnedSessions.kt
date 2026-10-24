package dev.myair

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * [sessions]: ids pinned by hand, in pinning order. [dismissed]: running sessions removed from the list, each with the
 * time its transcript had when removed; one comes back once it has written more and stopped working, i.e. waits again.
 */
data class Pins(val sessions: Set<String> = emptySet(), val dismissed: Map<String, Long> = emptyMap()) {
    fun pin(ids: Collection<String>) = Pins(sessions + ids, dismissed - ids.toSet())

    fun unpin(ids: Collection<String>) = copy(sessions = sessions - ids.toSet())

    /** Takes [removed] off the list: unpinned, and the running ones (per [live]) hidden until they wait again. */
    fun remove(removed: List<ClaudeSession>, live: Map<String, LiveSession>) =
        Pins(sessions - removed.map { it.id }.toSet(), dismissed + removed.filter { it.id in live }.associate { it.id to it.lastModified.toMillis() })

    /** Drops dismissals that are over: the process ended, or the session came back. */
    fun prune(sessions: List<ClaudeSession>, live: Map<String, LiveSession>): Pins {
        val byId = sessions.associateBy { it.id }
        return copy(dismissed = dismissed.filter { (id, at) -> id in live && byId[id]?.let { hidden(it, at, live) } != false })
    }

    fun toJson(): String = GsonBuilder().setPrettyPrinting().create()
        .toJson(if (dismissed.isEmpty()) mapOf("sessions" to sessions) else mapOf("sessions" to sessions, "dismissed" to dismissed))

    companion object {
        fun parse(text: String): Pins {
            val root = JsonParser.parseString(text).asJsonObject
            check(root.keySet() == setOf("sessions") || root.keySet() == setOf("sessions", "dismissed")) { "unexpected keys in pins: ${root.keySet()}" }
            return Pins(
                root["sessions"].asJsonArray.map { it.string() }.toSet(),
                root["dismissed"]?.asJsonObject?.entrySet()?.associate { (id, at) -> id to at.asLong }.orEmpty(),
            )
        }

        private fun JsonElement.string(): String {
            check(isJsonPrimitive && asJsonPrimitive.isString) { "pinned session id is not a string: $this" }
            return asString
        }
    }
}

/** A dismissed session stays hidden until its transcript moves past [at] and it is not working any more. */
private fun hidden(session: ClaudeSession, at: Long, live: Map<String, LiveSession>) =
    session.lastModified.toMillis() <= at || live[session.id]?.status == SessionStatus.WORKING

/**
 * The list above the tree: every session with a running process, plus the ones pinned by hand, less the dismissed ones
 * still hidden. Sessions waiting for the user come first, then working ones, then pinned ones without a process;
 * [sessions] arrive newest first and keep that order within each group. A pinned id absent from [sessions] is not shown.
 */
fun pinnedSessions(sessions: List<ClaudeSession>, live: Map<String, LiveSession>, pins: Pins): List<ClaudeSession> =
    sessions.filter { (it.id in pins.sessions || it.id in live) && pins.dismissed[it.id]?.let { at -> hidden(it, at, live) } != true }.sortedBy {
        when (live[it.id]?.status) {
            SessionStatus.NEEDS_INPUT -> 0
            SessionStatus.IDLE -> 1
            SessionStatus.WORKING -> 2
            null -> 3
        }
    }

/** `~/.my-air/pins.json`, shared by every IDE. */
class PinStore(val file: Path) {
    init {
        file.parent.createDirectories()
    }

    fun read(): Pins = if (file.exists()) Pins.parse(file.readText()) else Pins()

    @Synchronized
    fun update(op: (Pins) -> Pins): Pins {
        val before = read()
        val after = op(before)
        if (after != before) writeAtomically(file, after.toJson())
        return after
    }
}
