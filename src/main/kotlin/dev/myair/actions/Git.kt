package dev.myair.actions

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/** A git repository as the worktree popup needs it. */
data class GitRepo(val mainRoot: Path, val branches: List<String>, val default: String)

/** A file of [GitState.changes]; [path] is relative to [GitState.root]. A binary file counts no lines. */
data class FileChange(val path: String, val kind: Kind, val added: Int, val removed: Int) {
    enum class Kind { ADDED, MODIFIED, DELETED, UNTRACKED }
}

/**
 * The work tree of a folder against its base. [branch]: the checked-out branch, or `detached at <sha7>`; [root]: the
 * work tree root. [base]: the base stored for the branch ([baseStored]), else the default branch; null without commits
 * and when no default branch can be told. [changes]: the files changed since the merge base of [base] and HEAD,
 * untracked ones included; null exactly when [problem] says why they cannot be read. [mergeBase] is null then and
 * without commits.
 */
data class GitState(
    val branch: String, val detached: Boolean, val root: Path, val base: String?, val baseStored: Boolean,
    val mergeBase: String?, val changes: List<FileChange>?, val problem: String?,
) {
    val added get() = changes.orEmpty().sumOf { it.added }
    val removed get() = changes.orEmpty().sumOf { it.removed }
}

/** Lines as `git diff --numstat` counts them; a NUL in the first 8000 bytes makes a file binary, as for git. */
fun lineCount(bytes: ByteArray): Int {
    if ((0 until minOf(bytes.size, 8000)).any { bytes[it] == 0.toByte() }) return 0
    val newline = '\n'.code.toByte()
    return bytes.count { it == newline } + if (bytes.isNotEmpty() && bytes.last() != newline) 1 else 0
}

/** Runs the `git` on PATH; any exit code outside `ok` throws with git's stderr. */
object Git {
    fun run(dir: Path, vararg args: String, input: String? = null, ok: Set<Int> = setOf(0)): String =
        bytes(dir, *args, input = input, ok = ok).toString(Charsets.UTF_8)

