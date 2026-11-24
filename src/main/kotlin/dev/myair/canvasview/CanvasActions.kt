package dev.myair.canvasview

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI

/** What the canvas tab does with a click on a frame ([commandOf]). */
sealed interface CanvasCommand {
    /** Submit [text] to the manager's terminal (`@ToAssistant`). */
    data class Send(val text: String) : CanvasCommand

    /** Open [url], an http or https URL, in the browser (`@OpenUrl`). */
    data class Browse(val url: String) : CanvasCommand

    /** Open [path] in the editor, at the 1-based [line] when set (`@OpenFile`, `FileRef`); the tab checks that it lies under its root. */
    data class OpenFile(val path: String, val line: Int?) : CanvasCommand

    /** Copy [text] to the clipboard (`@CopyToClipboard`). */
    data class Copy(val text: String) : CanvasCommand

    /** An action of type [type] the plugin does not run: unknown, or with arguments it refuses (an `open_url` that is not http). */
    data class Unknown(val type: String) : CanvasCommand
}

private val json = GsonBuilder().disableHtmlEscaping().create()

/**
 * Maps a click on a frame of the canvas folder [folder] to what the tab does. A `@ToAssistant` click becomes the message
 * `[canvas <folder> · <frame>] <message>`, naming the frame's [author] session when it is known, followed by a JSON
 * block with the action's `context` and the submitting form's fields (`{"form": {"<name>": {"<field>": value}}}`)
 * when there are any.
 */
fun commandOf(action: CanvasMessage.Action, folder: String, author: String?): CanvasCommand = when (action.type) {
    "continue_conversation" -> CanvasCommand.Send(conversationText(action, folder, author))
    "open_url" -> action.params.string("url")?.takeIf(::isWebUrl)?.let(CanvasCommand::Browse) ?: CanvasCommand.Unknown(action.type)
    "OpenFile" -> action.params.string("path")?.takeIf { it.isNotBlank() }
        ?.let { CanvasCommand.OpenFile(it, action.params.line()) } ?: CanvasCommand.Unknown(action.type)
    "CopyToClipboard" -> action.params.string("text")?.let(CanvasCommand::Copy) ?: CanvasCommand.Unknown(action.type)
    else -> CanvasCommand.Unknown(action.type)
}

private fun conversationText(action: CanvasMessage.Action, folder: String, author: String?): String {
    val head = "[canvas $folder · ${action.frame}] ${action.message}".trimEnd() + (author?.let { " (frame by session $it)" } ?: "")
    val attached = JsonObject()
    action.params.get("context")?.takeUnless { it.isJsonNull }?.let { attached.add("context", it) }
    formFields(action)?.let { attached.add("form", it) }
    return if (attached.size() == 0) head else "$head\n```json\n${json.toJson(attached)}\n```"
}

/** The submitting form's state, each field reduced from `{value, componentType}` to its value; null without a form. */
private fun formFields(action: CanvasMessage.Action): JsonObject? {
    val name = action.formName ?: return null
    val form = action.formState?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val fields = JsonObject()
    for ((field, entry) in form.entrySet()) fields.add(field, fieldValue(entry))
    return JsonObject().apply { add(name, fields) }
}

private fun fieldValue(entry: JsonElement): JsonElement =
    entry.takeIf { it.isJsonObject && it.asJsonObject.has("value") }?.asJsonObject?.get("value") ?: entry

private fun isWebUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
}

private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.line(): Int? =
    get("line")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it >= 1 && it == Math.floor(it) && it <= Int.MAX_VALUE }?.toInt()
