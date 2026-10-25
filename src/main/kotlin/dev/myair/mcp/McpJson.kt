package dev.myair.mcp

import com.google.gson.GsonBuilder
import dev.myair.folders.Folder
import dev.myair.folders.SessionFolders
import dev.myair.folders.StructureHistory
import java.time.ZoneId

private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

fun SessionPage.toJson(structure: SessionFolders, zone: ZoneId): String = gson.toJson(
    linkedMapOf(
        "total" to total,
        "offset" to offset,
        "sessions" to sessions.map { session ->
            linkedMapOf(
                "id" to session.id,
                "title" to session.title,
                "project" to session.cwd.toString(),
                "lastMessage" to session.lastMessage.atZone(zone).toOffsetDateTime().toString(),
                "folder" to structure.placeOf(session)?.let(structure::pathOf),
                "prompts" to session.prompts.map { it.take(PROMPT_CHARS) },
                "changedFiles" to session.changedFiles.size,
            )
        },
    ),
)

/**
 * The folder tree in the user's order, each folder by path with the ids of the sessions filed directly in it and the
 * project directory it is bound to, whose sessions filed nowhere else it holds too.
 */
fun SessionFolders.treeJson(): String {
    fun node(folder: Folder, prefix: String): Map<String, Any?> = linkedMapOf(
        "path" to prefix + folder.name,
        "project" to folder.project,
        "sessions" to folder.items.map { it.id },
        "folders" to folder.folders.map { node(it, "$prefix${folder.name}/") },
    )
    return gson.toJson(folders.map { node(it, "") })
}

/** Snapshots newest first with their counts; one that does not parse shows its error instead, so it hides no other. */
fun StructureHistory.toJson(): String = gson.toJson(
    list().map { snapshot ->
        val entry = linkedMapOf<String, Any>("name" to snapshot.name, "taken" to snapshot.taken.toString(), "source" to snapshot.source.label)
        try {
            val folders = SessionFolders.parse(read(snapshot.name))
            entry["folders"] = folders.all().count()
            entry["sessions"] = folders.all().sumOf { it.items.size }
        } catch (e: Exception) {
            entry["error"] = "${e.javaClass.simpleName}: ${e.message}"
        }
        entry
    },
)
