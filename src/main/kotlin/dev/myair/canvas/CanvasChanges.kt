package dev.myair.canvas

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.myair.claude.ShownFrame
import dev.myair.store.Canvas
import dev.myair.store.Frame
import dev.myair.store.Keys
import dev.myair.store.pruneFrames
import java.nio.file.Path
import java.time.Instant

/** The canvas folder of [canvases] whose manager is session [sessionId]; a canvas that cannot be read names no manager. */
fun managedCanvasFolder(canvases: Map<String, Result<Canvas>>, sessionId: String): String? =
    canvases.entries.firstOrNull { it.value.getOrNull()?.manager?.session == sessionId }?.key

private fun requireTitle(title: String?) =
    require(title == null || (title.isNotBlank() && title.none { it == '\n' || it == '\r' })) { "title must be one line, not blank, got '$title'" }

/** [canvas] with a new newest frame `C<next>` of [openui], pruned to the frames kept; the new frame is `newest`. */
fun addFrame(canvas: Canvas, title: String?, openui: String, now: Instant): Canvas {
    require(openui.isNotBlank()) { "openui is empty" }
    requireTitle(title)
    val frame = Frame("C${canvas.next}", title, openui, now, null, null)
    return pruneFrames(canvas.copy(next = canvas.next + 1, frames = canvas.frames + frame))
}

private fun Canvas.edit(id: String, edit: (Frame) -> Frame): Canvas = frame(id).let { f -> copy(frames = frames.map { if (it === f) edit(f) else it }) }

/** [canvas] with the form and `$variable` [state] the page reported for [frame]; a frame no longer kept throws. */
fun setState(canvas: Canvas, frame: String, state: JsonObject?): Canvas = canvas.edit(frame) { it.copy(state = state) }

/** [canvas] with [session] as the author of [frame]; an author once learned is kept. */
fun setAuthor(canvas: Canvas, frame: String, session: String): Canvas = canvas.edit(frame) { if (it.author == null) it.copy(author = session) else it }

/**
 * Gives each frame of the canvas of folder [folderId] without an author the session whose transcript shows the
 * `my_air_canvas_show` call that added it ([shows]: by session id, the frames each one showed); frames no longer kept and
 * other folders' frames are skipped.
 */
fun learnAuthors(canvas: Canvas, folderId: String, shows: Map<String, List<ShownFrame>>): Canvas =
    shows.entries.fold(canvas) { c, (session, frames) ->
        frames.filter { it.folder == folderId && c.frames.any { f -> f.id == it.frame && f.author == null } }.fold(c) { acc, it -> setAuthor(acc, it.frame, session) }
    }

/** An agent's change list for a canvas (a JSON array of `{"op": ...}` objects), applied all or none. */
object CanvasChanges {
    /** The keys each op requires, besides `op`. */
    private val REQUIRED = mapOf(
        "remove_frame" to setOf("id"),
        "set_title" to setOf("id", "title"),
        "clear" to emptySet(),
    )
    private val SOURCE = Path.of("changes")

    fun apply(canvas: Canvas, changes: String): Canvas {
        val list = try {
            JsonParser.parseString(changes).also { require(it.isJsonArray) { "changes must be a JSON array, got $it" } }.asJsonArray.toList()
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("changes are not JSON: ${e.message}", e)
        }
        return list.withIndex().fold(canvas) { c, (i, change) ->
            try {
                applyOne(c, change)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            }
        }
    }

    private fun applyOne(canvas: Canvas, change: JsonElement): Canvas {
        require(change.isJsonObject) { "not an object" }
        val op = change.asJsonObject["op"]?.takeIf { it.isJsonPrimitive }?.asString
        val required = requireNotNull(REQUIRED[op]) { "unknown op '$op', expected one of ${REQUIRED.keys}" }
        val k = Keys(change, "op $op", SOURCE, required + "op")
        return when (op) {
            "remove_frame" -> canvas.frame(k.string("id")).let { f -> canvas.copy(frames = canvas.frames - f) }
            "set_title" -> k.stringOrNull("title").let { title -> requireTitle(title); canvas.edit(k.string("id")) { it.copy(title = title) } }
            // `next` stays: a frame id is never given twice, so a click on an old frame cannot reach a new one.
            "clear" -> canvas.copy(frames = emptyList())
            else -> error("unreachable op $op")
        }
    }
}

/**
 * [canvas] with [patch] merged into frame [frame]'s document ([mergeOpenUi]), kept in place with its id, title and
 * state, and the merge. [expect] is the revision ([openUiRev]) the patch was written against; any other revision
 * throws, naming the current one and giving the current document, so a change made in between is never overwritten.
 */
fun patchFrame(canvas: Canvas, frame: String, patch: String, expect: String): Pair<Canvas, OpenUiMerge> {
    val f = canvas.frame(frame)
    val rev = openUiRev(f.openui)
    require(expect == rev) {
        "frame $frame is at rev $rev, not $expect: it changed since you read it. Patch it again with expect \"$rev\"; its document now is:\n${f.openui}"
    }
    val merge = mergeOpenUi(f.openui, patch)
    return canvas.edit(frame) { it.copy(openui = merge.text) } to merge
}
