package dev.myair.canvasview

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// The plugin's side of the canvas page's protocol: `canvas-web/src/protocol.ts` documents it, and
// `canvas-web/src/protocol.json` lists its names for the tests of both sides (CanvasProtocolTest).

/** What the canvas page shows: frame [frame]'s OpenUI Lang text [openui], its saved form [state], in the IDE's theme. */
data class CanvasFrame(val frame: String, val openui: String, val dark: Boolean, val state: JsonObject? = null)

/** The script that shows [frame] in the page (`window.myAir.show`); the JSON goes in as a string literal. */
fun showScript(frame: CanvasFrame): String = call("show", Gson().toJson(frame))

/**
 * The script that has the page parse [openui] against its library without rendering it (`window.myAir.validate`);
 * the page answers with [CanvasMessage.Validated] carrying [id].
 */
fun validateScript(id: String, openui: String): String = call("validate", Gson().toJson(mapOf("id" to id, "openui" to openui)))

/**
 * The script run once the page has loaded: it defines `window.myAirPost`, whose body [postBody] is the JBCefJSQuery's
 * injection for the argument `json`, then lets the page post what it queued (`window.myAir.connect`).
 */
fun connectScript(postBody: String): String = "window.myAirPost = function(json) { $postBody }; window.myAir.connect();"

private fun call(function: String, json: String) = "window.myAir.$function(${Gson().toJson(json)});"

/** An error of the page's parser or renderer (OpenUIError, trimmed by `canvas-web/src/bridge.ts`). */
data class CanvasError(
    val code: String,
    val message: String,
    /** The statement to fix, such as `header` in `header = CardHeader(...)`. */
    val statementId: String? = null,
    val component: String? = null,
    /** A JSON pointer into the component's props, such as `/title`. */
    val path: String? = null,
    val hint: String? = null,
    /** The document still renders (`severity: "warning"`). */
    val warning: Boolean = false,
)

/** A message the canvas page posts through `window.myAirPost` (`PageMessage` in `canvas-web/src/protocol.ts`). */
sealed interface CanvasMessage {
    /** The page rendered for the first time, [ms] after its script started. */
    data class Ready(val ms: Long) : CanvasMessage

    /**
     * [frame] parsed; [ok] when it has a root. [orphaned] statements are not reachable from `root` and do not show,
     * [unresolved] references are used but never defined.
     */
    data class Parsed(
        val frame: String, val ms: Long, val ok: Boolean, val orphaned: List<String>, val unresolved: List<String>, val errors: List<CanvasError>,
    ) : CanvasMessage

    /** The answer to [validateScript] [id]: [ok] when the document has a root and no error (warnings allowed). */
    data class Validated(
        val id: String, val ok: Boolean, val errors: List<CanvasError>, val unresolved: List<String>, val orphaned: List<String>,
    ) : CanvasMessage

    /** The renderer's errors on [frame]; empty once they are resolved. */
    data class Errors(val frame: String, val errors: List<CanvasError>) : CanvasMessage

    /** The form and `$variable` state of [frame] changed (the page debounces it and posts only a change). */
    data class State(val frame: String, val state: JsonObject) : CanvasMessage

    /**
     * A click on [frame]: [type] is `continue_conversation`, `open_url` or a custom action's name (`OpenFile`,
     * `CopyToClipboard`), [params] its arguments (`url` for `open_url`, `context` for `@ToAssistant`). [formName] and
     * [formState] are set only when the click came from a form; the state is that form's, keyed by its name.
     */
    data class Action(
        val frame: String, val type: String, val message: String, val params: JsonObject, val formName: String?, val formState: JsonObject?,
    ) : CanvasMessage

    /** An uncaught script error in the page. */
    data class ScriptError(val message: String) : CanvasMessage

    companion object {
        /** Parses a posted message; an unknown type or a missing field throws, so a changed page fails loudly. */
        fun parse(json: String): CanvasMessage {
            val o = JsonParser.parseString(json).asJsonObject
            val r = Fields(o, json)
            return when (val type = r.str("type")) {
                "ready" -> Ready(r.long("ms"))
                "parsed" -> Parsed(r.str("frame"), r.long("ms"), r.bool("ok"), r.strings("orphaned"), r.strings("unresolved"), r.errors("errors"))
                "validated" -> Validated(r.str("id"), r.bool("ok"), r.errors("errors"), r.strings("unresolved"), r.strings("orphaned"))
                "errors" -> Errors(r.str("frame"), r.errors("errors"))
                "state" -> State(r.str("frame"), r.obj("state"))
                "action" -> Fields(r.obj("event"), json).let { e ->
                    Action(r.str("frame"), e.str("type"), e.str("humanFriendlyMessage"), e.obj("params"), e.optStr("formName"), e.optObj("formState"))
                }
                "js-error" -> ScriptError(r.str("message"))
                else -> throw IllegalArgumentException("unknown canvas message type $type: $json")
            }
        }
    }
}

/** Typed, required reads of a message's fields; [json] is the whole message, for the error. */
private class Fields(private val o: JsonObject, private val json: String) {
    private fun get(key: String): JsonElement = requireNotNull(o.get(key)?.takeUnless { it.isJsonNull }) { "canvas message without $key: $json" }
    private fun primitive(key: String) = get(key).also { require(it.isJsonPrimitive) { "canvas message with a bad $key: $json" } }.asJsonPrimitive

    fun str(key: String): String = primitive(key).also { require(it.isString) { "canvas message with a bad $key: $json" } }.asString
    fun long(key: String): Long = primitive(key).also { require(it.isNumber) { "canvas message with a bad $key: $json" } }.asLong
    fun bool(key: String): Boolean = primitive(key).also { require(it.isBoolean) { "canvas message with a bad $key: $json" } }.asBoolean
    fun obj(key: String): JsonObject = get(key).also { require(it.isJsonObject) { "canvas message with a bad $key: $json" } }.asJsonObject
    fun optStr(key: String): String? = if (o.get(key)?.isJsonNull != false) null else str(key)
    fun optObj(key: String): JsonObject? = if (o.get(key)?.isJsonNull != false) null else obj(key)

    private fun array(key: String): JsonArray = get(key).also { require(it.isJsonArray) { "canvas message with a bad $key: $json" } }.asJsonArray
    fun strings(key: String): List<String> = array(key).map {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "canvas message with a bad $key: $json" }
        it.asString
    }

    fun errors(key: String): List<CanvasError> = array(key).map {
        require(it.isJsonObject) { "canvas message with a bad $key: $json" }
        val e = Fields(it.asJsonObject, json)
        CanvasError(
            e.str("code"), e.str("message"), e.optStr("statementId"), e.optStr("component"), e.optStr("path"), e.optStr("hint"),
            warning = e.optStr("severity")?.let { s -> require(s == "warning") { "canvas error with severity $s: $json" }; true } ?: false,
        )
    }
}
