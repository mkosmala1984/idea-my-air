package dev.myair.planview

import dev.myair.store.DEFAULT_PROFILE
import dev.myair.store.ProfileStore
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.PlanStart
import dev.myair.editor.SessionTerminals
import dev.myair.folders.ChangeSource
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.NEW_SESSION_TITLE
import dev.myair.store.PlanManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The tab of a folder [folderId] that has a manager session, a plan's or a review's. One instance per folder per IDE, for
 * its lifetime ([FolderTabs.file]): a [LightVirtualFile] compares by identity, so a tab open in a window is focused rather
 * than opened twice.
 */
abstract class FolderTabFile(val folderId: String, name: String) : LightVirtualFile(name, FileTypes.PLAIN_TEXT, ""), EditorHistoryManager.OptionallyIncluded {
    init {
        isWritable = false
    }

    /** `<protocol>://<folderId>`, saved with the open tabs and resolved on startup ([FolderTabFileSystem]). */
    override fun getPath() = folderId

    // Recent Files would hold this object and reopen it unchecked, as for a session tab.
    override fun isIncludedInEditorHistory(project: Project) = false
}

/** A folder tab's path names its folder by id alone. */
fun parseFolderTabPath(path: String): String {
    require(path.isNotEmpty() && path.none { it in "/?&" }) { "not a folder tab path: $path" }
    return path
}

/** Restores folder tabs after a restart: `<protocol>://<folderId>` resolves to that folder's one tab file ([fileOf]). */
abstract class FolderTabFileSystem(private val protocol: String, private val fileOf: (String) -> VirtualFile) : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol() = protocol

    override fun findFileByPath(path: String): VirtualFile {
        val folderId = parseFolderTabPath(path)
        ClaudeSessionsService.getInstance() // starts tracking which session each tab shows
        return fileOf(folderId)
    }

    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)
}

/**
 * The tab file of each folder of one kind and the file of its manager session, one of each per folder per IDE. The manager's
 * file is never opened as a tab: the folder's tab shows its terminal, and `SessionTerminals` keys processes by file identity,
 * so a reopened tab finds the same file and binds a detached process again.
 */
class FolderTabs<F : FolderTabFile>(private val create: (String) -> F) {
    private val files = ConcurrentHashMap<String, F>()
    private val managers = ConcurrentHashMap<String, ClaudeSessionVirtualFile>()

    fun file(folderId: String): F = files.computeIfAbsent(folderId, create)

    fun manager(folderId: String): ClaudeSessionVirtualFile? = managers[folderId]

    /** The manager's file while `SessionTerminals` has its process: identity matters only then. */
    fun running(folderId: String): ClaudeSessionVirtualFile? = managers[folderId]?.takeIf(SessionTerminals.getInstance()::runs)

    fun setManager(folderId: String, file: ClaudeSessionVirtualFile) {
        managers[folderId] = file
    }

    /**
     * The file of [manager], the manager of folder [folderId] as just stored, started with [args]; a new session ([newId])
     * is first filed in the folder, so the tree shows it there.
     */
    fun managerFile(folderId: String, manager: PlanManager, newId: String, args: List<String>, planStart: PlanStart? = null): ClaudeSessionVirtualFile {
        if (manager.session == newId) ClaudeSessionsService.getInstance().updateFolders(ChangeSource.AUTO) { it.assign(newId, folderId) }
        return ClaudeSessionVirtualFile(manager.session, manager.cwd, manager.project, ProfileStore.getInstance().byName(DEFAULT_PROFILE), NEW_SESSION_TITLE, args, planStart = planStart).also { setManager(folderId, it) }
    }

    /**
     * Drops the manager's file of folder [folderId], returning it. The tab file stays for the IDE's lifetime, so a folder
     * that comes back (a history restore) is opened in the same tab rather than beside the one left showing it gone.
     */
    fun forgetManager(folderId: String): ClaudeSessionVirtualFile? = managers.remove(folderId)

    /** The tab whose manager [file] is; null for a session tab's file. */
    fun tabOf(file: ClaudeSessionVirtualFile): F? = managers.entries.firstOrNull { it.value === file }?.key?.let(::file)

    /**
     * Opens the tab of [folderId] in [project], or focuses it there, and returns its editor. A tab left showing its folder
     * gone, which came back since, is closed first, so it opens in a new editor.
     */
    inline fun <reified E : ManagerTabEditor> openHere(project: Project, folderId: String): E {
        val editors = FileEditorManager.getInstance(project)
        val file = file(folderId)
        if (editors.getAllEditors(file).filterIsInstance<E>().any { it.gone }) editors.closeFile(file)
        return editors.openFile(file, true).filterIsInstance<E>().single()
    }

    /** The open editors of the tab of [folderId] in every project of this IDE. */
    inline fun <reified E : FileEditor> editors(folderId: String): List<E> =
        ProjectManager.getInstance().openProjects.flatMap { FileEditorManager.getInstance(it).getAllEditors(file(folderId)).filterIsInstance<E>() }
}
