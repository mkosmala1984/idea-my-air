package dev.myair.folders

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * An agent's change list for one plan, the plan of folder [apply]'s `folderId`: a JSON array of `{"op": ...}` objects
 * applied in order, all or none, like [FolderChanges]. Milestones and tasks are addressed by id and must belong to that
 * plan; anything unknown throws [IllegalArgumentException] naming the change.
 */
object PlanChanges {
    private val FIELDS = mapOf(
        "set_goal" to setOf("goal"),
        "add_milestone" to setOf("title", "index"),
        "add_task" to setOf("milestone", "title", "index"),
        "update" to setOf("id", "title", "status", "next"),
        "move" to setOf("id", "milestone", "index"),
        "delete" to setOf("id"),
    )

    fun apply(structure: SessionFolders, folderId: String, changes: String): SessionFolders {
        val list = try {
            JsonParser.parseString(changes)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("changes are not JSON: ${e.message}", e)
        }
        require(list.isJsonArray) { "changes must be a JSON array, got $list" }
        return list.asJsonArray.withIndex().fold(structure) { folders, (i, change) ->
            try {
                applyOne(folders, folderId, change)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            }
        }
    }

    private fun applyOne(folders: SessionFolders, folderId: String, element: JsonElement): SessionFolders {
        require(element.isJsonObject) { "not an object" }
        val change = element.asJsonObject
        val op = change.string("op")
        val fields = requireNotNull(FIELDS[op]) { "unknown op '$op', expected one of ${FIELDS.keys}" }
        require(change.keySet() - "op" - fields == emptySet<String>()) { "unknown fields ${change.keySet() - "op" - fields}" }
        fun milestone(id: String) = requireNotNull(folders.findMilestone(id)?.takeIf { it.first.id == folderId }) { "no milestone '$id' in this plan" }.second
        fun task(id: String) = requireNotNull(folders.findTask(id)?.takeIf { it.first.id == folderId }) { "no task '$id' in this plan" }.second
        fun isMilestone(id: String) = folders.findMilestone(id)?.first?.id == folderId
        val index = change.get("index")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "'index' must be a number" }
            it.asInt
        }
        return when (op) {
            "set_goal" -> folders.setGoal(folderId, change.nullableString("goal")?.also { require(it.isNotBlank()) { "blank goal" } })
            "add_milestone" -> folders.addMilestone(folderId, change.title(), index)
            "add_task" -> folders.addTask(milestone(change.string("milestone")).id, change.title(), index)
            "update" -> {
                val id = change.string("id")
                val title = change.get("title")?.let { change.title() }
                if (isMilestone(id)) {
                    require(!change.has("next")) { "a milestone has no next step" }
                    val done = change.get("status")?.let {
                        when (val status = change.string("status")) {
                            "open" -> false
                            "done" -> true
                            else -> throw IllegalArgumentException("unknown milestone status '$status', expected open or done")
                        }
                    }
                    folders.editMilestone(milestone(id).id) { it.copy(title = title ?: it.title, done = done ?: it.done) }
                } else {
                    val status = change.get("status")?.let { PlanStatus.of(change.string("status")) }
                    val next = if (change.has("next")) change.nullableString("next")?.also { require(it.isNotBlank()) { "blank next step" } } else task(id).next
                    folders.editTask(task(id).id) { it.copy(title = title ?: it.title, status = status ?: it.status, next = next) }
                }
            }
            "move" -> {
                val id = change.string("id")
                val at = requireNotNull(index) { "'index' is missing" }
                if (isMilestone(id)) {
                    require(!change.has("milestone")) { "a milestone moves within its plan only" }
                    folders.moveMilestone(id, at.also { require(it in 0 until folders.find(folderId)!!.plan!!.milestones.size) { "index $it outside the milestones" } })
                } else {
                    val from = folders.find(folderId)!!.plan!!.milestones.first { m -> m.tasks.any { it.id == task(id).id } }
                    val to = change.get("milestone")?.let { milestone(change.string("milestone")) } ?: from
                    val size = to.tasks.size - if (to.id == from.id) 1 else 0
                    require(at in 0..size) { "index $at outside 0..$size" }
                    folders.moveTask(id, to.id, at)
                }
            }
            else -> {
                val id = change.string("id")
                if (!isMilestone(id)) task(id)
                folders.deletePlanItem(id)
            }
        }
    }

    private fun JsonObject.title() = string("title").also { require(it.isNotBlank()) { "blank title" } }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "'$name' must be a string" }
        return value.asString
    }

    /** A field that must be present, holding a string or null. */
    private fun JsonObject.nullableString(name: String): String? {
        require(has(name)) { "'$name' is missing (null clears it)" }
        return if (get(name).isJsonNull) null else string(name)
    }
}
