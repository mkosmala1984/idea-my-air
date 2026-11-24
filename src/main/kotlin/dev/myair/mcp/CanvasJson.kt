package dev.myair.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.myair.canvasview.CanvasBoard
import dev.myair.canvasview.CanvasError
import dev.myair.canvasview.CanvasMessage
import dev.myair.canvas.OpenUiMerge
import dev.myair.canvas.openUiRev
import dev.myair.folders.SessionFolders
import dev.myair.store.Canvas
import dev.myair.store.Frame

private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()

/**
 * The canvas folder [folder] names, a folder id or else a path (names joined by /), among [canvases] (folder ids);
 * throws naming it when there is no such folder or it is no canvas.
 */
fun resolveCanvasFolder(structure: SessionFolders, canvases: Set<String>, folder: String): String {
    val found = requireNotNull(structure.find(folder) ?: structure.findByPath(folder)) { "no folder '$folder'" }
    require(found.id in canvases) { "folder '${structure.pathOf(found.id)}' is not a canvas folder" }
    return found.id
}

/**
 * How the check of a shown frame went: [validated] when a canvas page parsed it, [ok] its verdict then (null otherwise),
 * the geometry of its [boards], and why not or what follows in [note].
 */
data class CanvasCheck(
    val validated: Boolean, val ok: Boolean?, val errors: List<CanvasError>, val unresolved: List<String>, val orphaned: List<String>, val note: String?,
    val boards: List<CanvasBoard> = emptyList(),
) {
    companion object {
        const val NO_PAGE = "no canvas tab is open in this IDE, the frame was stored unchecked"
        const val TIMEOUT = "the canvas page did not answer within 5 s, the frame was stored unchecked"
        const val CLOSED = "the canvas tab closed before it answered, the frame was stored unchecked"
        const val INVALID = "the document or a board's layout has errors and was stored as it is, so the canvas shows what it can of it: " +
            "fix the errors (and the error problems of boards) and show the frame again"
        const val WARNINGS = "the document renders, with warnings"

        fun unchecked(note: String) = CanvasCheck(false, null, emptyList(), emptyList(), emptyList(), note)

        /**
         * The page's answer: invalid ([CanvasMessage.Validated.ok] false) is a document without root, with an error that
         * is not a warning, or with a board problem that is an error.
         */
        fun of(answer: CanvasMessage.Validated) = CanvasCheck(
            true, answer.ok, answer.errors, answer.unresolved, answer.orphaned,
            when {
                !answer.ok -> INVALID
                answer.errors.isNotEmpty() || answer.boards.any { it.problems.isNotEmpty() } -> WARNINGS
                else -> null
            },
            answer.boards,
        )
    }
}

private fun strings(list: List<String>) = JsonArray().also { a -> list.forEach(a::add) }

private fun boardJson(b: CanvasBoard) = JsonObject().apply {
    addProperty("board", b.board)
    addProperty("unit", b.unit)
    add("elements", JsonArray().also { a ->
        b.elements.forEach { e ->
            a.add(JsonObject().apply {
                addProperty("id", e.id)
                addProperty("kind", e.kind)
                addProperty("parent", e.parent)
                addProperty("x", e.x)
                addProperty("y", e.y)
                addProperty("w", e.w)
                addProperty("h", e.h)
                addProperty("estimated", e.estimated)
            })
        }
    })
    add("edges", JsonArray().also { a ->
        b.edges.forEach { e -> a.add(JsonObject().apply { addProperty("id", e.id); addProperty("from", e.from); addProperty("to", e.to) }) }
    })
    add("problems", JsonArray().also { a ->
        b.problems.forEach { p ->
            a.add(JsonObject().apply {
                addProperty("severity", if (p.error) "error" else "warning")
                addProperty("code", p.code)
                add("ids", strings(p.ids))
                addProperty("message", p.message)
            })
        }
    })
}

private fun errorJson(e: CanvasError) = JsonObject().apply {
    addProperty("code", e.code)
    addProperty("message", e.message)
    addProperty("statementId", e.statementId)
    addProperty("component", e.component)
    addProperty("path", e.path)
    addProperty("hint", e.hint)
    addProperty("severity", if (e.warning) "warning" else "error")
}

/**
 * What `my_air_canvas_show` returns: the folder (id and path), the new [frame]'s id and revision, and [check]; for
 * `my_air_canvas_patch` also the statements the [merge] set and removed.
 */
fun canvasShowJson(folderId: String, path: String, frame: Frame, check: CanvasCheck, merge: OpenUiMerge? = null): String = gson.toJson(
    JsonObject().apply {
        addProperty("folder", folderId)
        addProperty("path", path)
        addProperty("frame", frame.id)
        addProperty("rev", openUiRev(frame.openui))
        if (merge != null) {
            add("set", strings(merge.set))
            add("removed", strings(merge.removed))
        }
        addProperty("stored", true)
        addProperty("validated", check.validated)
        addProperty("ok", check.ok)
        add("errors", JsonArray().also { a -> check.errors.forEach { a.add(errorJson(it)) } })
        add("unresolved", strings(check.unresolved))
        add("orphaned", strings(check.orphaned))
        add("boards", JsonArray().also { a -> check.boards.forEach { a.add(boardJson(it)) } })
        addProperty("note", check.note)
    },
)

/** What `my_air_canvas_get` and `my_air_canvas_apply` return: the frames without their documents, and the newest frame whole. */
fun canvasGetJson(folderId: String, path: String, canvas: Canvas): String = gson.toJson(
    JsonObject().apply {
        addProperty("folder", folderId)
        addProperty("path", path)
        add("manager", canvas.manager?.let { m ->
            JsonObject().apply {
                addProperty("session", m.session)
                addProperty("cwd", m.cwd.toString())
                addProperty("project", m.project.toString())
                addProperty("profile", m.profile)
            }
        } ?: JsonNull.INSTANCE)
        add("frames", JsonArray().also { a ->
            canvas.frames.forEach { f ->
                a.add(JsonObject().apply {
                    addProperty("id", f.id)
                    addProperty("rev", openUiRev(f.openui))
                    addProperty("title", f.title)
                    addProperty("created", f.created.toString())
                    addProperty("author", f.author)
                })
            }
        })
        add("newest", canvas.newest?.let { f ->
            JsonObject().apply {
                addProperty("id", f.id)
                addProperty("rev", openUiRev(f.openui))
                addProperty("title", f.title)
                addProperty("openui", f.openui)
                add("state", f.state ?: JsonNull.INSTANCE)
            }
        } ?: JsonNull.INSTANCE)
    },
)
