package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.EDT
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import dev.myair.folders.FolderChanges
import dev.myair.folders.PlanChanges
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Lets an agent file sessions into the user's folders and keep the plans of plan folders. The tools do not ask the user
 * anything themselves: their descriptions tell the agent to get approval in the conversation first where it is needed,
 * and every write leaves a snapshot to undo it.
 */
@Suppress("FunctionName")
class FoldersMcpToolset : McpToolset {
    // The default is experimental, which may hide the tools.
    override fun isExperimental() = false

    @McpTool
    @McpDescription(
        "Finds Claude Code sessions of the user, newest first, one page at a time. All filters are optional and combine. " +
        "Each session has id, title, project (the directory claude ran in), lastMessage (when its latest message was written), folder (its path in the user's " +
        "folders, from filing, from its project being bound to a folder or from its brief naming a plan task; null when unfiled), its first human prompts (cut short) and the number of files it changed. The result " +
        "has total, the number of all matches: fetch the next page with offset until it is covered.",
    )
    suspend fun my_air_sessions_find(
        @McpDescription("Earliest last message, inclusive: <n>d or <n>h before now (7d, 12h), a date (2026-10-17) or a local date-time (2026-10-24T09:00).")
        since: String? = null,
        @McpDescription("Latest last message, inclusive, in the same forms; a date covers the whole day.")
        until: String? = null,
        @McpDescription("Words that must all occur, ignoring case, in the title or the project path.")
        query: String? = null,
        @McpDescription("Folder path such as rtm/fixes: sessions filed in it or any folder below it.")
        folder: String? = null,
        @McpDescription("true: only unfiled sessions; false: only filed ones. Cannot be true together with folder.")
        unfiled: Boolean? = null,
        @McpDescription("Matches to skip, default 0.")
        offset: Int? = null,
        @McpDescription("Page size, default $DEFAULT_LIMIT, at most $MAX_LIMIT.")
        limit: Int? = null,
    ): String = withFolders { service, folders ->
        val now = ZonedDateTime.now()
        findSessions(service.sessions, folders, now, since, until, query, folder, unfiled, offset, limit).toJson(folders, now.zone)
    }

    @McpTool
    @McpDescription(
        "The user's folder tree in their order: each folder's path, the directories bound to it (empty for most), plan (for a " +
            "plan folder how many of its tasks are done, else null; read it with my_air_plan_get), the ids of sessions filed " +
            "directly in it, and its subfolders. A bound folder also holds every session run in a bound directory or a directory " +
            "below it (worktrees included) not filed in another folder; the most specific binding wins.",
    )
    suspend fun my_air_folders_get(): String = withFolders { _, folders -> folders.treeJson() }

    @McpTool
    @McpDescription(
        "Applies changes to the user's folders, all or none, in one write that leaves a snapshot for undo. " +
        "Only apply what the user approved: first show them a table (session title | project | current folder | " +
        "proposed folder, new folders marked) and wait for their ok. Ask about every delete separately, saying how many " +
        "sessions return to the unfiled list. Folders are addressed by path, names joined by /, matched ignoring case. " +
        "changes is a JSON array applied in order, each change seeing the result of the previous ones: " +
        "{\"op\":\"create\",\"path\":\"rtm/fixes\"} (the parent must exist), " +
        "{\"op\":\"create\",\"path\":\"rtm/my-air\",\"dirs\":[\"E:\\\\dev\\\\my-air\"],\"plan\":true} (dirs optional: directories as " +
        "my_air_sessions_find spells a project; the folder holds their sessions filed in no other folder, new ones too; a directory " +
        "binds to one folder; deleting the folder unbinds them. plan optional: a plan folder, holding milestones and tasks, see " +
        "my_air_plan_get; no plan folder lies in another), " +
        "{\"op\":\"add_plan\",\"path\":\"rtm/my-air\"} (makes an existing folder a plan folder), " +
        "{\"op\":\"bind\",\"path\":\"rtm/my-air\",\"dir\":\"E:\\\\dev\\\\my-air-web\"}, {\"op\":\"unbind\",\"dir\":\"E:\\\\dev\\\\my-air-web\"}, " +
        "{\"op\":\"rename\",\"path\":\"rtm/fixes\",\"name\":\"bugs\"}, " +
        "{\"op\":\"move\",\"path\":\"rtm/fixes\",\"to\":\"testflo\",\"index\":0} (to null is the top level; index optional, default last), " +
        "{\"op\":\"delete\",\"path\":\"rtm/fixes\"} (its sessions become unfiled; a plan in it is deleted too), " +
        "{\"op\":\"assign\",\"session\":\"<id>\",\"path\":\"rtm/fixes\"} (path null takes it out of its folder; a session of a bound project then shows in that project's folder). " +
        "Returns the folder tree after the change.",
    )
    suspend fun my_air_folders_apply(@McpDescription("The JSON array of changes.") changes: String): String = withFolders { service, _ ->
        val scanned = service.sessions.map { it.id }.toSet()
        val projects = service.sessions.map { it.cwd.toString() }.toSet()
        service.updateFolders(ChangeSource.MCP) { FolderChanges.apply(it, changes, scanned, projects) }
        service.folders.treeJson()
    }

