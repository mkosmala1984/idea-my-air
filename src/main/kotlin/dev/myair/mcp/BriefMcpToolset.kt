package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import dev.myair.MyAirSettings
import dev.myair.claude.Brief

/**
 * Lets the agent of a session describe what the session is for. Nothing is stored here: the call and its arguments land in
 * the calling session's transcript, where the scanner reads the latest accepted one ([Brief.next]).
 */
@Suppress("FunctionName")
class BriefMcpToolset : McpToolset {
    override fun isExperimental() = false

    override fun isEnabled() = MyAirSettings.getInstance().briefs

    @McpTool
    @McpDescription(
        "Describes this session for the user's session list: what it is for and its tasks with progress. Call it once you " +
        "understand the user's first request, and again whenever the goal changes or a task starts or finishes; every call " +
        "replaces the whole brief. Keep tasks as outcomes the user cares about, not tool steps, and write in the user's language. " +
        "Changes nothing but this description.",
    )
    suspend fun my_air_set_brief(
        @McpDescription("One sentence, at most ${Brief.GOAL_CHARS} chars: what this session is for.")
        goal: String,
        @McpDescription(
            "JSON array of at most ${Brief.MAX_TASKS} tasks in order, each {\"content\": \"...\", \"status\": \"pending\" | \"in_progress\" | \"completed\"}, " +
            "content at most ${Brief.TASK_CHARS} chars, at most one in_progress. [] when there are no tasks yet.",
        )
        tasks: String,
    ): String {
        try {
            Brief.input(goal, tasks)
        } catch (e: IllegalArgumentException) {
            throw McpExpectedError(e.message ?: e.toString())
        }
        return "ok"
    }
}
