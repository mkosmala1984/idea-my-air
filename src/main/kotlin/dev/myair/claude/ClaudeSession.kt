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
 * [background]: the tasks the main conversation sent to the background, in the order launched; those without an
 * [BackgroundTask.end] also end with their process, which the transcript does not record.
 * [skills]: the skills `claude` listed to the main conversation, in its order. [mcpServers]: the MCP servers whose tools
 * it currently offers, sorted, without those waiting for a sign-in ([mcpServers] of the tool names).
 */
data class ClaudeSession(
    val id: String, val cwd: Path, val title: String, val lastMessage: Instant, val changedFiles: List<Path>, val prompts: List<String>,
    val brief: Brief? = null, val project: Path = cwd, val desktop: Boolean = false, val addedDirs: List<Path> = emptyList(),
    val model: String? = null, val effort: String? = null, val artifacts: List<Artifact> = emptyList(),
    val background: List<BackgroundTask> = emptyList(), val skills: List<String> = emptyList(), val mcpServers: List<String> = emptyList(),
) {
    /** Case-insensitive substring match on the title or the project path; every word of [query] must match. */
    fun matches(query: String): Boolean =
        query.split(' ').filter { it.isNotEmpty() }.all { title.contains(it, true) || cwd.toString().contains(it, true) }

    /** Whether the last message came less than [days] days before [now]. */
    fun isWithin(days: Int, now: Instant): Boolean = lastMessage > now.minus(Duration.ofDays(days.toLong()))

    /** TreeState matches nodes by toString(), so a session keeps its selection while its title and time change. */
    override fun toString() = id
}

/** The tools a server waiting for a sign-in offers, and nothing else. */
private val AUTH_TOOLS = setOf("authenticate", "complete_authentication")

/** The servers of MCP [tools] (`mcp__<server>__<tool>`), sorted, but those offering only [AUTH_TOOLS]. */
fun mcpServers(tools: Collection<String>): List<String> = tools
    .map { name -> name.split("__", limit = 3).also { check(it.size == 3 && it[0] == "mcp") { "not an MCP tool: $name" } } }
    .groupBy({ it[1] }, { it[2] })
    .filterValues { !AUTH_TOOLS.containsAll(it) }.keys.sorted()

/** An artifact as its latest publish named it; [path] is the local file published. */
data class Artifact(val id: String, val title: String, val url: String, val path: Path)

/**
 * A shell command, monitor, agent or workflow sent to the background; [id] is the one its notifications name. [output]:
 * the file `claude` writes its output to, as its launch or final notification names it. [end]: null while no final
 * notification has come. [model]: the model an agent runs on. [events]: how many event notifications a monitor sent,
 * the last at [lastEvent]. [usage]: what an agent or workflow used, as its latest final notification counts it.
 */
data class BackgroundTask(
    val id: String, val kind: Kind, val description: String, val started: Instant, val output: Path? = null, val end: End? = null,
    val model: String? = null, val events: Int = 0, val lastEvent: Instant? = null, val usage: Usage? = null,
) {
    enum class Kind { SHELL, MONITOR, AGENT, WORKFLOW }

    /** [summary] is `claude`'s own line about the end, which names a command's exit code. */
    data class End(val time: Instant, val status: Status, val summary: String)

    /** The statuses of a final notification, and [EXPIRED]: a monitor's timeout, which `claude` tells as an event. */
    enum class Status { COMPLETED, FAILED, KILLED, STOPPED, EXPIRED }

    /** [agents]: a workflow's agents, none for an agent. */
    data class Usage(val tokens: Long, val toolUses: Int, val agents: Agents?)

    data class Agents(val count: Int, val done: Int, val failed: Int)
}

/** Whether this path is [dir] or a directory below it, comparing as text: see [dev.myair.folders.SessionFolders.projectFolder]. */
fun Path.isAtOrUnder(dir: String): Boolean {
    val path = toString()
    val separator = fileSystem.separator
    return path == dir || path.startsWith(dir.removeSuffix(separator) + separator)
}
