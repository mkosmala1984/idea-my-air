package dev.myair

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** A user folder of Folders mode; toString() is the node id TreeState keeps expansion and selection by. */
data class Folder(val id: String, val name: String, val folders: List<Folder> = emptyList(), val items: List<Item> = emptyList()) {
    override fun toString() = id

    /** This folder and every folder below it. */
    fun subtree(): Sequence<Folder> = sequenceOf(this) + folders.asSequence().flatMap { it.subtree() }
}

/** Something filed in a folder. Version 1 knows only sessions; a new type extends [SessionFolders.parse], it is never skipped. */
data class Item(val type: String, val id: String)

/**
 * The user's folder tree, in the order the user arranged it. A session is filed in at most one folder; one filed nowhere
 * belongs to the fixed `sessions` folder. Operations naming a folder that no longer exists change nothing, because another
 * IDE may have deleted it; every other broken invariant throws.
 */
data class SessionFolders(val folders: List<Folder> = emptyList()) {
    init {
        val all = all().toList()
        require(all.map { it.id }.duplicates().isEmpty()) { "duplicate folder ids ${all.map { it.id }.duplicates()}" }
        val items = all.flatMap { it.items }
        items.forEach { require(it.type == SESSION) { "unknown item type '${it.type}'" } }
        require(items.map { it.id }.duplicates().isEmpty()) { "sessions filed twice: ${items.map { it.id }.duplicates()}" }
        (sequenceOf(folders) + all.asSequence().map { it.folders }).forEach { siblings ->
            siblings.forEach { require(it.name.isNotBlank() && it.name == it.name.trim()) { "bad folder name '${it.name}'" } }
            require(siblings.map { it.name.lowercase() }.duplicates().isEmpty()) { "duplicate folder names among ${siblings.map { it.name }}" }
        }
    }

    fun all(): Sequence<Folder> = folders.asSequence().flatMap { it.subtree() }

    fun find(id: String) = all().firstOrNull { it.id == id }

    fun folderOf(sessionId: String) = all().firstOrNull { folder -> folder.items.any { it.id == sessionId } }?.id

    /** The folders directly under [parentId], or at the top level for null. */
    fun children(parentId: String?) = if (parentId == null) folders else checkNotNull(find(parentId)) { "no folder $parentId" }.folders

    /** Whether [name] is taken among the children of [parentId] by a folder other than [except]. */
    fun nameTaken(parentId: String?, name: String, except: String? = null) =
        children(parentId).any { it.id != except && it.name.equals(name.trim(), true) }

    fun createFolder(parentId: String?, id: String, name: String): SessionFolders {
        if (parentId != null && find(parentId) == null) return this
        return withChildren(parentId) { it + Folder(id, name) }
    }

    fun rename(id: String, name: String) = if (find(id) == null) this else edit(id) { it.copy(name = name) }

    /** Removes the folder and its subtree; the sessions filed there go back to `sessions`. */
    fun delete(id: String) = if (find(id) == null) this else SessionFolders(folders.without(id))

    /** Moves the folder under [parentId] (top level for null) to position [index] among that parent's children as they are before the move. */
    fun moveFolder(id: String, parentId: String?, index: Int): SessionFolders {
        val moved = find(id) ?: return this
        if (parentId != null && find(parentId) == null) return this
        require(moved.subtree().none { it.id == parentId }) { "folder $id cannot move into itself" }
        val siblings = children(parentId)
        require(index in 0..siblings.size) { "index $index outside 0..${siblings.size}" }
        val current = siblings.indexOfFirst { it.id == id }
        val at = if (current in 0 until index) index - 1 else index
        return SessionFolders(folders.without(id)).withChildren(parentId) { it.toMutableList().apply { add(at, moved) } }
    }

    /** Files the session in [folderId], taking it out of any other folder; null returns it to `sessions`. */
    fun assign(sessionId: String, folderId: String?): SessionFolders {
        if (folderId != null && find(folderId) == null) return this
        val unfiled = SessionFolders(folders.map { it.mapSubtree { f -> f.copy(items = f.items.filter { item -> item.id != sessionId }) } })
        return if (folderId == null) unfiled else unfiled.edit(folderId) { it.copy(items = it.items + Item(SESSION, sessionId)) }
    }

    /**
     * A tab switched from session [from] to [to] in the same process. `/clear` starts a session no scan has seen yet, which
     * continues the same work and takes [from]'s folder; `/resume` lands on a session in [scanned], which stays where it is.
     */
    fun carryOver(from: String, to: String, scanned: Set<String>): SessionFolders {
        if (to in scanned || folderOf(to) != null) return this
        return folderOf(from)?.let { assign(to, it) } ?: this
    }

    /** Sessions filed in the folder or anywhere below it. */
    fun sessionCount(id: String) = checkNotNull(find(id)) { "no folder $id" }.subtree().sumOf { it.items.size }

    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(linkedMapOf("version" to VERSION, "folders" to folders))

    private fun edit(id: String, change: (Folder) -> Folder) = SessionFolders(folders.map { it.mapSubtree { f -> if (f.id == id) change(f) else f } })

    private fun withChildren(parentId: String?, change: (List<Folder>) -> List<Folder>) =
        if (parentId == null) SessionFolders(change(folders)) else edit(parentId) { it.copy(folders = change(it.folders)) }

    companion object {
        const val SESSION = "session"
        private const val VERSION = 1

        /** Reads `structure.json`, throwing on anything it does not know rather than skipping it. */
        fun parse(text: String): SessionFolders {
            val root = JsonParser.parseString(text).asJsonObject
            val version = root.field("version").asInt
            require(version == VERSION) { "unsupported structure version $version" }
            return SessionFolders(root.field("folders").asJsonArray.map { folder(it.asJsonObject) })
        }

        private fun folder(json: JsonObject): Folder = Folder(
            json.field("id").asString,
            json.field("name").asString,
            json.field("folders").asJsonArray.map { folder(it.asJsonObject) },
            json.field("items").asJsonArray.map { it.asJsonObject.let { item -> Item(item.field("type").asString, item.field("id").asString) } },
        )

        private fun JsonObject.field(name: String): JsonElement = checkNotNull(get(name)) { "missing '$name' in $this" }
    }
}

private fun Folder.mapSubtree(change: (Folder) -> Folder): Folder = change(copy(folders = folders.map { it.mapSubtree(change) }))

private fun List<Folder>.without(id: String): List<Folder> = filter { it.id != id }.map { it.copy(folders = it.folders.without(id)) }

private fun <T> List<T>.duplicates() = groupingBy { it }.eachCount().filterValues { it > 1 }.keys

/**
 * `structure.json`, shared by every IDE. Each change re-reads the file before applying, so two IDEs editing at once lose
 * at most a change made in the same few milliseconds. A missing file is an empty structure.
 */
class StructureStore(val file: Path) {
    init {
        file.parent.createDirectories()
    }

    fun read() = if (file.exists()) SessionFolders.parse(file.readText()) else SessionFolders()

    @Synchronized
    fun update(op: (SessionFolders) -> SessionFolders): SessionFolders {
        val before = read()
        val after = op(before)
        if (after != before) writeAtomically(file, after.toJson())
        return after
    }
}
