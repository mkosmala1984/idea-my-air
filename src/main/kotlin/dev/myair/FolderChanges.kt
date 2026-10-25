package dev.myair

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.UUID

/**
 * An agent's change list: a JSON array of `{"op": ...}` objects addressing folders by path, applied in order, each on
 * the result of the one before. Unlike the tree, where a missing folder means another IDE deleted it, here an unknown
 * path, session, project or field is the agent's mistake: it throws [IllegalArgumentException] naming the change, and since
 * nothing is written until the whole list applied, no change lands.
 */
object FolderChanges {
    private val FIELDS = mapOf(
        "create" to setOf("path", "project"),
        "rename" to setOf("path", "name"),
        "move" to setOf("path", "to", "index"),
        "delete" to setOf("path"),
        "assign" to setOf("session", "path"),
    )

    /** [scanned] holds the session ids and [projects] the project directories, as `my_air_sessions_find` gives them. */
    fun apply(
        structure: SessionFolders, changes: String, scanned: Set<String>, projects: Set<String>, newId: () -> String = { UUID.randomUUID().toString() },
    ): SessionFolders {
        val list = try {
            JsonParser.parseString(changes)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("changes are not JSON: ${e.message}", e)
        }
        require(list.isJsonArray) { "changes must be a JSON array, got $list" }
        return list.asJsonArray.withIndex().fold(structure) { folders, (i, change) ->
            try {
                applyOne(folders, change, scanned, projects, newId)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            }
        }
    }

    private fun applyOne(folders: SessionFolders, element: JsonElement, scanned: Set<String>, projects: Set<String>, newId: () -> String): SessionFolders {
        require(element.isJsonObject) { "not an object" }
        val change = element.asJsonObject
        val op = change.string("op")
        val fields = requireNotNull(FIELDS[op]) { "unknown op '$op', expected one of ${FIELDS.keys}" }
        require(change.keySet() - "op" - fields == emptySet<String>()) { "unknown fields ${change.keySet() - "op" - fields}" }
        return when (op) {
            "create" -> {
                val path = change.string("path")
                val parent = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folders.at(it).id }
                val name = path.substringAfterLast('/')
                require(!folders.nameTaken(parent, name)) { "'$path' already exists" }
                val project = change.get("project")?.let { change.string("project") }
                require(project == null || project in projects) { "no project '$project'" }
                require(project == null || folders.all().none { it.project == project }) { "project '$project' is bound already" }
                folders.createFolder(parent, newId(), name, project)
            }
            "rename" -> folders.rename(folders.at(change.string("path")).id, change.string("name"))
            "move" -> {
                val moved = folders.at(change.string("path"))
                val parent = change.nullableString("to")?.let { folders.at(it).id }
                val others = folders.children(parent).filter { it.id != moved.id }
                val position = change.get("index")?.let { index ->
                    require(index.isJsonPrimitive && index.asJsonPrimitive.isNumber) { "'index' must be a number" }
                    index.asInt.also { require(it in 0..others.size) { "index $it outside 0..${others.size}" } }
                } ?: others.size
                // moveFolder counts positions before the move; [position] is where the folder ends up.
                val current = folders.children(parent).indexOfFirst { it.id == moved.id }
                folders.moveFolder(moved.id, parent, if (current in 0..position) position + 1 else position)
            }
            "delete" -> folders.delete(folders.at(change.string("path")).id)
            else -> {
                val session = change.string("session")
                require(session in scanned) { "no session '$session'" }
                folders.assign(session, change.nullableString("path")?.let { folders.at(it).id })
            }
        }
    }

    private fun SessionFolders.at(path: String) = requireNotNull(findByPath(path)) { "no folder '$path'" }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "'$name' must be a string" }
        return value.asString
    }

    /** A field that must be present, holding a string or null. */
    private fun JsonObject.nullableString(name: String): String? {
        require(has(name)) { "'$name' is missing (null means the top level or sessions)" }
        return if (get(name).isJsonNull) null else string(name)
    }
}
