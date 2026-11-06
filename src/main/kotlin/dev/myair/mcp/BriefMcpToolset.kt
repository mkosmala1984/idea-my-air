package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import dev.myair.MyAirSettings
import com.intellij.openapi.application.EDT
import dev.myair.claude.Brief
import dev.myair.service.ClaudeSessionsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import java.util.UUID

/**
 * Lets the agent of a session describe what the session is for, and file and title it. Nothing about the session is stored
 * here: the call, its arguments and its result land in the calling session's transcript, where the scanner reads the latest
 * accepted one ([Brief.next]); only a folder the call names and that does not exist yet is created.
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
        "When the session works on a task of a plan (my_air_plan_get), name it in plan_task, so the session shows under that task. " +
        "folder and title, when filing is on, file the session in the user's folder tree and title it in their session list, " +
        "without asking: read my_air_folders_get first and prefer an existing folder; a missing folder is created, but only " +
        "one level below an existing one, never at the top level or in a plan folder. plan_task, folder and title keep their " +
        "value when a later call leaves them out: give folder and title again only when the goal changes, since the user may " +
        "have renamed or moved the folder meanwhile. The user's own filing and renaming win over them. " +
        "Changes nothing but this description and a folder it creates.",
    )
    suspend fun my_air_set_brief(
        @McpDescription("One sentence, at most ${Brief.GOAL_CHARS} chars: what this session is for.")
        goal: String,
        @McpDescription(
            "JSON array of at most ${Brief.MAX_TASKS} tasks in order, each {\"content\": \"...\", \"status\": \"pending\" | \"in_progress\" | \"completed\"}, " +
            "content at most ${Brief.TASK_CHARS} chars, at most one in_progress. [] when there are no tasks yet.",
        )
        tasks: String,
        @McpDescription("Optional: the id of the plan task this session works on, such as T12; omit it when there is none.")
        plan_task: String? = null,
        @McpDescription("Optional: the folder path the session belongs in, names joined by /, matched ignoring case, such as rtm/fixes.")
        folder: String? = null,
        @McpDescription("Optional: the session's title, at most ${Brief.TITLE_CHARS} chars, in the user's language.")
        title: String? = null,
    ): String {
        try {
            require(folder == null && title == null || MyAirSettings.getInstance().fileSessions) { "filing sessions is turned off; leave out folder and title" }
            Brief.input(goal, tasks, plan_task, title)
            return withContext(Dispatchers.EDT) {
                val service = ClaudeSessionsService.getInstance()
                plan_task?.let { service.folders.requireTask(it) }
                if (folder == null) return@withContext Brief.OK
                lateinit var found: Folder
                var created = false
                service.updateFolders(ChangeSource.BRIEF) { before ->
                    before.briefFolder(folder) { UUID.randomUUID().toString() }.let { (after, f) -> found = f; created = after != before; after }
                }
                Brief.folderResult(found.id, service.folders.pathOf(found.id), created)
            }
        } catch (e: IllegalArgumentException) {
            throw McpExpectedError(e.message ?: e.toString())
        }
    }
}
