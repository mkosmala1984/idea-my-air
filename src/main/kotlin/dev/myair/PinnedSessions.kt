package dev.myair

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * [sessions]: ids pinned by hand, in pinning order. [dismissed]: running sessions removed from the list, each with the
 * time of its last message when removed; one comes back once it has a newer message and stopped working, i.e. waits again.
 * [settled]: sessions marked as done, hidden from the list and the tree unless all sessions are shown.
 */
data class Pins(val sessions: Set<String> = emptySet(), val dismissed: Map<String, Long> = emptyMap(), val settled: Set<String> = emptySet()) {
    fun pin(ids: Collection<String>) = Pins(sessions + ids, dismissed - ids.toSet(), settled - ids.toSet())

    fun unpin(ids: Collection<String>) = copy(sessions = sessions - ids.toSet())

    /** Takes [removed] off the list: unpinned, and the running ones (per [live]) hidden until they wait again. */
    fun remove(removed: List<ClaudeSession>, live: Map<String, LiveSession>) =
        copy(sessions = sessions - removed.map { it.id }.toSet(), dismissed = dismissed + removed.filter { it.id in live }.associate { it.id to it.lastMessage.toEpochMilli() })

    /** Marks [ids] as done: no longer pinned or dismissed, just hidden. */
    fun settle(ids: Collection<String>) = Pins(sessions - ids.toSet(), dismissed - ids.toSet(), settled + ids)

    fun unsettle(ids: Collection<String>) = copy(settled = settled - ids.toSet())

    /** What the list and the tree take from [all]: everything with [showSettled], otherwise the sessions not settled. */
    fun shown(all: List<ClaudeSession>, showSettled: Boolean) = if (showSettled) all else all.filter { it.id !in settled }

    /** Drops dismissals that are over: the process ended, or the session came back. */
    fun prune(sessions: List<ClaudeSession>, live: Map<String, LiveSession>): Pins {
        val byId = sessions.associateBy { it.id }
        return copy(dismissed = dismissed.filter { (id, at) -> id in live && byId[id]?.let { hidden(it, at, live) } != false })
    }

    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(
        buildMap {
            put("sessions", sessions)
            if (dismissed.isNotEmpty()) put("dismissed", dismissed)
            if (settled.isNotEmpty()) put("settled", settled)
        },
    )

    companion object {
        fun parse(text: String): Pins {
            val root = JsonParser.parseString(text).asJsonObject
            check("sessions" in root.keySet() && setOf("sessions", "dismissed", "settled").containsAll(root.keySet())) { "unexpected keys in pins: ${root.keySet()}" }
            return Pins(
                root["sessions"].asJsonArray.map { it.string() }.toSet(),
                root["dismissed"]?.asJsonObject?.entrySet()?.associate { (id, at) -> id to at.asLong }.orEmpty(),
                root["settled"]?.asJsonArray?.map { it.string() }?.toSet().orEmpty(),
            )
        }

        private fun JsonElement.string(): String {
            check(isJsonPrimitive && asJsonPrimitive.isString) { "session id in pins is not a string: $this" }
            return asString
        }
    }
}

/** A dismissed session stays hidden until it has a message after [at] and it is not working any more. */
private fun hidden(session: ClaudeSession, at: Long, live: Map<String, LiveSession>) =
    session.lastMessage.toEpochMilli() <= at || live[session.id]?.status == SessionStatus.WORKING

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
