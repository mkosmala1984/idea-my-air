package dev.myair

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

enum class SessionStatus { WORKING, NEEDS_INPUT, IDLE }

/**
 * [pid] is the `claude` process running the session; it keeps its pid when `/clear` or `/resume` switch it to another
 * session. [entrypoint] is what started it, e.g. "cli" or "claude-desktop". [waitingFor] says what a
 * [SessionStatus.NEEDS_INPUT] session is blocked on, e.g. "dialog open".
 */
data class LiveSession(val pid: Long, val entrypoint: String, val status: SessionStatus, val waitingFor: String?)

/**
 * Reads `~/.claude/sessions/<pid>.json`, which every running `claude` process keeps up to date.
 * A file outlives a crashed process, so an entry counts only while its pid still belongs to the process that wrote it.
 */
class LiveSessionRegistry(
    private val dir: Path,
    private val isSameProcess: (pid: Long, procStart: Long) -> Boolean = ::isSameProcess,
) {
    fun read(): Map<String, LiveSession> = dir.listDirectoryEntries("*.json").mapNotNull { file ->
        val o = JsonParser.parseString(file.readText()).asJsonObject
        val pid = o["pid"].asLong
        val procStart = checkNotNull(o["procStart"]) { "no procStart in $file" }.asString.toLong()
        if (!isSameProcess(pid, procStart)) return@mapNotNull null
        val status = when (val s = checkNotNull(o["status"]) { "no status in $file" }.asString) {
            // `shell`: running a shell command; claude's own session list shows it as working, like `busy`.
            "busy", "shell" -> SessionStatus.WORKING
            "waiting" -> SessionStatus.NEEDS_INPUT
            "idle" -> SessionStatus.IDLE
            else -> error("unknown session status '$s' in $file")
        }
        val entrypoint = checkNotNull(o["entrypoint"]) { "no entrypoint in $file" }.asString
        o["sessionId"].asString to LiveSession(pid, entrypoint, status, o["waitingFor"]?.asString)
    }.toMap()

    companion object {
        /** Milliseconds between the Windows FILETIME epoch (1601-01-01) and the Unix epoch. */
        private const val FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L

        /** [procStart] is the process creation time as a Windows FILETIME (100 ns ticks since 1601, UTC). */
        fun isSameProcess(pid: Long, procStart: Long): Boolean {
            val started = ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null) ?: return false
            return started.toEpochMilli() == procStart / 10_000 - FILETIME_EPOCH_OFFSET_MS
        }
    }
}
