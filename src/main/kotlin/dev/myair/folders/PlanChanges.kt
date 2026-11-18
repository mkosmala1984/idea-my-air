package dev.myair.folders

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.time.LocalDate

/**
 * An agent's change list for one plan, the plan of folder [apply]'s `folderId`: a JSON array of `{"op": ...}` objects
 * applied in order, all or none, like [FolderChanges]. Milestones and tasks are addressed by id and must belong to that
 * plan; anything unknown throws [IllegalArgumentException] naming the change.
 */
object PlanChanges {
    private val FIELDS = mapOf(
        "set_description" to setOf("description"),
        "add_milestone" to setOf("title", "description", "index"),
        "add_task" to setOf("milestone", "title", "description", "index"),
        "set_constraints" to setOf("constraints"),
        "add_decision" to setOf("text", "session"),
        "add_requirement" to setOf("text", "eval", "tasks", "index"),
        "add_evidence" to setOf("id", "evidence"),
        "set_review" to setOf("status", "commit"),
        "add_blocker" to setOf("text"),
        "update" to setOf("id", "title", "description", "status", "next", "text", "eval", "tasks", "evidence", "resolved"),
        "move" to setOf("id", "milestone", "index"),
        "delete" to setOf("id"),
    )

    /** The fields `update` takes per kind of id (`M`, `T`, `R`, `D`, `B`). */
    private val UPDATE_FIELDS = mapOf(
        "M" to setOf("id", "title", "description", "status", "next"),
        "T" to setOf("id", "title", "description", "status", "next"),
        "R" to setOf("id", "text", "eval", "tasks", "evidence"),
        "D" to setOf("id", "text"),
        "B" to setOf("id", "text", "resolved"),
    )
    private val COMMIT = Regex("[0-9a-f]{7,40}")

    fun apply(structure: SessionFolders, folderId: String, changes: String, today: LocalDate): SessionFolders {
        val list = try {
            JsonParser.parseString(changes)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("changes are not JSON: ${e.message}", e)
        }
        require(list.isJsonArray) { "changes must be a JSON array, got $list" }
        return list.asJsonArray.withIndex().fold(structure) { folders, (i, change) ->
            try {
                applyOne(folders, folderId, change, today)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("change ${i + 1} $change: ${e.message}", e)
            }
        }
    }

