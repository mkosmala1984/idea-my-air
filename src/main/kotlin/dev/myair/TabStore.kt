package dev.myair

import com.google.gson.Gson
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
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
import kotlin.io.path.writeText

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

/**
 * Writes to a temp name and moves it into place, so a reader never sees half a file. `Files.move` (MoveFileEx) refuses
 * to replace a file anyone holds open, as the taskbar app and other IDEs do while reading; a POSIX-semantics rename
 * replaces it anyway, leaving readers that share delete access on the old contents.
 */
fun writeAtomically(target: Path, text: String) {
    val temp = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
    temp.writeText(text)
    try {
        // A virus scanner or indexer may hold a just-written file without sharing delete for a moment; Windows then
        // refuses the rename with access denied or a sharing violation, as it does to the IDE's own temp files here.
        for (attempt in 1..RENAME_ATTEMPTS) {
            val error = rename(temp, target) ?: return
            check(error in TRANSIENT_ERRORS && attempt < RENAME_ATTEMPTS) { "cannot move $temp to $target: error $error" }
            Thread.sleep(50)
        }
    } finally {
        temp.deleteIfExists()
    }
}

/** Null when [temp] took the place of [target], else the Windows error code. */
private fun rename(temp: Path, target: Path): Int? {
    val kernel32 = Kernel32.INSTANCE
    val handle = kernel32.CreateFile(temp.toString(), WinNT.DELETE, WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null)
    if (handle == WinBase.INVALID_HANDLE_VALUE) return kernel32.GetLastError()
    try {
        // FILE_RENAME_INFO on x64: Flags at 0, RootDirectory at 8, FileNameLength at 16, FileName at 20.
        val name = target.toAbsolutePath().toString()
        val info = Memory(20L + 2 * (name.length + 1)).apply {
            clear()
            setInt(0, FILE_RENAME_FLAG_REPLACE_IF_EXISTS or FILE_RENAME_FLAG_POSIX_SEMANTICS)
            setInt(16, 2 * name.length)
            setWideString(20, name)
        }
        return if (kernel32.SetFileInformationByHandle(handle, FILE_RENAME_INFO_EX, info, WinDef.DWORD(info.size()))) null else kernel32.GetLastError()
    } finally {
        kernel32.CloseHandle(handle)
    }
}

private const val RENAME_ATTEMPTS = 5
private val TRANSIENT_ERRORS = setOf(WinError.ERROR_ACCESS_DENIED, WinError.ERROR_SHARING_VIOLATION)

private const val FILE_RENAME_INFO_EX = 22
private const val FILE_RENAME_FLAG_REPLACE_IF_EXISTS = 1
private const val FILE_RENAME_FLAG_POSIX_SEMANTICS = 2
