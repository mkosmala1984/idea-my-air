package dev.myair.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import dev.myair.actions.Git
import dev.myair.actions.GitFailure
import dev.myair.actions.createWorktree
import dev.myair.actions.rememberBase
import dev.myair.actions.runningDirs
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.PlanStart
import dev.myair.editor.projectBase
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import dev.myair.folders.FolderChanges
import dev.myair.folders.PlanChanges
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.PresetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID
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
        @McpDescription("Words that must each occur, ignoring case, in the title, the project path, the branch the session started on or the branch checked out where it works now.")
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
        findSessions(service.sessions, folders, now, since, until, query, folder, unfiled, offset, limit, service::branchOf).toJson(folders, now.zone)
    }

    @McpTool
    @McpDescription(
        "Starts a new Claude Code session in a My Air tab of this IDE: claude runs there with prompt as its first message. " +
            "Start one only when the user asked for it in this conversation. The new session does not see this conversation, " +
            "so its prompt must say everything it needs. Arguments left out take the values of the configuration named in " +
            "preset, else claude's own settings, in the IDE project's directory. Returns the new session's id, cwd (where it " +
            "runs: the new worktree when one was made) and project.",
    )
    suspend fun my_air_session_start(
        @McpDescription("The session's first message.") prompt: String,
        @McpDescription("The project directory it starts in; default: the IDE project's.") dir: String? = null,
        @McpDescription("claude --model: opus, sonnet, haiku or a full model id.") model: String? = null,
        @McpDescription("claude --effort: low, medium, high, xhigh or max.") effort: String? = null,
        @McpDescription("JSON array of directories for claude --add-dir.") add_dirs: String? = null,
        @McpDescription("true: start in a new worktree of dir's repository, off base.") worktree: Boolean? = null,
        @McpDescription(
            "Required with worktree: the branch the worktree checks out (for a remote one such as origin/main, its local " +
                "namesake); when another worktree holds it and cannot be freed, a new branch off it.",
        )
        base: String? = null,
        @McpDescription("Only with worktree: a new branch of this name off base, checked out in the worktree.") branch: String? = null,
        @McpDescription("A plan task id such as T12: the session is filed in that task's plan folder and gets the plan as context.") plan_task: String? = null,
        @McpDescription("The name of a session configuration of the user; the other arguments replace its fields.") preset: String? = null,
    ): String {
        val project = currentCoroutineContext().project
        val (start, plan, running) = onEdt { service ->
            val saved = if (preset == null) emptyList()
            else PresetStore.INSTANCE.read().getOrElse { throw IllegalArgumentException("Cannot read ${PresetStore.INSTANCE.file}: ${it.message}") }
            val start = sessionStartPreset(saved, preset, dir, model, effort, add_dirs, worktree, base)
            require(branch == null || start.worktree) { "branch without worktree" }
            val plan = plan_task?.let { service.folders.requireTask(it) }?.let { (folder, task) -> PlanStart(folder.id, task.id) }
            Triple(start, plan, runningDirs())
        }
        val sessionProject = start.dir(projectBase(project))
        val args = expected {
            val missing = start.missingDirs(sessionProject)
            require(missing.isEmpty()) { "not directories: ${missing.joinToString()}" }
            start.claudeArgs(prompt)
        }
        val id = UUID.randomUUID().toString()
        val repo = if (start.worktree) withContext(Dispatchers.IO) { expected { Git.repo(sessionProject) } } else null
        val cwd = if (repo == null) sessionProject else withContext(Dispatchers.IO) { expected { createWorktree(repo, checkNotNull(start.base), branch, id, running) } }
        withContext(Dispatchers.EDT) {
            repo?.let { rememberBase(it, checkNotNull(start.base)) }
            ClaudeSessionOpener.startNew(project, sessionProject, cwd, id, args, plan)
        }
        return startedJson(id, cwd, sessionProject)
    }

    @McpTool
    @McpDescription(
        "The user's folder tree in their order:each folder's path, the directories bound to it (empty for most), plan (for a " +
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
        "my_air_plan_get; no plan folder lies in another, so a project with several plans has them as plan folders inside " +
        "its bound folder), " +
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
        "The plans of the project a directory belongs to, as a JSON array: the plan folder that the folder bound to the " +
            "directory (or to one above it) is or lies in, else every plan folder below that bound folder. Each plan has folder " +
            "(its path, the plan's name, which my_air_plan_apply takes), its directories, description (Markdown, as for " +
            "milestones and tasks), and milestones (id, title, description, status open or done) with their tasks (id, title, description, status: pending, in_progress, waiting for the user, blocked by someone else, done or " +
            "canceled; next: the next step), each task with the sessions whose brief names it. Fails when the directory has no plan.",
    )
    suspend fun my_air_plan_get(@McpDescription("A directory of the project, normally the session's working directory.") dir: String): String =
        withFolders { service, folders -> folders.plansJson(planFolders(folders, dir), service.sessions, service.live, ZoneId.systemDefault()) }

    @McpTool
    @McpDescription(
        "Changes one plan my_air_plan_get returned, all or none, in one write that leaves a snapshot for undo. " +
            "Add tasks and keep their status and next step current freely; delete or move milestones and tasks only after the " +
            "user agreed. changes is a JSON array applied in order: " +
            "{\"op\":\"set_description\",\"description\":\"...\"} (the plan's; null clears it), " +
            "{\"op\":\"add_milestone\",\"title\":\"...\",\"description\":\"...\",\"index\":0} (description and index optional, index default last), " +
            "{\"op\":\"add_task\",\"milestone\":\"M1\",\"title\":\"...\",\"description\":\"...\",\"index\":0}, " +
            "{\"op\":\"update\",\"id\":\"T4\",\"title\":\"...\",\"description\":\"...\",\"status\":\"waiting\",\"next\":\"...\"} (fields " +
            "optional; description or next null clears it; a milestone takes title, description and status open or done). " +
            "Descriptions are Markdown: what the plan, milestone or task is about and how it is to be done, not its progress. " +
            "{\"op\":\"move\",\"id\":\"T4\",\"milestone\":\"M2\",\"index\":0} (index after the move; milestone optional for a task, its own " +
            "by default, and not given for a milestone), " +
            "{\"op\":\"delete\",\"id\":\"T4\"} (a milestone only when all its tasks are done or canceled). " +
            "New ids (M<n>, T<n>) are unique across all plans and never reused. Returns the plan after the change.",
    )
    suspend fun my_air_plan_apply(
        @McpDescription("The plan's folder path, as my_air_plan_get gives it.") plan: String,
        @McpDescription("The JSON array of changes.") changes: String,
    ): String = withFolders { service, folders ->
        val id = requireNotNull(folders.findByPath(plan)?.takeIf { it.plan != null }) { "no plan folder at '$plan'" }.id
        service.updateFolders(ChangeSource.MCP) { PlanChanges.apply(it, id, changes) }
        service.folders.planJson(checkNotNull(service.folders.find(id)), service.sessions, service.live, ZoneId.systemDefault())
    }

    @McpTool
    @McpDescription(
        "Snapshots of the folder structure, newest first, each taken just before a change: name, taken, source " +
        "(ui: the user in the tree; auto: a new session filed, or one continued by /clear; mcp: a change list; brief: a folder a session's brief created; restore: a " +
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

    /** The plan folders of [dir], or an error telling the agent how one is made. */
    private fun planFolders(folders: SessionFolders, dir: String): List<Folder> = folders.planFoldersOf(Path.of(dir)).ifEmpty {
        val bound = folders.projectFolder(Path.of(dir))
        throw IllegalArgumentException(
            "'$dir' has no plan" + (bound?.let { " (it is bound to folder '${folders.pathOf(it.id)}')" } ?: "") +
                ". With the user's approval, my_air_folders_apply creates a plan folder" + (bound?.let { " inside '${folders.pathOf(it.id)}'" } ?: " bound to the directory") +
                " (create with plan true) or makes a folder a plan folder (add_plan).",
        )
    }

    /** Runs [block] on the EDT, where the service's state lives; an input error reaches the agent with its message. */
    private suspend fun <T> onEdt(block: (ClaudeSessionsService) -> T): T = withContext(Dispatchers.EDT) { expected { block(ClaudeSessionsService.getInstance()) } }

    /** Runs [block]; an input error, or a failed `git` (whose message carries its stderr), reaches the agent with its message. */
    private fun <T> expected(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw McpExpectedError(e.message ?: e.toString())
    } catch (e: GitFailure) {
        throw McpExpectedError(e.message!!)
    }

    /** Like [onEdt], for tools that need the folders; an unreadable `structure.json` is reported, as Folders mode shows it. */
    private suspend fun <T> withFolders(block: (ClaudeSessionsService, SessionFolders) -> T): T = onEdt { service ->
        service.foldersError?.let { throw McpExpectedError("Cannot read ${service.structureFile}: $it") }
        block(service, service.folders)
    }
}