    private fun applyOne(folders: SessionFolders, folderId: String, element: JsonElement, today: LocalDate): SessionFolders {
        require(element.isJsonObject) { "not an object" }
        val change = element.asJsonObject
        val op = change.string("op")
        val fields = requireNotNull(FIELDS[op]) { "unknown op '$op', expected one of ${FIELDS.keys}" }
        val allowed = if (op == "update") UPDATE_FIELDS[change.string("id").take(1)] ?: UPDATE_FIELDS.getValue("T") else fields
        require(change.keySet() - "op" - allowed == emptySet<String>()) { "unknown fields ${change.keySet() - "op" - allowed}" }
        fun milestone(id: String) = requireNotNull(folders.findMilestone(id)?.takeIf { it.first.id == folderId }) { "no milestone '$id' in this plan" }.second
        fun task(id: String) = requireNotNull(folders.findTask(id)?.takeIf { it.first.id == folderId }) { "no task '$id' in this plan" }.second
        val plan = folders.find(folderId)!!.plan!!
        fun requirement(id: String) = requireNotNull(plan.requirements.firstOrNull { it.id == id }) { "no requirement '$id' in this plan" }
        fun decision(id: String) = requireNotNull(plan.decisions.firstOrNull { it.id == id }) { "no decision '$id' in this plan" }
        fun blocker(id: String) = requireNotNull(plan.review?.blockers?.firstOrNull { it.id == id }) { "no blocker '$id' in this plan" }
        fun taskIds(): List<String> = change.get("tasks").let { t ->
            require(t != null) { "'tasks' is missing" }
            require(t.isJsonArray && t.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "'tasks' must be an array of strings" }
            t.asJsonArray.map { task(it.asString).id }
        }
        fun evidence(item: JsonElement): String {
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "evidence must be a string" }
            return item.asString.also(::requireEvidence)
        }
        fun isMilestone(id: String) = folders.findMilestone(id)?.first?.id == folderId
        val index = change.get("index")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "'index' must be a number" }
            it.asInt
        }
        return when (op) {
            "set_description" -> folders.setDescription(folderId, change.description())
            "add_milestone" -> folders.addMilestone(folderId, change.title(), index, change.takeIf { it.has("description") }?.description())
            "add_task" -> folders.addTask(milestone(change.string("milestone")).id, change.title(), index, change.takeIf { it.has("description") }?.description())
            "set_constraints" -> folders.setConstraints(folderId, change.nullableString("constraints")?.also { require(it.isNotBlank()) { "blank constraints" } })
            "add_decision" -> folders.addDecision(folderId, change.text(), today, change.get("session")?.let { change.string("session") })
            "add_requirement" -> folders.addRequirement(folderId, change.text(), EvalKind.of(change.string("eval")), if (change.has("tasks")) taskIds() else emptyList(), index)
            "add_evidence" -> {
                val item = evidence(requireNotNull(change.get("evidence")) { "'evidence' is missing" })
                folders.editRequirement(folderId, requirement(change.string("id")).id) { it.copy(evidence = it.evidence + item) }
            }
            "set_review" -> {
                val commit = if (change.has("commit")) change.nullableString("commit")?.also { require(COMMIT.matches(it)) { "bad commit '$it', expected 7-40 lowercase hex" } } else plan.review?.commit
                folders.setReview(folderId, ReviewStatus.of(change.string("status")), commit, today)
            }
            "add_blocker" -> folders.addBlocker(folderId, change.text(), today)
            "update" -> {
                val id = change.string("id")
                when (id.take(1)) {
                    "R" -> return folders.editRequirement(folderId, requirement(id).id) { r ->
                        r.copy(
                            text = if (change.has("text")) change.text() else r.text,
                            eval = change.get("eval")?.let { EvalKind.of(change.string("eval")) } ?: r.eval,
                            tasks = if (change.has("tasks")) taskIds() else r.tasks,
                            evidence = change.get("evidence")?.let { e ->
                                require(e.isJsonArray) { "'evidence' must be an array of strings" }
                                e.asJsonArray.map(::evidence)
                            } ?: r.evidence,
                        )
                    }
                    "D" -> return folders.editDecision(folderId, decision(id).id) { it.copy(text = if (change.has("text")) change.text() else it.text) }
                    "B" -> return folders.editBlocker(folderId, blocker(id).id) { b ->
                        val resolved = change.get("resolved")?.let {
                            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "'resolved' must be true or false" }
                            it.asBoolean
                        }
                        b.copy(text = if (change.has("text")) change.text() else b.text, resolved = resolved ?: b.resolved)
                    }
                }
                val title = change.get("title")?.let { change.title() }
                val description = if (change.has("description")) change.description() else null
                if (isMilestone(id)) {
                    require(!change.has("next")) { "a milestone has no next step" }
                    val done = change.get("status")?.let {
                        when (val status = change.string("status")) {
                            "open" -> false
                            "done" -> true
                            else -> throw IllegalArgumentException("unknown milestone status '$status', expected open or done")
                        }
                    }
                    folders.editMilestone(milestone(id).id) { it.copy(title = title ?: it.title, done = done ?: it.done, description = if (change.has("description")) description else it.description) }
                } else {
                    val status = change.get("status")?.let { PlanStatus.of(change.string("status")) }
                    val next = if (change.has("next")) change.nullableString("next")?.also { require(it.isNotBlank()) { "blank next step" } } else task(id).next
                    folders.editTask(task(id).id) { it.copy(title = title ?: it.title, status = status ?: it.status, next = next, description = if (change.has("description")) description else it.description) }
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
                when (id.take(1)) {
                    "R" -> requirement(id)
                    "D" -> decision(id)
                    "B" -> blocker(id)
                    else -> if (!isMilestone(id)) task(id)
                }
                if (id.take(1) in Plan.ENTRY_PREFIXES) folders.deleteEntry(folderId, id) else folders.deletePlanItem(id)
            }
        }
    }

    private fun JsonObject.text() = string("text").also { require(it.isNotBlank()) { "blank text" } }

    private fun JsonObject.title() = string("title").also { require(it.isNotBlank()) { "blank title" } }

    /** A Markdown description, or null to clear it. */
    private fun JsonObject.description() = nullableString("description")?.also { require(it.isNotBlank()) { "blank description" } }

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
