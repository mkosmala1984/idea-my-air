package dev.myair

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.myair.claude.ClaudeHomeWatcher
import dev.myair.claude.ClaudeSession
import dev.myair.claude.ClaudeSessionScanner
import dev.myair.claude.LiveSession
import dev.myair.claude.LiveSessionRegistry
import dev.myair.folders.ChangeSource
import dev.myair.folders.SessionFolders
import dev.myair.folders.StructureHistory
import dev.myair.folders.StructureStore
import dev.myair.store.PinStore
import dev.myair.store.Pins
import dev.myair.store.SeenStore
import dev.myair.store.firstSight
import dev.myair.store.newToUser
import dev.myair.store.pinnedSessions
import dev.myair.store.writeAtomically
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/**
 * The one watcher over `~/.claude` and `~/.my-air`, shared by every project: session list, live statuses, the session each
 * tab shows, and the user's folders. [sessions], [live], [folders] and the listeners are touched on the EDT only.
 */
@Service(Service.Level.APP)
class ClaudeSessionsService : Disposable {
    private val claudeHome = Path.of(System.getProperty("user.home"), ".claude")
    private val scanner = ClaudeSessionScanner(claudeHome.resolve("projects"))
    private val registry = LiveSessionRegistry(claudeHome.resolve("sessions"))
    private val structure = StructureStore(Path.of(System.getProperty("user.home"), ".my-air", "structure.json"))
    private val pinStore = PinStore(structure.file.resolveSibling("pins.json"))

    // In a directory of its own: the watcher above sees `~/.my-air`, and every IDE would rescan on each write.
    private val boardFile = structure.file.resolveSibling("taskbar").createDirectories().resolve("board.json")

    /** What this IDE last wrote to [boardFile]; folder, pin and title changes refresh too, but leave the board as it is. */
    private var boardText: String? = null

    private val seenStore = SeenStore(structure.file.resolveSibling("seen"))

    /** False until the first refresh lands; the first scan reads every transcript and takes seconds. */
    var loaded = false
        private set

    var sessions = emptyList<ClaudeSession>()
        private set

    /** Session id to its running `claude` process; a session absent here has no process. */
    var live = emptyMap<String, LiveSession>()
        private set

    var folders = SessionFolders()
        private set

    /** Why `structure.json` could not be read; Folders mode shows it instead of the tree and allows no changes meanwhile. */
    var foldersError: String? = null
        private set

    /** Running idle sessions with a message the user has not seen, as the taskbar's "Unread" group lists them. */
    var unread = emptySet<String>()
        private set

    /** Pinned and dismissed sessions; see [pinnedSessions] for what the list shows. */
    var pins = Pins()
        private set

    val structureFile: Path get() = structure.file

    val history: StructureHistory get() = structure.history

    private val listeners = ArrayList<() -> Unit>()

    /** One refresh at a time, so an older result never lands after a newer one. */
    private val refresher = AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude sessions refresh", 1)

