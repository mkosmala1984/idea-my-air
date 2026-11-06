package dev.myair.editor

import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import dev.myair.folders.Folder
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders

/**
 * What every `claude` a session tab starts appends to its system prompt (`--append-system-prompt`), on `--resume` too:
 * claude records a session's prompt on its first request and builds it again from the current launch's flags only after
 * a compaction. It names nothing of one session, so every session sends the same prompt and shares its cache; the base
 * is read from git when needed, since the context pane may change it after the start. One line without `"`, `<` or `>`,
 * as it travels on a Windows command line.
 */
fun appendedSystemPrompt(briefs: Boolean, filing: Boolean): String = listOfNotNull(
    "This session runs in a My Air tab of IntelliJ IDEA.",
    "My Air stores the base of a branch it created as `git config branch.NAME.myAirBase`, NAME being the branch: when the " +
        "current branch has one, diff, review and target pull requests against that base, not the default branch.",
    "The IDE's MCP server has My Air tools, each described in full by its own description: my_air_sessions_find finds " +
        "the user's Claude Code sessions (by date, words in the title or project, folder), for example an earlier session to " +
        "continue from; my_air_folders_get reads the folders the user files sessions in, my_air_folders_apply changes them " +
        "only after the user approved the change, and my_air_history_list with my_air_history_restore undo such changes; " +
        "my_air_session_start starts a new session in a tab, only when the user asks for one.",
    "A project the user plans has one or more plan folders with milestones and tasks: at the start call my_air_plan_get with " +
        "the working directory, and when it returns plans, tell the user which task fits the request or propose a new one" +
        (if (briefs) ", and name that task as plan_task in my_air_set_brief." else ".") +
        " Keep the task's status and next step current with my_air_plan_apply: waiting when the user has to act, done only when " +
        "the work is finished. Add tasks freely, but delete or move milestones and tasks only after the user agreed.",
    if (!briefs) null else "Call the my_air_set_brief tool once you understand the user's first request, and again whenever the " +
        "goal changes or a task starts or finishes.",
    if (!filing) null else "File and title this session through my_air_set_brief without asking: give title (at most " +
        "${Brief.TITLE_CHARS} chars, in the user's language) in the first call and change it only when the goal changes; " +
        "before giving folder, call my_air_folders_get first and pick the existing folder that fits, naming a new one only " +
        "when none does, one level below an existing folder and never in a plan folder. Both stay until replaced, and the " +
        "user's own filing and renaming win over them.",
).joinToString(" ")

/** A new session's place in a plan, chosen where it was started: the plan folder it is filed in, and the task if one. */
data class PlanStart(val folderId: String, val taskId: String?)

/**
 * The plan session [sessionId] works in, with its task when known: the task its brief names ([session], null before a scan
 * found it), else [start]'s task, else no task in the plan folder the session shows in (for a new session, the one it is
 * filed in). Null outside every plan.
 */
fun sessionPlan(structure: SessionFolders, sessionId: String, session: ClaudeSession?, start: PlanStart?): Pair<Folder, PlanTask?>? {
    (session?.let(structure::taskOf) ?: start?.taskId?.let(structure::findTask))?.let { return it }
    val place = session?.let(structure::placeOf) ?: structure.folderOf(sessionId)
    return place?.let(structure::planFolderAt)?.let { it to null }
}

/**
 * What a session in a plan adds to its system prompt (`--append-system-prompt-file`, so Markdown keeps its lines): the
 * plan's name, path and description, and the task's and its milestone's. Only these, which rarely change: claude keeps a
 * session's prompt until it compacts, so statuses and next steps here would go stale.
 */
fun planPrompt(structure: SessionFolders, folder: Folder, task: PlanTask?, briefs: Boolean): String {
    val plan = checkNotNull(folder.plan) { "folder ${folder.id} has no plan" }
    val path = structure.pathOf(folder.id)
    return buildString {
        append("# My Air plan: ${folder.name}\n\n")
        append("This session works in the plan \"${folder.name}\" (folder path \"$path\", the plan argument of my_air_plan_apply). ")
        append("Below are its descriptions as of the session's start; read current statuses and next steps with my_air_plan_get.\n")
        plan.description?.let { append("\n$it\n") }
        if (task == null) return@buildString
        val milestone = plan.milestones.first { task in it.tasks }
        append("\n## Task ${task.id}: ${task.title}\n\n")
        append("This session works on task ${task.id}" + (if (briefs) "; name it as plan_task in my_air_set_brief." else ".") + "\n")
        task.description?.let { append("\n$it\n") }
        append("\n## Milestone ${milestone.id}: ${milestone.title}\n")
        milestone.description?.let { append("\n$it\n") }
    }
}
