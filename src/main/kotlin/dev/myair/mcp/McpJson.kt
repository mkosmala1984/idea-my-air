package dev.myair.mcp

import com.google.gson.GsonBuilder
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.folders.Folder
import dev.myair.folders.progress
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
 * The folder tree in the user's order, each folder by path with the ids of the sessions filed directly in it, the
 * directories bound to it, whose sessions filed nowhere else it holds too, and for a plan folder its tasks' progress.
 */
fun SessionFolders.treeJson(): String {
    fun node(folder: Folder, prefix: String): Map<String, Any?> = linkedMapOf(
        "path" to prefix + folder.name,
        "dirs" to folder.dirs,
        "plan" to folder.plan?.let { "${progress(it.tasks())} tasks done" },
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

/**
 * The plan of [folder]: its path, directories, description, and milestones with their tasks, each task with the sessions whose
 * brief names it, newest first, and their live status (null without a process).
 */
fun SessionFolders.planJson(folder: Folder, sessions: List<ClaudeSession>, live: Map<String, LiveSession>, zone: ZoneId): String =
    gson.toJson(planMap(folder, sessions, live, zone))

/** The plans of [folders] as a JSON array, each as [planJson] gives it. */
fun SessionFolders.plansJson(folders: List<Folder>, sessions: List<ClaudeSession>, live: Map<String, LiveSession>, zone: ZoneId): String =
    gson.toJson(folders.map { planMap(it, sessions, live, zone) })

private fun SessionFolders.planMap(folder: Folder, sessions: List<ClaudeSession>, live: Map<String, LiveSession>, zone: ZoneId): Map<String, Any?> {
    val plan = checkNotNull(folder.plan) { "folder ${folder.id} has no plan" }
    val byTask = sessions.filter { taskOf(it)?.first?.id == folder.id }.groupBy { it.brief!!.planTask!! }
    return linkedMapOf(
        "folder" to pathOf(folder.id),
        "dirs" to folder.dirs,
        "description" to plan.description,
        "milestones" to plan.milestones.map { m ->
            linkedMapOf(
                "id" to m.id, "title" to m.title, "description" to m.description, "status" to if (m.done) "done" else "open", "progress" to progress(m.tasks),
                "tasks" to m.tasks.map { t ->
                    linkedMapOf(
                        "id" to t.id, "title" to t.title, "description" to t.description, "status" to t.status.label, "next" to t.next,
                        "sessions" to byTask[t.id].orEmpty().sortedByDescending { it.lastMessage }.map { s ->
                            linkedMapOf(
                                "id" to s.id, "title" to s.title,
                                "lastMessage" to s.lastMessage.atZone(zone).toOffsetDateTime().toString(),
                                "status" to live[s.id]?.status?.name?.lowercase(),
                            )
                        },
                    )
                },
            )
        },
    )
}
