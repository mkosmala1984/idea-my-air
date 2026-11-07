package dev.myair.folders

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory

/**
 * An agent's change list: a JSON array of `{"op": ...}` objects addressing folders by path, applied in order, each on
 * the result of the one before. Unlike the tree, where a missing folder means another IDE deleted it, here an unknown
 * path, session, directory or field is the agent's mistake: it throws [IllegalArgumentException] naming the change, and since
 * nothing is written until the whole list applied, no change lands.
 */
object FolderChanges {
    private val FIELDS = mapOf(
        "create" to setOf("path", "dirs", "plan"),
        "add_plan" to setOf("path"),
        "bind" to setOf("path", "dir"),
        "unbind" to setOf("dir"),
        "set_workdir" to setOf("path", "dir"),
        "rename" to setOf("path", "name"),
        "move" to setOf("path", "to", "index"),
        "delete" to setOf("path"),
        "assign" to setOf("session", "path"),
    )

    /** [scanned] holds the session ids and [projects] the directories sessions ran in, as `my_air_sessions_find` gives them. */
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
                val dirs = change.get("dirs")?.let {
                    require(it.isJsonArray && it.asJsonArray.all { d -> d.isJsonPrimitive && d.asJsonPrimitive.isString }) { "'dirs' must be an array of strings" }
                    it.asJsonArray.map { d -> d.asString }
                }.orEmpty()
                dirs.forEach { folders.checkBindable(it, projects) }
                val plan = change.get("plan")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "'plan' must be true or false" }
                    it.asBoolean
                } == true
                require(!plan || parent == null || folders.planFolderAt(parent) == null) { "'${folders.pathOf(parent!!)}' lies in a plan folder" }
                folders.createFolder(parent, newId(), name, dirs, if (plan) Plan() else null)
            }
            "add_plan" -> {
                val folder = folders.at(change.string("path"))
                require(folders.canAddPlan(folder.id)) { "'${change.string("path")}' has a plan, lies in a plan folder or holds one" }
                folders.addPlan(folder.id)
            }
            "bind" -> {
                val dir = change.string("dir")
                folders.checkBindable(dir, projects)
                folders.bind(folders.at(change.string("path")).id, dir)
            }
            "unbind" -> {
                val dir = change.string("dir")
                require(folders.all().any { dir in it.dirs }) { "'$dir' is bound to no folder" }
                folders.unbind(dir)
            }
            "set_workdir" -> {
                require(change.has("dir")) { "'dir' is missing (null clears the working directory)" }
                val dir = if (change.get("dir").isJsonNull) null else Path.of(change.string("dir"))
                require(dir == null || dir.isDirectory()) { "'$dir' is not a directory" }
                folders.setWorkdir(folders.at(change.string("path")).id, dir?.toString())
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

    private fun SessionFolders.checkBindable(dir: String, projects: Set<String>) {
        require(dir in projects) { "no project '$dir'" }
        require(all().none { dir in it.dirs }) { "'$dir' is bound already" }
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
