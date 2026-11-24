package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.EDT
import dev.myair.canvas.CanvasChanges
import dev.myair.canvas.CanvasService
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
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

/** How long `my_air_canvas_show` waits for a canvas page to check the frame. */
private const val VALIDATION_MS = 5_000L

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
            "and estimated when the height is a default rather than the content's; edges; problems with severity, code, ids and message) " +
            "and note. When ok is false, errors are listed or a board has problems, fix the document and show it again.",
    )
    suspend fun my_air_canvas_show(
        @McpDescription("The canvas folder's id, or its path, names joined by /, such as rtm/Canvas: flaky tests; an id is looked up first.") folder: String,
        @McpDescription("The whole OpenUI Lang document, starting with root = Stack(...).") openui: String,
        @McpDescription("A short one-line title of the frame, shown above it; optional.") title: String? = null,
    ): String {
        val shown = withCanvas(folder) { service, id ->
            val frame = checkNotNull(service.update(id) { addFrame(it, title, openui, Instant.now()) }.newest)
            val validation = UUID.randomUUID().toString()
            val browser = CanvasRenderers.getInstance().validator(id)
            Shown(id, ClaudeSessionsService.getInstance().folders.pathOf(id), frame, browser?.let { it to validation }, browser?.validate(validation, openui))
        }
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
        return canvasShowJson(shown.id, shown.path, shown.frame, check)
    }

    @McpTool
    @McpDescription(
        "The frames of a My Air canvas folder as JSON: folder (id), path, manager (session, cwd, project, profile; or null), frames, " +
            "oldest first (id, title, created, author: the session that showed it once My Air learned it from its transcript, else null), " +
            "and newest (the newest frame's id, title, openui (its document) and state (the form and \$variable state the user left in it, or null); null without frames).",
    )
    suspend fun my_air_canvas_get(
        @McpDescription("The canvas folder's id or path, as for my_air_canvas_show.") folder: String,
    ): String = withCanvas(folder) { service, id -> canvasGetJson(id, ClaudeSessionsService.getInstance().folders.pathOf(id), service.canvas(id)) }

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
        val id: String, val path: String, val frame: Frame,
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
