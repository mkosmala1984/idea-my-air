package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.EDT
import dev.myair.canvas.CanvasChanges
import dev.myair.canvas.CanvasService
import dev.myair.canvas.OpenUiMerge
import dev.myair.canvas.patchFrame
import dev.myair.canvas.addFrame
import dev.myair.canvas.canvasLanguage
import dev.myair.canvasview.CanvasBrowser
import dev.myair.canvasview.CanvasMessage
import dev.myair.canvasview.CanvasRenderers
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import java.time.Instant
import java.util.UUID

/** How long `my_air_canvas_show` waits for a canvas page to check the frame. */
private const val VALIDATION_MS = 5_000L

/** How long a snapshot waits for the canvas tab to show and render the frame, and then for the picture. */
private const val SNAPSHOT_WAIT_MS = 4_000L
private const val SNAPSHOT_TAKE_MS = 3_000L

/** How long the frame must have been rendered before its picture is taken: boards fit their view and measure notes. */
private const val SNAPSHOT_SETTLE_MS = 700L

/**
 * Lets any session show OpenUI Lang frames on a My Air canvas folder, read them and change the list. The tools do not
 * know the calling session; a frame learns its author from the caller's transcript later ([dev.myair.canvas.learnAuthors]).
 */
@Suppress("FunctionName")
class CanvasMcpToolset : McpToolset {
    // The default is experimental, which may hide the tools.
    override fun isExperimental() = false

    @McpTool
    @McpDescription(
        "Shows an OpenUI Lang document as a new frame on a My Air canvas folder (a folder whose tab renders generated UI next to its " +
            "manager's terminal; my_air_folders_get lists folders). Unless you are the canvas's manager, whose prompt holds the language, " +
            "call my_air_canvas_language first and follow it. The frame is always stored, the canvas tabs show it, and the canvas keeps " +
            "the newest 50 frames. Then a canvas page of this IDE checks the document, waiting up to 5 s. Returns JSON: folder (id), path, " +
            "frame (its id C<n>), stored (true), validated (whether a page checked it; false when no canvas tab is open, see note), " +
            "ok (true when the document has a root and no error, null when not validated), errors (code, message, statementId, component, " +
            "path, hint, severity error or warning), unresolved (references never defined), orphaned (statements root does not reach, " +
            "which do not show), boards (each Board's geometry: board, unit, elements with id, kind, parent and x, y, w, h in grid units " +
            "and estimated when the height is a default because the page could not measure the content; edges; problems with severity, code, ids and message) " +
            "and note. When ok is false, errors are listed or a board has problems, fix the document and show it again. With snapshot " +
            "true it also returns snapshot: the path of a PNG of the frame as the canvas tab shows it (Read it to see what the user " +
            "sees), or null with snapshotNote saying why.",
    )
    suspend fun my_air_canvas_show(
        @McpDescription("The canvas folder's id, or its path, names joined by /, such as rtm/Canvas: flaky tests; an id is looked up first.") folder: String,
        @McpDescription("The whole OpenUI Lang document, starting with root = Stack(...).") openui: String,
        @McpDescription("A short one-line title of the frame, shown above it; optional.") title: String? = null,
        @McpDescription("Whether to return a PNG of the frame as shown (about a second longer); default false.") snapshot: Boolean = false,
    ): String {
        val shown = withCanvas(folder) { service, id ->
            val frame = checkNotNull(service.update(id) { addFrame(it, title, openui, Instant.now()) }.newest)
            startCheck(id, frame, null)
        }
        return finishCheck(shown, snapshot)
    }

