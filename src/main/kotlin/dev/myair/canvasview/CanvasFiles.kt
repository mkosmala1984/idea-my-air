package dev.myair.canvasview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.canvas.CANVAS_MANAGER_FIRST_MESSAGE
import dev.myair.canvas.CanvasService
import dev.myair.canvas.canvasManagerPrompt
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.projectBase
import dev.myair.folders.ChangeSource
import dev.myair.folders.SessionFolders
import dev.myair.planview.FolderTabFile
import dev.myair.planview.FolderTabFileSystem
import dev.myair.planview.FolderTabs
import dev.myair.planview.hasManagerTranscript
import dev.myair.planview.managerStart
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Canvas
import dev.myair.store.DEFAULT_PROFILE
import dev.myair.store.PlanManager
import java.nio.file.Path
import java.util.UUID

/** The tab of the canvas of folder [folderId], `myair-canvas://<folderId>`. */
class CanvasVirtualFile(folderId: String) : FolderTabFile(folderId, "Canvas") {
    override fun getFileSystem() = CanvasFileSystem.instance
}

class CanvasFileSystem : FolderTabFileSystem(PROTOCOL, { CanvasTabs.getInstance().tabs.file(it) }) {
    companion object {
        const val PROTOCOL = "myair-canvas"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as CanvasFileSystem
    }
}

/**
 * The directory a new manager of the canvas of folder [folderId] runs in, and where the canvas's relative file paths
 * resolve: the nearest working directory or bound directory of the folder and its ancestors ([SessionFolders.startFolder],
 * as for a session started from the folder); null when none has one.
 */
fun canvasStartDir(structure: SessionFolders, folderId: String): Path? = structure.startFolder(folderId)?.startDir?.let(Path::of)

/**
 * How [canvas]'s manager starts ([managerStart]): a new one in [cwd] (asked only then), which is also its project, in
 * `default`'s profile; a stored one where and in the profile it was stored. [firstArgs], the arguments a handover left
 * ([FolderTabs.adopt]), follow; without them a session with no transcript yet gets [CANVAS_MANAGER_FIRST_MESSAGE] after
 * `--`, a resumed one nothing.
 */
fun canvasManagerStart(
    canvas: Canvas, newId: String, cwd: () -> Path, firstArgs: List<String>? = null, hasTranscript: (String, String) -> Boolean,
): Pair<PlanManager, List<String>> {
    // A stored manager keeps its directories ([managerStart] ignores these).
    val dir = canvas.manager?.cwd ?: cwd()
    val (manager, args) = managerStart(canvas.manager, newId, dir, dir, DEFAULT_PROFILE, hasTranscript)
    return manager to args + (firstArgs ?: if (args.first() == "--session-id") listOf("--", CANVAS_MANAGER_FIRST_MESSAGE) else emptyList())
}

/** The tab file of each canvas folder and the file of its manager session ([FolderTabs]); the manager is stored in `canvas.json`. */
@Service(Service.Level.APP)
class CanvasTabs {
    val tabs = FolderTabs(::CanvasVirtualFile)

    /**
     * The manager session's file of the canvas of [folderId], kept while `SessionTerminals` has its process, else built
     * again from the canvas's `manager`. With none stored, a new session runs in [canvasStartDir], else in [ideProject]'s
     * directory; it is stored in `canvas.json` and filed in the canvas folder.
     */
    fun startManager(folderId: String, ideProject: Project): ClaudeSessionVirtualFile {
        ThreadingAssertions.assertEventDispatchThread()
        tabs.running(folderId)?.let { return it }
        val newId = UUID.randomUUID().toString()
        lateinit var args: List<String>
        val cwd = { canvasStartDir(ClaudeSessionsService.getInstance().folders, folderId) ?: projectBase(ideProject) }
        val firstArgs = tabs.takeFirstArgs(folderId)
        val manager = checkNotNull(CanvasService.getInstance().update(folderId) { canvas ->
            val (manager, start) = canvasManagerStart(canvas, newId, cwd, firstArgs, ::hasManagerTranscript)
            args = start
            canvas.copy(manager = manager)
        }.manager)
        return tabs.managerFile(folderId, manager, newId, args)
    }

    /**
     * Makes [manager] the manager of the canvas of [folderId], replacing the one it had, whose id it returns with the tab:
     * stores it in `canvas.json`, files its session in the canvas folder, binds it ([FolderTabs.adopt]: [running] is its
     * process here, else the tab starts it with [firstArgs]), shows it in the open tabs and opens the tab in [project]. EDT.
     */
    fun adopt(project: Project, folderId: String, manager: PlanManager, running: ClaudeSessionVirtualFile?, firstArgs: List<String>): Pair<CanvasEditor, String?> {
        ThreadingAssertions.assertEventDispatchThread()
        var previous: String? = null
        CanvasService.getInstance().update(folderId) { previous = it.manager?.session; it.copy(manager = manager) }
        ClaudeSessionsService.getInstance().updateFolders(ChangeSource.MCP) { it.assign(manager.session, folderId) }
        tabs.adopt(folderId, manager, running, firstArgs)
        tabs.editors<CanvasEditor>(folderId).forEach(CanvasEditor::showManager)
        return CanvasOpener.openHere(project, folderId) to previous
    }

    /** The profile to check before the manager of the canvas of [folderId] starts ([FolderTabs.profileToCheck]); one not made yet is `default`'s. */
    fun profileToCheck(folderId: String): String? =
        tabs.profileToCheck(folderId) { CanvasService.getInstance().canvas(folderId).manager?.profile ?: DEFAULT_PROFILE }

    /** The canvas whose manager [file] is; null for any other session's file. */
    fun canvasOf(file: ClaudeSessionVirtualFile): CanvasVirtualFile? = tabs.tabOf(file)

    /** The system prompt of [file] when it is a canvas's manager, written to a file at every start of its process; else null. */
    fun managerPrompt(file: ClaudeSessionVirtualFile): String? = canvasOf(file)?.folderId?.let {
        canvasManagerPrompt(it, CanvasService.getInstance().canvas(it))
    }

    companion object {
        fun getInstance(): CanvasTabs = service()
    }
}