    fun bytes(dir: Path, vararg args: String, input: String? = null, ok: Set<Int> = setOf(0)): ByteArray {
        val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).start()
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        // Written apart from the stdout read: git may fill its stdout pipe before it has read all of a large input.
        val stdin = CompletableFuture.runAsync { process.outputStream.use { if (input != null) it.write(input.toByteArray()) } }
        val stdout = process.inputStream.readAllBytes()
        val exit = process.waitFor()
        stdin.get()
        check(exit in ok) { "git ${args.joinToString(" ")} failed in $dir (exit $exit):\n${stderr.get().trim()}" }
        return stdout
    }

    /** The root of the main checkout, also when [dir] is inside a linked worktree. */
    fun mainRoot(dir: Path): Path = Path.of(run(dir, "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).parent

    fun repo(dir: Path): GitRepo {
        val root = mainRoot(dir)
        val local = parseBranches(run(root, "for-each-ref", "--format=%(refname)", "refs/heads"))
        val remote = parseBranches(run(root, "for-each-ref", "--format=%(refname)", "refs/remotes"))
        val originHead = run(root, "for-each-ref", "--format=%(symref:short)", "refs/remotes/origin/HEAD").trim().ifEmpty { null }
        return GitRepo(root, local + remote, defaultBranch(run(root, "remote").lines().filter { it.isNotEmpty() }, originHead, local))
    }

    /**
     * The work tree of [dir] against its base; null when neither [dir] nor a folder above it has a `.git`. Any other
     * failure throws: git's exit code 128 means every kind of fatal error, not only "no repository".
     */
    fun state(dir: Path): GitState? {
        if (generateSequence(dir.toAbsolutePath()) { it.parent }.none { it.resolve(".git").exists() }) return null
        check(run(dir, "rev-parse", "--is-inside-work-tree").trim() == "true") { "$dir is not in a work tree" }
        val root = Path.of(run(dir, "rev-parse", "--show-toplevel").trim())
        val current = run(root, "branch", "--show-current").trim().ifEmpty { null }
        // A repository without commits has no HEAD to diff against: all its files are untracked.
        if (!isCommit(root, "HEAD")) return GitState(checkNotNull(current), false, root, null, false, null, untracked(root), null)
        val branch = current ?: ("detached at " + run(root, "rev-parse", "--short=7", "HEAD").trim())
        val stored = current?.let { storedBase(root, it) }
        fun problem(base: String?, problem: String) = GitState(branch, current == null, root, base, stored != null, null, null, problem)
        // defaultBranch throws when the repository names no default; the branch row stays, the reason goes to Changes.
        val base = stored ?: runCatching { repo(root).default }.getOrElse { return problem(null, it.message.orEmpty()) }
        if (!isCommit(root, base)) return problem(base, "Base $base no longer exists")
        // merge-base exits 1, saying nothing, when the two share no commit.
        val mergeBase = run(root, "merge-base", base, "HEAD", ok = setOf(0, 1)).trim().ifEmpty { return problem(base, "Base $base shares no history with $branch") }
        return GitState(branch, current == null, root, base, stored != null, mergeBase, tracked(root, mergeBase) + untracked(root), null)
    }

    /** [path], relative to [root], as it was in [commit]. */
    fun show(root: Path, commit: String, path: String): ByteArray = bytes(root, "show", "$commit:$path")

    private fun isCommit(root: Path, ref: String) = run(root, "rev-parse", "--verify", "-q", "$ref^{commit}", ok = setOf(0, 1)).isNotBlank()

    /**
     * Tracked files changed in the work tree since [mergeBase]; renames are a deletion and an addition. One `git diff`
     * gives both the status (`--raw`: `:<modes> <shas> <status>`, then the path) and the counts (`--numstat`), so a
     * file the session writes meanwhile cannot be in one list and not the other.
     */
    private fun tracked(root: Path, mergeBase: String): List<FileChange> {
        val entries = nulSeparated(run(root, "--no-optional-locks", "diff", "--no-renames", "--raw", "--numstat", "-z", mergeBase))
        // Raw entries take two fields each, a meta line and a path that may itself start with ':'.
        var raw = 0
        while (raw < entries.size && entries[raw].startsWith(":")) raw += 2
        val kinds = entries.take(raw).chunked(2).associate { (meta, path) -> path to kindOf(meta.substringAfterLast(' ')) }
        return entries.drop(raw).map { line ->
            val (added, removed, path) = line.split('\t', limit = 3)
            FileChange(path, kinds.getValue(path), lines(added), lines(removed))
        }.sortedBy { it.path }
    }

    private fun kindOf(status: String) = when (status) {
        "A" -> FileChange.Kind.ADDED
        "M", "T", "U" -> FileChange.Kind.MODIFIED
        "D" -> FileChange.Kind.DELETED
        else -> error("unexpected git diff status $status")
    }

    /**
     * Untracked files, without the nested repositories and worktrees `ls-files` names as `dir/`; anything but a regular
     * file (a symlink to a folder, a broken one) counts no lines.
     */
    private fun untracked(root: Path) = nulSeparated(run(root, "ls-files", "-z", "--others", "--exclude-standard")).filterNot { it.endsWith("/") }.sorted()
        .map { FileChange(it, FileChange.Kind.UNTRACKED, root.resolve(it).let { file -> if (file.isRegularFile()) lineCount(file.readBytes()) else 0 }, 0) }

    /** `-z` output: every entry ends with a NUL. */
    private fun nulSeparated(output: String) = output.split('\u0000').dropLast(1)

    /** The checked-out branch of [dir], or `detached at <sha7>`. */
    fun currentBranch(dir: Path) = run(dir, "branch", "--show-current").trim().ifEmpty { "detached at " + run(dir, "rev-parse", "--short=7", "HEAD").trim() }

    /** A `--numstat` count; a binary file has `-` for both. */
    private fun lines(count: String) = if (count == "-") 0 else count.toInt()

    /**
     * A new [branch] off [base] checked out at [path], with [base] stored as its base; `--no-track` keeps a remote base
     * from becoming its upstream.
     */
    fun addWorktree(mainRoot: Path, path: Path, branch: String, base: String) {
        run(mainRoot, "worktree", "add", "--no-track", "-b", branch, path.toString(), base)
        setBase(mainRoot, branch, base)
    }

    /** The ref [branch] is compared against, kept in the repository's config so it follows `git branch -m`. */
    fun setBase(dir: Path, branch: String, base: String) {
        run(dir, "config", baseKey(branch), base)
    }

    /** The base [setBase] stored for [branch]; null when none was. */
    fun storedBase(dir: Path, branch: String): String? = run(dir, "config", "--get", baseKey(branch), ok = setOf(0, 1)).trim().ifEmpty { null }

    private fun baseKey(branch: String) = "branch.$branch.myAirBase"

    /** Untracked files of [mainRoot] that match `.worktreeinclude` and are gitignored, as `claude --worktree` copies them. */
    fun includedFiles(mainRoot: Path): List<String> {
        val include = mainRoot.resolve(".worktreeinclude")
        if (!include.exists()) return emptyList()
        val matching = run(mainRoot, "ls-files", "-z", "--others", "--ignored", "--exclude-from=$include").split('\u0000').filter { it.isNotEmpty() }
        if (matching.isEmpty()) return emptyList()
        // check-ignore exits 1 when none of the paths is ignored.
        return run(mainRoot, "check-ignore", "-z", "--stdin", input = matching.joinToString("\u0000"), ok = setOf(0, 1))
            .split('\u0000').filter { it.isNotEmpty() }
    }
}
