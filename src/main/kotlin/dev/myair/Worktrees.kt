package dev.myair

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

const val MAX_RECENT_BASES = 5

/** Popup content: [items] in display order, separators above [recentStart] and [restStart], [selected] preselected. */
data class BaseChoices(val items: List<String>, val recentStart: Int, val restStart: Int, val selected: Int)

/** The default branch first, then the recent bases that still exist, then every other branch. */
fun baseChoices(branches: List<String>, default: String, recent: List<String>): BaseChoices {
    check(default in branches) { "default branch $default is not among the branches $branches" }
    val shownRecent = recent.filter { it != default && it in branches }
    val rest = branches - (shownRecent + default).toSet()
    val selected = if (recent.firstOrNull { it in branches }.let { it == null || it == default }) 0 else 1
    return BaseChoices(listOf(default) + shownRecent + rest, 1, 1 + shownRecent.size, selected)
}

/** [chosen] becomes the newest recent base; bases no longer among [branches] are dropped. */
fun pushRecent(recent: List<String>, chosen: String, branches: List<String>): List<String> =
    (listOf(chosen) + recent.filter { it != chosen && it in branches }).take(MAX_RECENT_BASES)

/**
 * The branch `origin/HEAD` points at ([originHead], e.g. `origin/main`); a repository without `origin` has no such
 * pointer, so its default is whichever one of `main` and `master` exists locally.
 */
fun defaultBranch(remotes: List<String>, originHead: String?, local: List<String>): String {
    if ("origin" in remotes) return checkNotNull(originHead) { "origin/HEAD is not set; run: git remote set-head origin -a" }
    val candidates = local.filter { it == "main" || it == "master" }
    check(candidates.size == 1) { "no origin remote and the local branches main/master found are $candidates: cannot tell the default branch" }
    return candidates.single()
}

/** A relative [setting] resolves against [mainRoot]; [Path.resolve] returns an absolute one unchanged. */
fun worktreePath(mainRoot: Path, setting: String, name: String): Path = mainRoot.resolve(setting).resolve(name)

/** `for-each-ref --format=%(refname)` output to short names, without the `<remote>/HEAD` pointers. */
fun parseBranches(refs: String): List<String> = refs.lines().filter { it.isNotEmpty() && !(it.startsWith("refs/remotes/") && it.endsWith("/HEAD")) }
    .map { it.removePrefix("refs/heads/").removePrefix("refs/remotes/") }

/** Copies [files], relative to [mainRoot], to the same relative paths in [worktree]. */
fun copyIncluded(mainRoot: Path, worktree: Path, files: List<String>) {
    for (file in files) {
        val target = worktree.resolve(file)
        target.parent.createDirectories()
        Files.copy(mainRoot.resolve(file), target, StandardCopyOption.COPY_ATTRIBUTES)
    }
}

/** A git repository as the worktree popup needs it. */
data class GitRepo(val mainRoot: Path, val branches: List<String>, val default: String)

/** Runs the `git` on PATH; any exit code outside `ok` throws with git's stderr. */
object Git {
    fun run(dir: Path, vararg args: String, input: String? = null, ok: Set<Int> = setOf(0)): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).start()
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        // Written apart from the stdout read: git may fill its stdout pipe before it has read all of a large input.
        val stdin = CompletableFuture.runAsync { process.outputStream.use { if (input != null) it.write(input.toByteArray()) } }
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
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

    /** A new [branch] off [base] checked out at [path]; `--no-track` keeps a remote base from becoming its upstream. */
    fun addWorktree(mainRoot: Path, path: Path, branch: String, base: String) {
        run(mainRoot, "worktree", "add", "--no-track", "-b", branch, path.toString(), base)
    }

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