    @McpTool
    @McpDescription(
        "Changes statements of a frame of a My Air canvas folder in place, instead of showing the whole document again: for a board " +
            "you move, add or remove elements one statement at a time. patch is OpenUI Lang statements: one with an existing name " +
            "replaces it, a new name is added, name = null deletes it, and statements root no longer reaches are dropped (so removing an " +
            "element from its parent's list removes it). expect is the frame's rev from my_air_canvas_show, my_air_canvas_get or the last " +
            "patch; when the frame changed since, the patch is refused and the error gives the current rev and document. The patched " +
            "frame keeps its id, title and form state, and is checked like a shown one. Returns what my_air_canvas_show returns, " +
            "with the new rev, plus set (the statements the patch set) and removed (the statements gone); snapshot as for my_air_canvas_show.",
    )
    suspend fun my_air_canvas_patch(
        @McpDescription("The canvas folder's id or path, as for my_air_canvas_show.") folder: String,
        @McpDescription("The frame's id, such as C3.") frame: String,
        @McpDescription("OpenUI Lang statements, one per line: name = expression.") patch: String,
        @McpDescription("The frame's rev the patch is based on.") expect: String,
        @McpDescription("Whether to return a PNG of the frame as shown, as for my_air_canvas_show; default false.") snapshot: Boolean = false,
    ): String {
        val shown = withCanvas(folder) { service, id ->
            var merge: OpenUiMerge? = null
            val canvas = service.update(id) { c -> patchFrame(c, frame, patch, expect).let { (patched, m) -> merge = m; patched } }
            startCheck(id, canvas.frame(frame), merge)
        }
        return finishCheck(shown, snapshot)
    }

    /** Has a canvas page of this IDE start checking [frame]'s document; call on the EDT. */
    private fun startCheck(id: String, frame: Frame, merge: OpenUiMerge?): Shown {
        val validation = UUID.randomUUID().toString()
        val browser = CanvasRenderers.getInstance().validator(id)
        return Shown(id, ClaudeSessionsService.getInstance().folders.pathOf(id), frame, merge, browser?.let { it to validation }, browser?.validate(validation, frame.openui))
    }

    /** Waits for the check [startCheck] started, at most [VALIDATION_MS], and returns the tool's answer, with a snapshot when [snapshot]. */
    private suspend fun finishCheck(shown: Shown, snapshot: Boolean): String {
        val check = shown.answer?.let { answer ->
            try {
                withTimeoutOrNull(VALIDATION_MS) { answer.await() }?.let(CanvasCheck::of) ?: CanvasCheck.unchecked(CanvasCheck.TIMEOUT)
            } catch (e: CancellationException) {
                // The page's browser was disposed (its tab closed); a cancellation of this call itself goes on.
                if (!answer.isCancelled) throw e
                CanvasCheck.unchecked(CanvasCheck.CLOSED)
            } finally {
                shown.browser?.let { (b, v) -> withContext(NonCancellable + Dispatchers.EDT) { b.forget(v) } }
            }
        } ?: CanvasCheck.unchecked(CanvasCheck.NO_PAGE)
        return canvasShowJson(shown.id, shown.path, shown.frame, check, shown.merge, if (snapshot) takeSnapshot(shown.id, shown.frame) else null)
    }

    /**
     * A PNG of [frame] as a canvas tab of [id] shows it, written to `snapshots/<frame>.png` beside `canvas.json`: waits up
     * to [SNAPSHOT_WAIT_MS] for a visible tab to have rendered exactly this document for [SNAPSHOT_SETTLE_MS].
     */
    private suspend fun takeSnapshot(id: String, frame: Frame): CanvasSnapshot {
        val deadline = System.currentTimeMillis() + SNAPSHOT_WAIT_MS
        var page: CanvasBrowser
        while (true) {
            val (found, why) = withContext(Dispatchers.EDT) {
                val pages = CanvasRenderers.getInstance().pages(id).filter { it.shows(frame.id, frame.openui) }
                val visible = pages.firstOrNull { it.isShowing }
                val rendered = visible?.rendered
                when {
                    pages.isEmpty() -> null to CanvasSnapshot.NOT_SHOWN
                    visible == null -> null to CanvasSnapshot.HIDDEN
                    rendered == null || System.currentTimeMillis() - rendered < SNAPSHOT_SETTLE_MS -> null to CanvasSnapshot.NOT_RENDERED
                    else -> visible to null
                }
            }
            if (found != null) {
                page = found
                break
            }
            if (System.currentTimeMillis() > deadline) return CanvasSnapshot(null, why)
            delay(100)
        }
        val png = withContext(Dispatchers.EDT) { page.screenshot() }
        val bytes = runCatching { withTimeoutOrNull(SNAPSHOT_TAKE_MS) { png.await() } }.getOrNull() ?: return CanvasSnapshot(null, CanvasSnapshot.FAILED)
        val file = withContext(Dispatchers.EDT) { CanvasService.getInstance().file(id) }.resolveSibling("snapshots").resolve("${frame.id}.png")
        withContext(Dispatchers.IO) {
            file.parent.createDirectories()
            file.writeBytes(bytes)
        }
        return CanvasSnapshot(file.toString(), null)
    }

