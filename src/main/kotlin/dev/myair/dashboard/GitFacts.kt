package dev.myair.dashboard

import dev.myair.actions.BranchFate
import dev.myair.actions.Git
import dev.myair.claude.ClaudeSession
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * Git facts per session, read per work tree root and reused while the root's stamps and the sessions it serves are
 * unchanged: a commit, a checkout, a fetch, or a session writing on (a newer last message, other changed files) rereads
 * it. Not thread-safe: one reader thread.
 */
class GitFacts {
    private data class Served(val id: String, val lastMessage: Instant, val changedFiles: List<Path>)
    private data class Key(val stamps: List<Long>, val served: Set<Served>, val branches: Set<String>)
    private data class RootFacts(val uncommitted: Set<Path>, val fates: Map<String, BranchFate?>)
    private data class Entry(val key: Key, val facts: Result<RootFacts>)

    private val cache = HashMap<Path, Entry>()
    private val gitDirs = HashMap<Path, Pair<Path, Path>>()

    /** How many root reads ran. */
    var rootReads = 0
        private set

    /** Blocking; call off the EDT. [sessions]: the ones not in Now and not settled. */
    fun read(sessions: List<ClaudeSession>): Map<String, Result<SessionGit>> {
        val files = sessions.associate { s -> s.id to s.changedFiles.mapNotNull { f -> rootOf(f.parent)?.let { it to f.normalize() } }.groupBy({ it.first }, { it.second }) }
        // Refs are shared by every work tree of a repository, so a removed worktree's branch is found in the project's.
        val branchRoots = sessions.filter { it.lastBranch != null }.mapNotNull { s -> (rootOf(s.workDir) ?: rootOf(s.project))?.let { s.id to it } }.toMap()
        val roots = (files.values.flatMap { it.keys } + branchRoots.values).toSet()
        val read = roots.associateWith { root ->
            val served = sessions.filter { s -> files.getValue(s.id).containsKey(root) || branchRoots[s.id] == root }
            val branches = served.filter { branchRoots[it.id] == root }.mapNotNull { it.lastBranch }.toSet()
            val key = runCatching { Key(stamps(root, branches), served.map { Served(it.id, it.lastMessage, it.changedFiles) }.toSet(), branches) }
                .getOrElse { return@associateWith Result.failure(it) }
            cache[root]?.takeIf { it.key == key }?.facts ?: runCatching {
                rootReads++
                RootFacts(Git.uncommitted(root), branches.associateWith { Git.branchFate(root, it) })
            }.also { cache[root] = Entry(key, it) }
        }
        cache.keys.retainAll(roots)
        return sessions.associate { s ->
            val own = files.getValue(s.id)
            val needed = own.keys + listOfNotNull(branchRoots[s.id])
            s.id to (needed.firstNotNullOfOrNull { read.getValue(it).exceptionOrNull() }?.let { Result.failure(it) } ?: Result.success(
                SessionGit(
                    uncommitted = own.any { (root, paths) -> paths.any { it in read.getValue(root).getOrThrow().uncommitted } },
                    fate = branchRoots[s.id]?.let { read.getValue(it).getOrThrow().fates.getValue(checkNotNull(s.lastBranch)) },
                ),
            ))
        }
    }

    /** The work tree [dir] or its nearest existing ancestor lies in; null outside a repository. */
    private fun rootOf(dir: Path?): Path? = generateSequence(dir) { it.parent }.firstOrNull { it.exists() }?.let { Git.head(it)?.root }

    /** Modification times that change with a commit, a checkout, a fetch, a staged change or a branch moving. */
    private fun stamps(root: Path, branches: Set<String>): List<Long> {
        val (gitDir, common) = gitDirs.getOrPut(root) {
            Git.run(root, "rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir").lines().filter { it.isNotBlank() }
                .let { Path.of(it[0].trim()) to Path.of(it[1].trim()) }
        }
        val files = listOf(gitDir.resolve("HEAD"), gitDir.resolve("index"), common.resolve("packed-refs"), common.resolve("FETCH_HEAD")) +
            branches.sorted().map { common.resolve("refs/heads/$it") }
        return files.map { if (it.exists()) it.getLastModifiedTime().toMillis() else -1 }
    }
}
