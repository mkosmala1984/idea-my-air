package dev.myair.editor

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** What a session tab's path in [ClaudeSessionFileSystem] names: enough to start the tab again after a restart. */
data class SessionTabRef(val sessionId: String, val cwd: Path, val project: Path)

/** `<sessionId>?cwd=<cwd>&project=<project>`, both values URL-encoded, so the path holds no `/` of its own. */
fun sessionTabPath(sessionId: String, cwd: Path, project: Path) =
    "$sessionId?cwd=${encode(cwd)}&project=${encode(project)}"

fun parseSessionTabPath(path: String): SessionTabRef {
    val sessionId = path.substringBefore('?')
    require(sessionId.isNotEmpty() && '?' in path) { "not a session tab path: $path" }
    val pairs = path.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
    val values = pairs.toMap()
    require(pairs.size == 2 && values.keys == setOf("cwd", "project")) { "not a session tab path: $path" }
    return SessionTabRef(sessionId, decode(values.getValue("cwd")), decode(values.getValue("project")))
}

/** `claude` resumes a session it has a transcript of; one closed before its first message has none and starts again under its id. */
fun restartArgs(sessionId: String, claudeHome: Path): List<String> {
    val projects = claudeHome.resolve("projects")
    val known = projects.isDirectory() && projects.listDirectoryEntries().any { it.resolve("$sessionId.jsonl").exists() }
    return listOf(if (known) "--resume" else "--session-id", sessionId)
}

private fun encode(path: Path) = URLEncoder.encode(path.toString(), Charsets.UTF_8)
private fun decode(value: String) = Path.of(URLDecoder.decode(value, Charsets.UTF_8))
