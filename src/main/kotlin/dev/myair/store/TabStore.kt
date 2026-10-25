package dev.myair.store

import com.google.gson.Gson
import java.io.Closeable
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** A session tab in one IDE. [sessionIds] holds every id the tab may stand for: an Air tab exposes two candidates. */
data class OpenTab(val sessionIds: List<String>, val title: String, val project: String)

/** The session tabs of one running IDE, identified like registry entries by pid and process start. */
data class IdeTabs(val pid: Long, val started: Long, val tabs: List<OpenTab>)

/**
 * Lets every IDE running this plugin see the others' session tabs and ask one of them to show a tab:
 * `ides/<pid>.json` lists an IDE's tabs, `requests/<pid>/` receives focus requests for it. Files are written to a temp
 * name and moved into place, so a reader never sees half a file. A file left by a dead IDE is ignored and removed.
 */
class TabStore(
    root: Path,
    private val self: ProcessHandle = ProcessHandle.current(),
    private val isAlive: (pid: Long, started: Long) -> Boolean = ::isAlive,
) {
    private val gson = Gson()
    private val idesDir = root.resolve("ides").createDirectories()
    private val requestsDir = root.resolve("requests").createDirectories()
    private val selfStarted = startMillis(self)!!

    fun publish(tabs: List<OpenTab>) = writeAtomically(idesDir.resolve("${self.pid()}.json"), gson.toJson(IdeTabs(self.pid(), selfStarted, tabs)))

    /** Every live IDE's tabs, this one included. */
    fun all(): List<IdeTabs> = idesDir.listDirectoryEntries("*.json").mapNotNull { file ->
        val ide = try {
            gson.fromJson(file.readText(), IdeTabs::class.java)
        } catch (_: NoSuchFileException) {
            return@mapNotNull null // its IDE removed it between listing and reading
        }
        if (isAlive(ide.pid, ide.started)) ide else null.also { file.deleteIfExists() }
    }

    fun requestFocus(pid: Long, sessionId: String) =
        writeAtomically(requestsDir.resolve("$pid").createDirectories().resolve("${UUID.randomUUID()}.json"), gson.toJson(FocusRequest(sessionId)))

    /** Calls [onRequest] on its own thread with each session id sent to this IDE, including requests already waiting. */
    fun receiveRequests(onRequest: (String) -> Unit): Closeable {
        val inbox = requestsDir.resolve("${self.pid()}").createDirectories()
        val service = FileSystems.getDefault().newWatchService()
        inbox.register(service, ENTRY_CREATE)
        fun drain() = inbox.listDirectoryEntries("*.json").forEach { file ->
            val request = gson.fromJson(file.readText(), FocusRequest::class.java)
            file.deleteIfExists()
            onRequest(request.sessionId)
        }
        drain()
        val worker = thread(isDaemon = true, name = "My Air focus requests") {
            try {
                while (true) {
                    val key = service.take()
                    key.pollEvents()
                    key.reset()
                    drain()
                }
            } catch (_: ClosedWatchServiceException) {
                // close() ends the loop.
            }
        }
        return Closeable {
            service.close()
            worker.join()
        }
    }

    /** Removes this IDE's tab list, so others stop offering it before the process is gone. */
    fun withdraw() {
        idesDir.resolve("${self.pid()}.json").deleteIfExists()
    }

    private data class FocusRequest(val sessionId: String)

    companion object {
        fun startMillis(process: ProcessHandle): Long? = process.info().startInstant().map { it.toEpochMilli() }.orElse(null)

        fun isAlive(pid: Long, started: Long): Boolean = ProcessHandle.of(pid).map { startMillis(it) == started }.orElse(false)
    }
}
