package dev.myair.mcp

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.myair.folders.Folder
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders
import dev.myair.store.Preset

/**
 * How `my_air_session_start` starts a session: [saved]'s configuration named [preset] (none: claude's own settings in the IDE
 * project), each argument given replacing its field; [addDirs] is a JSON array. A worktree needs a base, since an agent
 * cannot pick one from a popup.
 */
fun sessionStartPreset(
    saved: List<Preset>, preset: String?, dir: String?, model: String?, effort: String?, addDirs: String?, worktree: Boolean?, base: String?,
): Preset {
    val from = if (preset == null) Preset("my_air_session_start")
    else requireNotNull(saved.firstOrNull { it.name == preset }) { "no configuration '$preset'; configurations: ${saved.joinToString { it.name }.ifEmpty { "none" }}" }
    val start = from.copy(
        model = model ?: from.model, effort = effort ?: from.effort, project = dir ?: from.project,
        // A configuration's worktree turned off takes its base along.
        worktree = worktree ?: from.worktree, base = if (worktree == false) base else base ?: from.base, addDirs = addDirs?.let(::stringArray) ?: from.addDirs,
    )
    require(!start.worktree || start.base != null) { "a worktree needs base, a branch of the directory's repository" }
    return start
}

private fun stringArray(json: String): List<String> {
    val array = try {
        JsonParser.parseString(json)
    } catch (e: JsonParseException) {
        throw IllegalArgumentException("not JSON: $json", e)
    }
    require(array.isJsonArray) { "not a JSON array: $json" }
    return array.asJsonArray.map {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "not a string: $it" }
        it.asString
    }
}

/** Plan task [id] with its plan folder; an unknown id is an error listing the open tasks. */
fun SessionFolders.requireTask(id: String): Pair<Folder, PlanTask> = findTask(id) ?: throw IllegalArgumentException(
    "no plan task '$id'; open tasks: " + all().flatMap { f -> f.plan?.tasks().orEmpty().filterNot { it.status.closed }.map { "${it.id} ${it.title} (${pathOf(f.id)})" } }
        .joinToString("; ").ifEmpty { "none" },
)
