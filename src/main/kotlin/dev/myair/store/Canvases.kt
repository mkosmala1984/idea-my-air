package dev.myair.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * One OpenUI Lang document a session showed on a canvas: [id] `C<n>`, an optional one-line [title], the [openui] text,
 * when it was [created], the session that showed it ([author], null until learned from its transcript) and the latest
 * form and `$variable` [state] the page reported for it (null before any).
 */
data class Frame(val id: String, val title: String?, val openui: String, val created: Instant, val author: String?, val state: JsonObject?)

/** The content of a canvas folder: its [manager], the next frame number [next] (never lowered) and its [frames], oldest first. */
data class Canvas(val manager: PlanManager?, val next: Int, val frames: List<Frame>) {
    fun frame(id: String): Frame = requireNotNull(frames.firstOrNull { it.id == id }) { "no frame '$id'" }

    val newest: Frame? get() = frames.lastOrNull()
}

/** The format `canvas.json` is written in; a file of another format throws. */
const val CANVAS_FORMAT = 1

/** At most this many frames are kept; the oldest go first. */
const val MAX_FRAMES = 50

/** [canvas] with at most [max] frames, the newest. */
fun pruneFrames(canvas: Canvas, max: Int = MAX_FRAMES): Canvas = if (canvas.frames.size <= max) canvas else canvas.copy(frames = canvas.frames.takeLast(max))

private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()

fun Canvas.toJson(): String = GSON.toJson(
    JsonObject().apply {
        addProperty("format", CANVAS_FORMAT)
        add("manager", manager?.let { m -> JsonObject().also { o -> m.fields().forEach { (k, v) -> o.addProperty(k, v) } } } ?: JsonNull.INSTANCE)
        addProperty("next", next)
        add("frames", JsonArray().also { a ->
            frames.forEach { f ->
                a.add(JsonObject().apply {
                    addProperty("id", f.id)
                    addProperty("title", f.title)
                    addProperty("openui", f.openui)
                    addProperty("created", f.created.toString())
                    addProperty("author", f.author)
                    add("state", f.state ?: JsonNull.INSTANCE)
                })
            }
        })
    },
)

/** Parses `canvas.json`: a missing or unknown key, a wrong type or another [CANVAS_FORMAT] throws naming [source]. */
fun parseCanvas(text: String, source: Path): Canvas {
    val json = JsonParser.parseString(text)
    // The format first: a newer one may bring keys this build does not know, and should say so.
    val format = Keys(json, "canvas", source, setOf("format"), json.takeIf { it.isJsonObject }?.asJsonObject?.keySet().orEmpty()).int("format")
    check(format <= CANVAS_FORMAT) { "$source: format $format is newer than $CANVAS_FORMAT, which this My Air reads; update My Air" }
    check(format == CANVAS_FORMAT) { "$source: format is $format, not $CANVAS_FORMAT" }
    val root = Keys(json, "canvas", source, setOf("format", "manager", "next", "frames"))
    return Canvas(
        manager = if (!root.element("manager").isJsonNull) Keys(root.element("manager"), "manager", source, setOf("session", "cwd", "project"), setOf("profile")).run {
            PlanManager(string("session"), Path.of(string("cwd")), Path.of(string("project")), if (has("profile")) string("profile") else DEFAULT_PROFILE)
        } else null,
        next = root.int("next"),
        frames = root.array("frames").map {
            Keys(it, "frame", source, setOf("id", "title", "openui", "created", "author", "state")).run {
                val state = element("state")
                check(state.isJsonNull || state.isJsonObject) { "$source: state of frame ${string("id")} is not an object: $state" }
                val created = string("created").let { s ->
                    try { Instant.parse(s) } catch (e: DateTimeParseException) { throw IllegalStateException("$source: created of frame ${string("id")} is not an instant: $s", e) }
                }
                Frame(string("id"), stringOrNull("title"), string("openui"), created, stringOrNull("author"), if (state.isJsonNull) null else state.asJsonObject)
            }
        },
    )
}

/** `<root>/<folderId>/canvas.json` of every canvas folder (`~/.my-air/canvases`); [readAll] keeps a broken one as a failure. */
class CanvasFiles(val root: Path) {
    fun file(folderId: String): Path = root.resolve(folderId).resolve("canvas.json")

    fun readAll(): Map<String, Result<Canvas>> {
        if (!root.exists()) return emptyMap()
        return root.listDirectoryEntries().filter { it.isDirectory() && file(it.name).exists() }.associate {
            val f = file(it.name)
            it.name to runCatching { parseCanvas(f.readText(), f) }
        }
    }

    fun exists(folderId: String) = file(folderId).exists()

    fun read(folderId: String): Canvas = file(folderId).let { parseCanvas(it.readText(), it) }

    /** Writes [canvas] pruned to [MAX_FRAMES]; returns what was written. */
    fun write(folderId: String, canvas: Canvas): Canvas {
        val pruned = pruneFrames(canvas)
        file(folderId).parent.createDirectories()
        writeAtomically(file(folderId), pruned.toJson())
        return pruned
    }
}