    @McpTool
    @McpDescription(
        "The frames of a My Air canvas folder as JSON: folder (id), path, manager (session, cwd, project, profile; or null), frames, " +
            "oldest first (id, title, created, author: the session that showed it once My Air learned it from its transcript, else null), " +
            "newest (the newest frame's id, rev, title, openui (its document) and state (the form and \$variable state the user left in it, or null); null without frames) " +
            "and, when frame names one, frame: that frame the same way, to patch a frame older than the newest.",
    )
    suspend fun my_air_canvas_get(
        @McpDescription("The canvas folder's id or path, as for my_air_canvas_show.") folder: String,
        @McpDescription("A frame's id, such as C3, to get its document too; optional.") frame: String? = null,
    ): String = withCanvas(folder) { service, id -> canvasGetJson(id, ClaudeSessionsService.getInstance().folders.pathOf(id), service.canvas(id), frame) }

    @McpTool
    @McpDescription(
        "Changes the frames of a My Air canvas folder, all or none. changes is a JSON array applied in order: " +
            "{\"op\":\"remove_frame\",\"id\":\"C3\"}, {\"op\":\"set_title\",\"id\":\"C3\",\"title\":\"...\"} (one line; null clears it), " +
            "{\"op\":\"clear\"} (removes every frame; ids are never given again). An unknown op or field fails naming the change. " +
            "Returns the canvas as my_air_canvas_get does.",
    )
    suspend fun my_air_canvas_apply(
        @McpDescription("The canvas folder's id or path, as for my_air_canvas_show.") folder: String,
        @McpDescription("The JSON array of changes.") changes: String,
    ): String = withCanvas(folder) { service, id ->
        canvasGetJson(id, ClaudeSessionsService.getInstance().folders.pathOf(id), service.update(id) { CanvasChanges.apply(it, changes) })
    }

    @McpTool
    @McpDescription(
        "My Air's canvas rules and the OpenUI Lang reference of the canvas's components (about 9k tokens): read it once before your " +
            "first my_air_canvas_show unless you are the canvas's manager, whose prompt holds it already.",
    )
    suspend fun my_air_canvas_language(): String = canvasLanguage()

    private class Shown(
        val id: String, val path: String, val frame: Frame, val merge: OpenUiMerge?,
        /** The page checking the frame and the id of its check. */
        val browser: Pair<CanvasBrowser, String>?, val answer: CompletableDeferred<CanvasMessage.Validated>?,
    )

    /**
     * Runs [block] on the EDT with the canvas folder [folder] names, a folder id or else a path; a folder that is no
     * canvas, an unreadable canvas or an input error reaches the agent with its message.
     */
    private suspend fun <T> withCanvas(folder: String, block: (CanvasService, String) -> T): T = withContext(Dispatchers.EDT) {
        val sessions = ClaudeSessionsService.getInstance()
        sessions.foldersError?.let { throw McpExpectedError("Cannot read ${sessions.structureFile}: $it") }
        try {
            val service = CanvasService.getInstance()
            val id = resolveCanvasFolder(sessions.folders, service.canvases.keys, folder)
            service.canvases.getValue(id).onFailure { throw IllegalArgumentException("the canvas '$folder' cannot be read: ${it.message}", it) }
            block(service, id)
        } catch (e: IllegalArgumentException) {
            throw McpExpectedError(e.message ?: e.toString())
        }
    }
}
