package dev.myair.editor

import dev.myair.store.DEFAULT_PROFILE
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** What a session tab's path in [ClaudeSessionFileSystem] names: enough to start the tab again after a restart. */
data class SessionTabRef(val sessionId: String, val cwd: Path, val project: Path, val profile: String)

/**
 * `<sessionId>?cwd=<cwd>&project=<project>`, then `&profile=<profile>` unless it is `default`, the values URL-encoded,
 * so the path holds no `/` of its own; a path without a profile, as tabs saved before profiles, reads as `default`.
 */
fun sessionTabPath(sessionId: String, cwd: Path, project: Path, profile: String) =
    "$sessionId?cwd=${encode(cwd.toString())}&project=${encode(project.toString())}" +
        if (profile == DEFAULT_PROFILE) "" else "&profile=${encode(profile)}"

private val TAB_PATH_KEYS = listOf(setOf("cwd", "project"), setOf("cwd", "project", "profile"))

fun parseSessionTabPath(path: String): SessionTabRef {
    val sessionId = path.substringBefore('?')
    require(sessionId.isNotEmpty() && '?' in path) { "not a session tab path: $path" }
    val pairs = path.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
    val values = pairs.toMap()
    require(pairs.size == values.size && values.keys in TAB_PATH_KEYS) { "not a session tab path: $path" }
    return SessionTabRef(
        sessionId, Path.of(decode(values.getValue("cwd"))), Path.of(decode(values.getValue("project"))),
        values["profile"]?.let(::decode) ?: DEFAULT_PROFILE,
    )
}

/** `claude` resumes a session it has a transcript of; one closed before its first message has none and starts again under its id. */
fun restartArgs(sessionId: String, claudeHome: Path) = listOf(if (hasTranscript(sessionId, claudeHome)) "--resume" else "--session-id", sessionId)

/** A project directory under [claudeHome] holds a transcript of [sessionId]. */
fun hasTranscript(sessionId: String, claudeHome: Path): Boolean {
    val projects = claudeHome.resolve("projects")
    return projects.isDirectory() && projects.listDirectoryEntries().any { it.resolve("$sessionId.jsonl").exists() }
}

private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8)
