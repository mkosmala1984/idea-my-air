package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.isAtOrUnder
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * [sessions] with [ClaudeSession.project] set to the main checkout when their cwd is in one of its linked worktrees, as
 * git lists them now for the directories the sessions ran in. A removed worktree is no longer listed, so its sessions
 * keep their own cwd.
 */
fun inProjects(sessions: List<ClaudeSession>): List<ClaudeSession> {
    val worktrees = sessions.map { it.cwd }.distinct().flatMap { root -> gitWorktrees(root).map { it to root } }
    return sessions.map { session -> worktrees.firstOrNull { session.cwd.isAtOrUnder(it.first.toString()) }?.let { session.copy(project = it.second) } ?: session }
}

/**
 * The linked worktrees of the main checkout [root], as git keeps them: `.git/worktrees/<name>/gitdir` names the worktree's
 * `.git` file. A directory that is no repository, or is itself a linked worktree (`.git` is a file), has none. An admin
 * directory without `gitdir` (a worktree half removed, or still being added) is none either: `git worktree list` skips it.
 */
fun gitWorktrees(root: Path): List<Path> {
    val admin = root.resolve(".git").resolve("worktrees")
    if (!admin.isDirectory()) return emptyList()
    return admin.listDirectoryEntries().map { it.resolve("gitdir") }.filter { it.exists() }.map { Path.of(it.readText().trim()).parent }
}
