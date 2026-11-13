package dev.myair.dashboard

import dev.myair.actions.BranchFate
import dev.myair.actions.Git
import dev.myair.claude.ClaudeSession
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * Git facts per session: the uncommitted files of each work tree it wrote into, and its branch's fate. Each is read on
 * its own and reused while its stamps (and, for a work tree, the sessions it serves) are unchanged: a commit, a
 * checkout, a fetch, or a session writing on (a newer last message, other changed files) rereads it. Not thread-safe:
 * one reader thread.
 */
class GitFacts {
    private data class Served(val id: String, val lastMessage: Instant, val changedFiles: List<Path>)
    private data class TreeKey(val stamps: List<Long>, val served: Set<Served>)
    private data class Tree(val key: TreeKey, val uncommitted: Result<Set<Path>>)
    private data class Fate(val stamps: List<Long>, val fate: Result<BranchFate?>)

    private sealed interface Task {
        data class Uncommitted(val root: Path) : Task
        data class Branch(val root: Path, val branch: String) : Task
    }

    private val trees = HashMap<Path, Tree>()
    private val fates = HashMap<Pair<Path, String>, Fate>()
    private val gitDirs = HashMap<Path, Pair<Path, Path>>()

    /** How many git reads ran (a work tree's status or a branch's fate). */
    var reads = 0
        private set

    /**
     * Blocking; call off the EDT. [sessions]: the ones not in Now and not settled. A first read of every work tree and
     * branch takes minutes, so they are read newest session first and [progress] gets, on this thread, the sessions each
     * read completes (first those needing none).
     */
    fun read(sessions: List<ClaudeSession>, progress: (Map<String, Result<SessionGit>>) -> Unit = {}): Map<String, Result<SessionGit>> {
        val files = sessions.associate { s -> s.id to s.changedFiles.mapNotNull { f -> rootOf(f.parent)?.let { it to f.normalize() } }.groupBy({ it.first }, { it.second }) }
        // Refs are shared by every work tree of a repository, so a removed worktree's branch is found in the project's.
        val branches = sessions.mapNotNull { s -> s.lastBranch?.let { b -> (rootOf(s.workDir) ?: rootOf(s.project))?.let { s.id to Task.Branch(it, b) } } }.toMap()
        val needed = sessions.associate { s -> s.id to files.getValue(s.id).keys.map { Task.Uncommitted(it) } + listOfNotNull(branches[s.id]) }
        val done = HashMap<Task, Result<*>>()
        fun factsOf(s: ClaudeSession): Result<SessionGit> {
            needed.getValue(s.id).firstNotNullOfOrNull { done.getValue(it).exceptionOrNull() }?.let { return Result.failure(it) }
            return Result.success(SessionGit(
                uncommitted = files.getValue(s.id).any { (root, paths) ->
                    @Suppress("UNCHECKED_CAST") val dirty = done.getValue(Task.Uncommitted(root)).getOrThrow() as Set<Path>
                    paths.any { it in dirty }
                },
                fate = branches[s.id]?.let { done.getValue(it).getOrThrow() as BranchFate? },
            ))
        }
        fun report(of: List<ClaudeSession>) = of.associate { it.id to factsOf(it) }.takeIf { it.isNotEmpty() }?.let(progress)
        report(sessions.filter { needed.getValue(it.id).isEmpty() })
        val tasks = sessions.sortedByDescending { it.lastMessage }.flatMap { needed.getValue(it.id) }.distinct()
        for (task in tasks) {
            done[task] = when (task) {
                is Task.Uncommitted -> uncommitted(task.root, sessions.filter { files.getValue(it.id).containsKey(task.root) })
                is Task.Branch -> fate(task.root, task.branch)
            }
            report(sessions.filter { s -> task in needed.getValue(s.id) && needed.getValue(s.id).all(done::containsKey) })
        }
        trees.keys.retainAll(tasks.filterIsInstance<Task.Uncommitted>().map { it.root }.toSet())
        fates.keys.retainAll(tasks.filterIsInstance<Task.Branch>().map { it.root to it.branch }.toSet())
        return sessions.associate { it.id to factsOf(it) }
    }

    private fun uncommitted(root: Path, served: List<ClaudeSession>): Result<Set<Path>> {
        val key = runCatching { TreeKey(stamps(root, null), served.map { Served(it.id, it.lastMessage, it.changedFiles) }.toSet()) }
            .getOrElse { return Result.failure(it) }
        return trees[root]?.takeIf { it.key == key }?.uncommitted ?: runCatching { reads++; Git.uncommitted(root) }.also { trees[root] = Tree(key, it) }
    }

    private fun fate(root: Path, branch: String): Result<BranchFate?> {
        val stamps = runCatching { stamps(root, branch) }.getOrElse { return Result.failure(it) }
        return fates[root to branch]?.takeIf { it.stamps == stamps }?.fate ?: runCatching { reads++; Git.branchFate(root, branch) }.also { fates[root to branch] = Fate(stamps, it) }
    }

    /** The work tree [dir] or its nearest existing ancestor lies in; null outside a repository. */
    private fun rootOf(dir: Path?): Path? = generateSequence(dir) { it.parent }.firstOrNull { it.exists() }?.let { Git.head(it)?.root }

    /** Modification times that change with a commit, a checkout, a fetch, a staged change or [branch] moving. */
    private fun stamps(root: Path, branch: String?): List<Long> {
        val (gitDir, common) = gitDirs.getOrPut(root) {
            Git.run(root, "rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir").lines().filter { it.isNotBlank() }
                .let { Path.of(it[0].trim()) to Path.of(it[1].trim()) }
        }
        val files = listOf(gitDir.resolve("HEAD"), gitDir.resolve("index"), common.resolve("packed-refs"), common.resolve("FETCH_HEAD")) +
            listOfNotNull(branch?.let { common.resolve("refs/heads/$it") })
        return files.map { if (it.exists()) it.getLastModifiedTime().toMillis() else -1 }
    }
}
