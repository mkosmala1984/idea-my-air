package dev.myair.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.claude.ClaudeHomeWatcher
import dev.myair.claude.ClaudeSession
import dev.myair.claude.ShownFrame
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Canvas
import dev.myair.store.CanvasFiles
import dev.myair.store.Homes
import kotlin.io.path.createDirectories

/** The canvases whose folder is in [structure]; the others are kept on disk, never deleted, so a restored folder finds its canvas. */
fun canvasesInStructure(all: Map<String, Result<Canvas>>, structure: SessionFolders): Map<String, Result<Canvas>> =
    all.filterKeys { structure.find(it) != null }

/** By session id, the canvas frames each of [sessions] showed, only sessions that showed one. */
fun shownFrames(sessions: List<ClaudeSession>): Map<String, List<ShownFrame>> =
    sessions.filter { it.shownFrames.isNotEmpty() }.associate { it.id to it.shownFrames }

/** The folders of [canvases] (readable ones) where [learnAuthors] would change something. */
fun authorsDue(canvases: Map<String, Result<Canvas>>, shows: Map<String, List<ShownFrame>>): Set<String> =
    canvases.filter { (id, result) -> result.getOrNull()?.let { learnAuthors(it, id, shows) != it } == true }.keys

/**
 * Every canvas of `~/.my-air/canvases`, read and written only here, so the canvas tabs and the MCP tools see one state.
 * [canvases] and the listeners are touched on the EDT only. Canvases are read off the EDT on every
 * [ClaudeSessionsService] change and on file events under the canvases directory (another IDE's writes among them);
 * only the latest read lands. After each change of the sessions, frames learn their author from the transcripts
 * ([learnAuthors]).
 */
@Service(Service.Level.APP)
class CanvasService : Disposable {
    private val files = CanvasFiles(Homes.myAir.resolve("canvases"))
    private val sessions = ClaudeSessionsService.getInstance()
    private var all: Map<String, Result<Canvas>> = emptyMap()

    /** By folder id, only folders in the structure; a `canvas.json` that cannot be read is a failure. */
    val canvases get() = canvasesInStructure(all, sessions.folders)

    /** The folder ids of every `canvas.json` on disk, readable or not: what [dev.myair.folders.refuseKindPlacement] knows as canvas folders. */
    val onDiskIds: Set<String> get() = all.keys

    private val listeners = ArrayList<() -> Unit>()

    /** One read at a time; [requested] counts the reads asked for, so an older one is dropped. Changed on the EDT only. */
    private val reader = AppExecutorUtil.createBoundedApplicationPoolExecutor("My Air canvases refresh", 1)
    @Volatile private var requested = 0

    private val watcher: ClaudeHomeWatcher

    init {
        ThreadingAssertions.assertEventDispatchThread()
        files.root.createDirectories()
        // At once, as structure.json is read on the EDT, so the first tool call or structure write after a start knows the canvases.
        all = files.readAll()
        watcher = ClaudeHomeWatcher(emptyList(), listOf(files.root)) { refresh() }
        sessions.subscribe(this) {
            // Later, not inside the notification: a write notifies again.
            ApplicationManager.getApplication().invokeLater(::sync)
            refresh()
        }
        refresh()
    }

    /** The canvas of [folderId]; throws when it has none or it cannot be read. */
    fun canvas(folderId: String): Canvas = requireNotNull(canvases[folderId]) { "folder $folderId has no canvas" }.getOrThrow()

    /** The `canvas.json` of [folderId]. */
    fun file(folderId: String) = files.file(folderId)

    /** Calls [listener] on the EDT after every change, until [parent] is disposed. */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Applies [op] to the canvas of [folderId] as it is on disk now (another IDE may have added a frame), writes it when
     * it changed and notifies; returns the canvas as stored, pruned.
     */
    fun update(folderId: String, op: (Canvas) -> Canvas): Canvas {
        ThreadingAssertions.assertEventDispatchThread()
        require(folderId in canvases) { "folder $folderId has no canvas" }
        val before = files.read(folderId)
        val after = op(before)
        if (after == before) return before
        val stored = store(folderId, after)
        changed()
        return stored
    }

    /** Writes the new [canvas] of [folderId] and notifies; refuses a folder with a `canvas.json` on disk, in the structure or not. */
    fun create(folderId: String, canvas: Canvas) {
        ThreadingAssertions.assertEventDispatchThread()
        require(!files.exists(folderId)) { "folder $folderId has a canvas already" }
        store(folderId, canvas)
        changed()
    }

    /** `/clear` and `/resume` give a process a new session id: a canvas managed by the first of a pair of [switched] now is by the second. */
    fun moveManagers(switched: List<Pair<String, String>>) = switched.forEach { (from, to) ->
        managedCanvasFolder(canvases, from)?.let { id ->
            update(id) { it.copy(manager = checkNotNull(it.manager).copy(session = to)) }
        }
    }

    /** Reads every canvas off the EDT; callable from any thread. */
    fun refresh() = ApplicationManager.getApplication().invokeLater {
        val generation = ++requested
        reader.execute {
            if (generation != requested) return@execute
            // Nobody reads the Future, so an exception would vanish with it; log it as an IDE error instead.
            val read = try {
                files.readAll()
            } catch (e: Throwable) {
                thisLogger().error("Canvas refresh failed", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != requested) return@invokeLater
                if (read == all) return@invokeLater
                all = read
                sync()
                changed()
            }
        }
    }

    /** Gives frames their author where a transcript shows the call that added them. */
    private fun sync() {
        val shows = shownFrames(sessions.sessions)
        if (shows.isEmpty()) return
        authorsDue(canvases, shows).forEach { id ->
            try {
                update(id) { learnAuthors(it, id, shows) }
            } catch (e: Exception) {
                // A canvas.json broken since the last read: the next read shows it as a failure.
                thisLogger().warn("Cannot learn the frame authors of canvas $id", e)
            }
        }
    }

    /** Writes [canvas] as the canvas of [folderId] and holds it; drops a read started before, which would land it as it was. */
    private fun store(folderId: String, canvas: Canvas): Canvas {
        val stored = files.write(folderId, canvas)
        requested++
        all = all + (folderId to Result.success(stored))
        return stored
    }

    // A copy: a listener may unsubscribe while being called.
    private fun changed() = listeners.toList().forEach { it() }

    override fun dispose() {
        watcher.close()
    }

    companion object {
        fun getInstance(): CanvasService = ApplicationManager.getApplication().getService(CanvasService::class.java)
    }
}

/** Creates the service with the first project, so frames learn their authors and structure writes know the canvases before any canvas is opened. */
class CanvasServiceStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { CanvasService.getInstance() }
    }
}
