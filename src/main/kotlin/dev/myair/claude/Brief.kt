package dev.myair.claude

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED }

data class BriefTask(val content: String, val status: TaskStatus)

/**
 * What a session is for, as its agent last described it with `my_air_set_brief`; [goal] is null for a Claude Desktop
 * plan (`update_plan`), which lists tasks only.
 */
data class Brief(val goal: String?, val tasks: List<BriefTask>) {
    val done: Int get() = tasks.count { it.status == TaskStatus.COMPLETED }

    val current: BriefTask? get() = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }

    companion object {
        /**
         * The brief after an accepted call of [tool] with [input], the call's `input` object, made while [previous] was
         * current. A plan is either replaced (`items`) or moved on: `advance` completes the step in progress and starts the
         * next pending one, `done`/`start` complete and start steps by number, counted from 1.
         */
        fun next(previous: Brief?, tool: String, input: JsonObject): Brief = when {
            tool != UPDATE_PLAN -> Brief(input["goal"].asString, tasks(JsonParser.parseString(input["tasks"].asString).asJsonArray))
            input.has("items") -> Brief(null, tasks(input.getAsJsonArray("items")))
            else -> {
                val brief = checkNotNull(previous) { "plan moved on before it was set: $input" }
                val (done, start) = if (input.has("advance")) {
                    val current = brief.tasks.indexOfFirst { it.status == TaskStatus.IN_PROGRESS }
                    listOfNotNull(current.takeIf { it >= 0 }) to brief.tasks.withIndex().firstOrNull { it.index > current && it.value.status == TaskStatus.PENDING }?.index
                } else {
                    checkNotNull(input.getAsJsonArray("done")) { "unknown plan update: $input" }.map { it.asInt - 1 } to input["start"]?.asInt?.minus(1)
                }
                brief.copy(tasks = brief.tasks.mapIndexed { i, task ->
                    when (i) {
                        in done -> task.copy(status = TaskStatus.COMPLETED)
                        start -> task.copy(status = TaskStatus.IN_PROGRESS)
                        else -> task
                    }
                })
            }
        }

        /** Items as both tools write them: `{"content": ..., "status": "pending" | "in_progress" | "completed"}`. */
        fun tasks(items: JsonArray): List<BriefTask> = items.map { it.asJsonObject }.map { o ->
            val status = o["status"].asString
            BriefTask(o["content"].asString, requireNotNull(TaskStatus.entries.firstOrNull { it.name.lowercase() == status }) { "unknown task status '$status'" })
        }

        const val SET_BRIEF = "my_air_set_brief"
        const val UPDATE_PLAN = "mcp__ccd_turn__update_plan"
    }
}
