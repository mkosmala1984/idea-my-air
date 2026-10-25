package dev.myair.claude

import java.nio.file.Path
import java.time.Instant

/**
 * [lastMessage] is the time of the latest message of the conversation, not of the file: resuming a session appends lines
 * too. [changedFiles] are the files the agent wrote or edited, in the order it first touched them; [prompts] the first human prompts.
 * [project] is the main checkout when [cwd] is in one of its linked worktrees (`inProjects`), else [cwd].
 */
data class ClaudeSession(
    val id: String, val cwd: Path, val title: String, val lastMessage: Instant, val changedFiles: List<Path>, val prompts: List<String>,
    val brief: Brief? = null, val project: Path = cwd,
) {
    /** Case-insensitive substring match on the title or the project path; every word of [query] must match. */
    fun matches(query: String): Boolean =
        query.split(' ').filter { it.isNotEmpty() }.all { title.contains(it, true) || cwd.toString().contains(it, true) }

    /** TreeState matches nodes by toString(), so a session keeps its selection while its title and time change. */
    override fun toString() = id
}

/** Whether this path is [dir] or a directory below it, comparing as text: see [dev.myair.folders.SessionFolders.projectFolder]. */
fun Path.isAtOrUnder(dir: String): Boolean {
    val path = toString()
    val separator = fileSystem.separator
    return path == dir || path.startsWith(dir.removeSuffix(separator) + separator)
}
