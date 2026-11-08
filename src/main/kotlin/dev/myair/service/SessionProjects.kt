package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.isAtOrUnder
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readAttributes
import kotlin.io.path.readText

/**
 * [sessions] with [ClaudeSession.project] set to the main checkout when their cwd is in one of its linked worktrees, as
 * git lists them now for the directories the sessions ran in. A removed worktree is no longer listed, so its sessions
 * keep their own cwd.
 */
fun inProjects(sessions: List<ClaudeSession>): List<ClaudeSession> {
    val worktrees = sessions.map { it.cwd }.distinct().flatMap { root -> gitWorktrees(root).map { it to root } }
    return sessions.map { session -> worktrees.firstOrNull { session.cwd.isAtOrUnder(it.first.path.toString()) }?.let { session.copy(project = it.second) } ?: session }
}

/** A linked worktree at [path], its admin directory made at [created]. */
data class LinkedWorktree(val path: Path, val created: Instant)

/**
 * The linked worktrees of the main checkout [root], as git keeps them, newest first: `.git/worktrees/<name>/gitdir` names
 * the worktree's `.git` file (relative to the admin directory with `--relative-paths`), and the admin directory's creation time is when `git worktree add` made it (git records no
 * date). A directory that is no repository, or is itself a linked worktree (`.git` is a file), has none. An admin
 * directory without `gitdir` (a worktree half removed, or still being added) is none either: `git worktree list` skips it.
 */
fun gitWorktrees(root: Path): List<LinkedWorktree> {
    val admin = root.resolve(".git").resolve("worktrees")
    if (!admin.isDirectory()) return emptyList()
    return admin.listDirectoryEntries().filter { it.resolve("gitdir").exists() }
        .map { LinkedWorktree(worktreeOf(it), it.readAttributes<BasicFileAttributes>().creationTime().toInstant()) }
        .sortedByDescending { it.created }
}

/** The worktree the admin directory [admin] keeps, from the `.git` file its `gitdir` names. */
private fun worktreeOf(admin: Path): Path {
    val gitdir = admin.resolve("gitdir").readText().trim()
    check(gitdir.isNotEmpty()) { "$admin/gitdir names no .git file" }
    return admin.resolve(gitdir).normalize().parent
}
