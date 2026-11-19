package dev.myair.planview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.PlanStart
import dev.myair.editor.SessionTerminals
import dev.myair.editor.hasTranscript
import dev.myair.editor.projectBase
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.NEW_SESSION_TITLE
import dev.myair.store.Homes
import dev.myair.store.PlanManager
import dev.myair.store.PlanManagerStore
import dev.myair.store.PlanManagers
import dev.myair.claude.LiveSession
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The tab of the plan of folder [folderId], `myair-plan://<folderId>`. */
class PlanVirtualFile(folderId: String) : FolderTabFile(folderId, "Plan") {
    override fun getFileSystem() = PlanFileSystem.instance
}

class PlanFileSystem : FolderTabFileSystem(PROTOCOL, { PlanFiles.getInstance().file(it) }) {
    companion object {
        const val PROTOCOL = "myair-plan"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as PlanFileSystem
    }
}

/**
 * How a plan's manager starts, from its stored entry [existing]: none yet gives a new session [newId] in [cwd] and
 * [project]; an entry resumes its session in the directories stored with it, or starts it again under its id when it was
 * closed before its first message left a transcript.
 */
fun managerStart(existing: PlanManager?, newId: String, cwd: Path, project: Path, hasTranscript: (String) -> Boolean): Pair<PlanManager, List<String>> {
    if (existing == null) return PlanManager(newId, cwd, project) to listOf("--session-id", newId)
    return existing to listOf(if (hasTranscript(existing.session)) "--resume" else "--session-id", existing.session)
}

/**
 * Why session [sessionId] cannot become the manager of the plan of folder [folderId], null when it can: it manages a plan
 * already, it is unknown ([known]: scanned, or its process runs here), a background job runs it ([live]), it runs outside
 * this IDE ([live] with no process here), or its process here is still [starting], so a message cannot reach it yet.
 */
fun managerRefusal(
    managers: PlanManagers, folderId: String, sessionId: String, known: Boolean, live: LiveSession?, runsHere: Boolean, starting: Boolean,
    pathOf: (String) -> String,
): String? {
    managers.folderOf(sessionId)?.let { return if (it == folderId) "session $sessionId manages this plan already" else "session $sessionId manages the plan '${pathOf(it)}'" }
    return when {
        !known -> "no session $sessionId"
        live?.job != null -> "session $sessionId is run by the background job ${live.job}"
        live != null && !runsHere -> "session $sessionId runs outside this IDE (pid ${live.pid}); it must end there first"
        starting -> "session $sessionId is still starting; call again in a few seconds"
        else -> null
    }
}

/** The tab file of each plan folder and the file of its manager session ([FolderTabs]). */
@Service(Service.Level.APP)
class PlanFiles {
    val store = PlanManagerStore(Homes.myAir.resolve("plan-managers.json"))
    val tabs = FolderTabs(::PlanVirtualFile)
    /**
     * Arguments for a plan's manager, by folder id, added after `--session-id`/`--resume` when [startManager] next builds
     * its file: the first prompt after `--`, and for a new session the flags it starts with.
     */
    private val pendingArgs = ConcurrentHashMap<String, List<String>>()

    fun file(folderId: String): PlanVirtualFile = tabs.file(folderId)

    fun manager(folderId: String): ClaudeSessionVirtualFile? = tabs.manager(folderId)

    /**
     * The manager session's file of plan [folder], kept while `SessionTerminals` has its process, else built again from its
     * entry in the store (a new session when there is none, filed in the plan folder so the tree shows it under `no task`).
     * A new manager runs where sessions started from the folder do, else in [ideProject]'s directory; an entry keeps the
     * directories it was stored with.
     */
    fun startManager(folder: Folder, structure: SessionFolders, ideProject: Project): ClaudeSessionVirtualFile {
        ThreadingAssertions.assertEventDispatchThread()
        // Once the process is gone, the store's entry decides the session and its args.
        tabs.running(folder.id)?.let { return it }
        checkNotNull(folder.plan) { "folder ${folder.id} has no plan" }
        val cwd = structure.startFolder(folder.id)?.startDir?.let(Path::of) ?: projectBase(ideProject)
        val newId = UUID.randomUUID().toString()
        lateinit var args: List<String>
        val manager = store.update { stored ->
            val (manager, start) = managerStart(stored.byFolder[folder.id], newId, cwd, cwd) { hasTranscript(it, Homes.claude) }
            args = start + pendingArgs.remove(folder.id).orEmpty()
            stored.prune(structure.all().map { it.id }.toSet()).set(folder.id, manager)
        }.byFolder.getValue(folder.id)
        if (manager.session == newId) ClaudeSessionsService.getInstance().updateFolders(ChangeSource.AUTO) { it.assign(newId, folder.id) }
        val file = ClaudeSessionVirtualFile(manager.session, manager.cwd, manager.project, NEW_SESSION_TITLE, args, planStart = PlanStart(folder.id, null))
        tabs.setManager(folder.id, file)
        return file
    }

    /**
     * Makes [manager]'s session, which manages no plan yet, the manager of the plan of [folderId], replacing the one it had,
     * whose id it returns. [running] is the file of its process in this IDE, kept as the manager's file so the plan tab binds
     * that process rather than starting another; without one, the process the plan tab starts gets [firstArgs] (a first prompt
     * after `--`, flags before it). The previous manager's process runs on detached, whatever the settings say: it may be the
     * caller of the replacement, waiting for its answer. Open plan tabs show the new manager after [PlanEditor.showManager].
     */
    fun adopt(folderId: String, manager: PlanManager, running: ClaudeSessionVirtualFile?, firstArgs: List<String>): String? {
        ThreadingAssertions.assertEventDispatchThread()
        var previous: String? = null
        store.update { stored ->
            check(stored.folderOf(manager.session) == null) { "session ${manager.session} manages plan ${stored.folderOf(manager.session)} already" }
            previous = stored.byFolder[folderId]?.session
            stored.set(folderId, manager)
        }
        tabs.forgetManager(folderId)?.let(SessionTerminals.getInstance()::detach)
        if (running == null) pendingArgs[folderId] = firstArgs
        else {
            check(running.sessionId == manager.session && SessionTerminals.getInstance().runs(running)) { "no process of session ${manager.session} in ${running.sessionId}'s file" }
            pendingArgs.remove(folderId)
            tabs.setManager(folderId, running)
        }
        return previous
    }

    /** The plan whose manager [file] is; null for a session tab's file. */
    fun planOf(file: ClaudeSessionVirtualFile): PlanVirtualFile? = tabs.tabOf(file)

    /** Drops the manager's file of a plan gone ([FolderTabs.forgetManager]). */
    fun forgetManager(folderId: String) {
        tabs.forgetManager(folderId)
    }

    companion object {
        fun getInstance(): PlanFiles = service()
    }
}
