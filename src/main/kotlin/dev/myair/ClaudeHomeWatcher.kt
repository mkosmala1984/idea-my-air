package dev.myair

import java.io.Closeable
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches `sessions/` and every `projects/<encoded-cwd>/` and calls [onChange] on its own thread once a burst of writes
 * settles: [quiet] after the last event, but no later than [maxWait] after the first, so a session that keeps writing
 * still refreshes. Air uses the same 500/700 ms.
 */
class ClaudeHomeWatcher(
    private val sessionsDir: Path,
    private val projectsDir: Path,
    private val quiet: Duration = 500.milliseconds,
    private val maxWait: Duration = 700.milliseconds,
    private val onChange: () -> Unit,
) : Closeable {
    private val service = FileSystems.getDefault().newWatchService()

    init {
        register(sessionsDir)
        register(projectsDir)
        projectsDir.listDirectoryEntries().filter { it.isDirectory() }.forEach(::register)
    }

    private val worker = thread(isDaemon = true, name = "Claude home watcher") {
        try {
            while (true) {
                handle(service.take())
                val deadline = System.nanoTime() + maxWait.inWholeNanoseconds
                while (true) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    handle(service.poll(minOf(quiet.inWholeNanoseconds, left), TimeUnit.NANOSECONDS) ?: break)
                }
                onChange()
            }
        } catch (_: ClosedWatchServiceException) {
            // close() ends the loop.
        }
    }

    private fun register(dir: Path) {
        dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
    }

    /** A new project directory gets its own watch; its first transcript may predate that, but the burst refreshes anyway. */
    private fun handle(key: WatchKey) {
        for (event in key.pollEvents()) {
            val child = (key.watchable() as Path).resolve(event.context() as? Path ?: continue)
            if (key.watchable() == projectsDir && event.kind() == ENTRY_CREATE && child.isDirectory()) register(child)
        }
        key.reset()
    }

    override fun close() {
        service.close()
        worker.join()
    }
}
