package dev.myair.planview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.LightVirtualFile
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
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The tab of the plan of folder [folderId]. One instance per folder per IDE, for its lifetime ([PlanFiles.file]): a
 * [LightVirtualFile] compares by identity, so a plan open in a window is focused rather than opened twice.
 */
class PlanVirtualFile(val folderId: String) : LightVirtualFile("Plan", FileTypes.PLAIN_TEXT, ""), EditorHistoryManager.OptionallyIncluded {
    init {
        isWritable = false
    }

    override fun getFileSystem() = PlanFileSystem.instance

    /** `myair-plan://<folderId>`, saved with the open tabs and resolved on startup ([PlanFileSystem]). */
    override fun getPath() = folderId

    // Recent Files would hold this object and reopen it unchecked, as for a session tab.
    override fun isIncludedInEditorHistory(project: Project) = false
}

/** A plan tab's path names its folder by id alone. */
fun parsePlanTabPath(path: String): String {
    require(path.isNotEmpty() && path.none { it in "/?&" }) { "not a plan tab path: $path" }
    return path
}

/** Restores plan tabs after a restart: `myair-plan://<folderId>` resolves to that plan's one [PlanVirtualFile]. */
class PlanFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol() = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile {
        val folderId = parsePlanTabPath(path)
        ClaudeSessionsService.getInstance() // starts tracking which session each tab shows
        return PlanFiles.getInstance().file(folderId)
    }

    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

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
 * The tab file of each plan folder and the file of its manager session, one of each per folder per IDE. The manager's
 * file is never opened as a tab: the plan tab shows its terminal, and `SessionTerminals` keys processes by file identity,
 * so a reopened plan tab finds the same file and binds a detached process again.
 */
@Service(Service.Level.APP)
class PlanFiles {
    val store = PlanManagerStore(Homes.myAir.resolve("plan-managers.json"))
    private val files = ConcurrentHashMap<String, PlanVirtualFile>()
    private val managers = ConcurrentHashMap<String, ClaudeSessionVirtualFile>()
    /** A message for a plan's manager, by folder id, sent as the first prompt when [startManager] next builds its file. */
    private val firstMessages = ConcurrentHashMap<String, String>()

    fun file(folderId: String): PlanVirtualFile = files.computeIfAbsent(folderId, ::PlanVirtualFile)

    fun manager(folderId: String): ClaudeSessionVirtualFile? = managers[folderId]

    /**
     * The manager session's file of plan [folder], kept while `SessionTerminals` has its process, else built again from its
     * entry in the store (a new session when there is none, filed in the plan folder so the tree shows it under `no task`).
     * A new manager runs where sessions started from the folder do, else in [ideProject]'s directory; an entry keeps the
     * directories it was stored with.
     */
    fun startManager(folder: Folder, structure: SessionFolders, ideProject: Project): ClaudeSessionVirtualFile {
        ThreadingAssertions.assertEventDispatchThread()
        // Identity matters only while a process runs; once it is gone, the store's entry decides the session and its args.
        managers[folder.id]?.takeIf(SessionTerminals.getInstance()::runs)?.let { return it }
        checkNotNull(folder.plan) { "folder ${folder.id} has no plan" }
        val cwd = structure.startFolder(folder.id)?.startDir?.let(Path::of) ?: projectBase(ideProject)
        val newId = UUID.randomUUID().toString()
        lateinit var args: List<String>
        val manager = store.update { stored ->
            val (manager, start) = managerStart(stored.byFolder[folder.id], newId, cwd, cwd) { hasTranscript(it, Homes.claude) }
            args = start + firstMessages.remove(folder.id).let { if (it == null) emptyList() else listOf("--", it) }
            stored.prune(structure.all().map { it.id }.toSet()).set(folder.id, manager)
        }.byFolder.getValue(folder.id)
        if (manager.session == newId) ClaudeSessionsService.getInstance().updateFolders(ChangeSource.AUTO) { it.assign(newId, folder.id) }
        val file = ClaudeSessionVirtualFile(manager.session, manager.cwd, manager.project, NEW_SESSION_TITLE, args, planStart = PlanStart(folder.id, null))
        managers[folder.id] = file
        return file
    }

    /**
     * Makes [manager]'s session, which manages no plan yet, the manager of the plan of [folderId]. [running] is the file of
     * its process in this IDE, kept as the manager's file so the plan tab binds that process rather than starting another;
     * without one, [message] goes to the session as the first prompt of the process the plan tab starts.
     */
    fun adopt(folderId: String, manager: PlanManager, running: ClaudeSessionVirtualFile?, message: String) {
        ThreadingAssertions.assertEventDispatchThread()
        store.update { stored ->
            check(stored.folderOf(manager.session) == null) { "session ${manager.session} manages plan ${stored.folderOf(manager.session)} already" }
            stored.set(folderId, manager)
        }
        if (running == null) firstMessages[folderId] = message
        else {
            check(running.sessionId == manager.session && SessionTerminals.getInstance().runs(running)) { "no process of session ${manager.session} in ${running.sessionId}'s file" }
            managers[folderId] = running
        }
    }

    /** The plan whose manager [file] is; null for a session tab's file. */
    fun planOf(file: ClaudeSessionVirtualFile): PlanVirtualFile? = managers.entries.firstOrNull { it.value === file }?.key?.let(::file)

    /**
     * Drops the manager's file of a plan gone. The tab file stays for the IDE's lifetime, so a plan that comes back (a
     * history restore, Add Plan again) is opened in the same tab rather than beside the one left showing it gone.
     */
    fun forgetManager(folderId: String) {
        managers.remove(folderId)
    }

    companion object {
        fun getInstance(): PlanFiles = service()
    }
}