    // File events drive refreshes; the 60 s rescan covers events the OS dropped, as Air's REREAD_INTERVAL does.
    private val watcher = ClaudeHomeWatcher(listOf(claudeHome.resolve("sessions"), structure.file.parent, seenStore.dir), claudeHome.resolve("projects")) { refresh() }
    private val safetyNet = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::refresh, 60, 60, TimeUnit.SECONDS)

    init {
        refresh()
    }

    /** Calls [listener] on the EDT after every change, until [parent] is disposed. */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun refresh() {
        // Nobody reads the Future, so an exception would vanish with it and freeze the list; log it as an IDE error instead.
        refresher.execute {
            val (scanned, current) = try {
                scanner.scan() to registry.read()
            } catch (e: Throwable) {
                thisLogger().error("Claude session refresh failed", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                // Read on the EDT, where updateFolders writes, so a read from before a drop cannot undo it; on its own,
                // so a broken structure.json does not hide the sessions.
                val (read, failure) = try {
                    structure.read() to null
                } catch (e: Exception) {
                    SessionFolders() to e
                }
                val error = failure?.let { "${it.javaClass.simpleName}: ${it.message}" }
                // Also read where updatePins writes, dropping dismissals that are over (written only when some are);
                // a broken pins.json throws, which the IDE reports as an error.
                val pinned = pinStore.update { it.prune(scanned, current) }
                // Before the check below: a session the user looked at changes the board and nothing else.
                val unreadChanged = updateBoard(scanned, current, pinned.settled)
                if (loaded && !unreadChanged && scanned == sessions && current == live && read == folders && error == foldersError && pinned == pins) return@invokeLater
                if (failure != null && error != foldersError) thisLogger().error("Cannot read ${structure.file}", failure)
                loaded = true
                sessions = scanned
                live = current
                pins = pinned
                folders = read
                foldersError = error
                retargetTabs()
                listeners.forEach { it() }
            }
        }
    }

    /** Called on the EDT when the user may look at a session: a tab selected, the IDE window activated. */
    fun lookedAt() {
        if (!updateBoard(sessions, live, pins.settled)) return
        retargetTabs()
        listeners.forEach { it() }
    }

    /**
     * Marks seen the sessions met for the first time and those whose tab the user looks at now, then writes the board.
     * Every IDE writes the same content, derived from the same files, so it needs no single owner. Returns whether [unread]
     * changed.
     */
    private fun updateBoard(sessions: List<ClaudeSession>, live: Map<String, LiveSession>, settled: Set<String>): Boolean {
        val seen = seenStore.read()
        val now = System.currentTimeMillis()
        val marks = firstSight(sessions, live, seen) + newToUser(SessionHosts.visibleSessions(), sessions, seen).associateWith { now }
        marks.forEach { (id, at) -> seenStore.mark(id, at) }
        val running = boardSessions(sessions, live, seen + marks)
        val board = boardJson(running, recentSessions(sessions, running, settled))
        if (board != boardText) {
            writeAtomically(boardFile, board)
            boardText = board
        }
        val before = unread
        unread = running.filter { it.unread }.map { it.id }.toSet()
        return unread != before
    }

    /** Applies [op] to the folders as they are on disk now and shows the result in every panel of this IDE. */
    fun updateFolders(source: ChangeSource, op: (SessionFolders) -> SessionFolders) {
        check(foldersError == null) { "folders cannot change while ${structure.file} is unreadable: $foldersError" }
        folders = structure.update(source, op)
        listeners.forEach { it() }
    }

    /** Applies [op] to the pins as they are on disk now and shows the result in every panel of this IDE. */
    fun updatePins(op: (Pins) -> Pins) {
        pins = pinStore.update(op)
        // Settling a session takes it out of the flyout's "Recent" group.
        if (updateBoard(sessions, live, pins.settled)) retargetTabs()
        listeners.forEach { it() }
    }

    private fun retargetTabs() {
        val titles = sessions.associate { it.id to it.title }
        var changed = false
        val switched = ArrayList<Pair<String, String>>()
        for (project in ProjectManager.getInstance().openProjects) {
            val editors = FileEditorManagerEx.getInstanceEx(project)
            for (file in editors.openFiles.filterIsInstance<ClaudeSessionVirtualFile>()) {
                val (sessionId, title) = retarget(file.sessionId, file.title, file.pid, live, titles)
                // By pid rather than session id: another process may run the same session ("Open Anyway").
                val status = live.values.firstOrNull { it.pid == file.pid }?.status
                val unread = sessionId in this.unread
                if (sessionId == file.sessionId && title == file.title && status == file.status && unread == file.unread) continue
                if (sessionId != file.sessionId) switched += file.sessionId to sessionId
                // Other IDEs list tabs by session and title only, so a status change alone is not published.
                if (sessionId != file.sessionId || title != file.title) changed = true
                file.sessionId = sessionId
                file.title = title
                file.status = status
                file.unread = unread
                editors.updateFilePresentation(file)
            }
        }
        if (changed) TabsSync.getInstance().publish()
        if (switched.isNotEmpty() && foldersError == null) {
            updateFolders(ChangeSource.AUTO) { switched.fold(it) { folders, (from, to) -> folders.carryOver(from, to, titles.keys) } }
        }
    }

    override fun dispose() {
        safetyNet.cancel(false)
        watcher.close()
    }

    companion object {
        fun getInstance(): ClaudeSessionsService = ApplicationManager.getApplication().getService(ClaudeSessionsService::class.java)

        /**
         * The session and title a tab running process [pid] shows now. `/clear` and `/resume` keep the process but switch its
         * session, which the registry reports under the same pid. A process not (or no longer) registered keeps its session.
         */
        fun retarget(sessionId: String, title: String, pid: Long?, live: Map<String, LiveSession>, titles: Map<String, String>): Pair<String, String> {
            val current = live.entries.firstOrNull { it.value.pid == pid }?.key ?: sessionId
            return current to (titles[current] ?: if (current == sessionId) title else NEW_SESSION_TITLE)
        }

        const val NEW_SESSION_TITLE = "New session"
    }
}