    @McpTool
    @McpDescription(
        "The plan of the project a directory belongs to: the plan folder bound to the directory or to one above it, or holding " +
            "the folder bound to it. Returns the folder's path, its directories, the goal, and milestones (status open or done) " +
            "with their tasks (id, title, status: pending, in_progress, waiting for the user, blocked by someone else, done or " +
            "canceled; next: the next step), each task with the sessions whose brief names it. Fails when no plan folder holds the directory.",
    )
    suspend fun my_air_plan_get(@McpDescription("A directory of the project, normally the session's working directory.") dir: String): String =
        withFolders { service, folders -> folders.planJson(planFolder(folders, dir), service.sessions, service.live, ZoneId.systemDefault()) }

    @McpTool
    @McpDescription(
        "Changes the plan my_air_plan_get returns for the same dir, all or none, in one write that leaves a snapshot for undo. " +
            "Add tasks and keep their status and next step current freely; delete or move milestones and tasks only after the " +
            "user agreed. changes is a JSON array applied in order: " +
            "{\"op\":\"set_goal\",\"goal\":\"...\"} (null clears it), " +
            "{\"op\":\"add_milestone\",\"title\":\"...\",\"index\":0} (index optional, default last), " +
            "{\"op\":\"add_task\",\"milestone\":\"M1\",\"title\":\"...\",\"index\":0}, " +
            "{\"op\":\"update\",\"id\":\"T4\",\"title\":\"...\",\"status\":\"waiting\",\"next\":\"...\"} (fields optional; next null clears " +
            "it; a milestone takes title and status open or done), " +
            "{\"op\":\"move\",\"id\":\"T4\",\"milestone\":\"M2\",\"index\":0} (index after the move; milestone optional for a task, its own " +
            "by default, and not given for a milestone), " +
            "{\"op\":\"delete\",\"id\":\"T4\"} (a milestone only when all its tasks are done or canceled). " +
            "New ids (M<n>, T<n>) are unique across all plans and never reused. Returns the plan after the change.",
    )
    suspend fun my_air_plan_apply(
        @McpDescription("A directory of the project, as for my_air_plan_get.") dir: String,
        @McpDescription("The JSON array of changes.") changes: String,
    ): String = withFolders { service, folders ->
        val id = planFolder(folders, dir).id
        service.updateFolders(ChangeSource.MCP) { PlanChanges.apply(it, id, changes) }
        service.folders.planJson(checkNotNull(service.folders.find(id)), service.sessions, service.live, ZoneId.systemDefault())
    }

    @McpTool
    @McpDescription(
        "Snapshots of the folder structure, newest first, each taken just before a change: name, taken, source " +
        "(ui: the user in the tree; auto: a new session filed, or one continued by /clear; mcp: a change list; restore: a " +
        "restore), and its folder and session counts.",
    )
    suspend fun my_air_history_list(): String = onEdt { it.history.toJson() }

    @McpTool
    @McpDescription(
        "Replaces the folder structure, plans included, with a snapshot from my_air_history_list; the replaced version is snapshotted too. " +
        "To undo the user's last change made by an agent, restore the newest snapshot with source mcp. Restoring replaces " +
        "everything, so any snapshot newer than the one restored marks a change that will be lost: name those to the user " +
        "and ask before restoring. Returns the folder tree after the restore.",
    )
    suspend fun my_air_history_restore(@McpDescription("The snapshot's name.") name: String): String = withFolders { service, _ ->
        val restored = try {
            SessionFolders.parse(service.history.read(name))
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("snapshot '$name' is not a valid structure: ${e.message}", e)
        }
        service.updateFolders(ChangeSource.RESTORE) { it.restore(restored) }
        service.folders.treeJson()
    }

    /** The plan folder of [dir], or an error telling the agent how one is made. */
    private fun planFolder(folders: SessionFolders, dir: String): Folder = folders.planFolderOf(Path.of(dir)) ?: throw IllegalArgumentException(
        "no plan folder holds '$dir'" + (folders.projectFolder(Path.of(dir))?.let { " (it is bound to folder '${folders.pathOf(it.id)}')" } ?: "") +
            ". With the user's approval, my_air_folders_apply makes a folder a plan folder (add_plan) or creates one bound to the directory.",
    )

    /** Runs [block] on the EDT, where the service's state lives; an input error reaches the agent with its message. */
    private suspend fun <T> onEdt(block: (ClaudeSessionsService) -> T): T = withContext(Dispatchers.EDT) {
        try {
            block(ClaudeSessionsService.getInstance())
        } catch (e: IllegalArgumentException) {
            throw McpExpectedError(e.message ?: e.toString())
        }
    }

    /** Like [onEdt], for tools that need the folders; an unreadable `structure.json` is reported, as Folders mode shows it. */
    private suspend fun <T> withFolders(block: (ClaudeSessionsService, SessionFolders) -> T): T = onEdt { service ->
        service.foldersError?.let { throw McpExpectedError("Cannot read ${service.structureFile}: $it") }
        block(service, service.folders)
    }
}
