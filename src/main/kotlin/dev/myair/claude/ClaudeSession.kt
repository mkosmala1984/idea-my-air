package dev.myair.claude

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * [lastMessage] is the time of the latest message of the conversation, not of the file: resuming a session appends lines
 * too. [changedFiles] are the files the agent wrote or edited, in the order it first touched them; [prompts] the first human prompts.
 * [project] is the main checkout when [cwd] is in one of its linked worktrees (`inProjects`), else the folder Claude
 * Desktop started the session in ([DesktopSessionReader]), else [cwd]. [desktop]: Claude Desktop runs the session.
 * [addedDirs] are the directories the session may use besides [cwd], as its latest environment snapshot lists them.
 * [model] and [effort]: what the latest reply of the model ran with; null before the first one.
 * [artifacts]: the artifacts the main conversation published, in the order first published.
 * [background]: the tasks the main conversation sent to the background and no notification has ended yet; they end
 * with their process too, which the transcript does not record.
 */
data class ClaudeSession(
    val id: String, val cwd: Path, val title: String, val lastMessage: Instant, val changedFiles: List<Path>, val prompts: List<String>,
    val brief: Brief? = null, val project: Path = cwd, val desktop: Boolean = false, val addedDirs: List<Path> = emptyList(),
    val model: String? = null, val effort: String? = null, val artifacts: List<Artifact> = emptyList(),
    val background: List<BackgroundTask> = emptyList(),
) {
    /** Case-insensitive substring match on the title or the project path; every word of [query] must match. */
    fun matches(query: String): Boolean =
        query.split(' ').filter { it.isNotEmpty() }.all { title.contains(it, true) || cwd.toString().contains(it, true) }

    /** Whether the last message came less than [days] days before [now]. */
    fun isWithin(days: Int, now: Instant): Boolean = lastMessage > now.minus(Duration.ofDays(days.toLong()))

    /** TreeState matches nodes by toString(), so a session keeps its selection while its title and time change. */
    override fun toString() = id
}

/** An artifact as its latest publish named it; [path] is the local file published. */
data class Artifact(val id: String, val title: String, val url: String, val path: Path)

/** A shell command, monitor, agent or workflow running in the background; [id] is the one its notifications name. */
data class BackgroundTask(val id: String, val kind: Kind, val description: String) {
    enum class Kind { SHELL, MONITOR, AGENT, WORKFLOW }
}

/** Whether this path is [dir] or a directory below it, comparing as text: see [dev.myair.folders.SessionFolders.projectFolder]. */
fun Path.isAtOrUnder(dir: String): Boolean {
    val path = toString()
    val separator = fileSystem.separator
    return path == dir || path.startsWith(dir.removeSuffix(separator) + separator)
}
