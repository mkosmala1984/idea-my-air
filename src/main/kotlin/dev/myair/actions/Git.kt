package dev.myair.actions

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.exists

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
