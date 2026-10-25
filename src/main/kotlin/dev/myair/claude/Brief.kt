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
            tool != UPDATE_PLAN -> input(input["goal"].asString, input["tasks"].asString)
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

        /** The arguments of `my_air_set_brief`, checked against the rules its description gives the agent. */
        fun input(goal: String, tasks: String): Brief {
            require(goal.isNotBlank() && goal.length <= GOAL_CHARS) { "goal must be one non-blank sentence of at most $GOAL_CHARS chars" }
            val items = try {
                JsonParser.parseString(tasks).asJsonArray
            } catch (e: Exception) {
                throw IllegalArgumentException("tasks must be a JSON array of {\"content\", \"status\"} objects: ${e.message}", e)
            }
            require(items.size() <= MAX_TASKS) { "at most $MAX_TASKS tasks, got ${items.size()}" }
            val parsed = tasks(items)
            parsed.forEach { require(it.content.isNotBlank() && it.content.length <= TASK_CHARS) { "task content must be non-blank, at most $TASK_CHARS chars: '${it.content}'" } }
            require(parsed.count { it.status == TaskStatus.IN_PROGRESS } <= 1) { "at most one task may be in_progress" }
            return Brief(goal, parsed)
        }

        /** Items as both tools write them: `{"content": ..., "status": "pending" | "in_progress" | "completed"}`. */
        fun tasks(items: JsonArray): List<BriefTask> = items.map { it.asJsonObject }.map { o ->
            val status = o["status"].asString
            BriefTask(o["content"].asString, requireNotNull(TaskStatus.entries.firstOrNull { it.name.lowercase() == status }) { "unknown task status '$status'" })
        }

        const val GOAL_CHARS = 200
        const val TASK_CHARS = 120
        const val MAX_TASKS = 15
        const val SET_BRIEF = "my_air_set_brief"
        const val UPDATE_PLAN = "mcp__ccd_turn__update_plan"
    }
}
