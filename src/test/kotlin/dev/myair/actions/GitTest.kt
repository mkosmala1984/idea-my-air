package dev.myair.actions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Runs the real `git` on PATH against repositories in a temp directory. */
class GitTest {
    @TempDir
    lateinit var dir: Path

    private fun git(repo: Path, vararg args: String) = Git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", *args).trim()

    private fun init(name: String): Path = dir.resolve(name).createDirectories().also {
        git(it, "init", "-b", "main")
        git(it, "commit", "--allow-empty", "-m", "first")
    }

    /** A clone of a repository that has `main` and `feature`, so it carries `origin/HEAD`. */
    private fun clone(): Path {
        val upstream = init("upstream")
        git(upstream, "branch", "feature")
        git(dir, "clone", upstream.toString(), "clone")
        return dir.resolve("clone")
    }

    @Test
    fun `a clone lists local and remote branches and defaults to origin HEAD`() {
        val repo = Git.repo(clone())
        assertEquals(listOf("main", "origin/feature", "origin/main"), repo.branches)
        assertEquals("origin/main", repo.default)
    }

    @Test
    fun `a repository without origin defaults to its local main`() {
        val repo = init("plain")
        git(repo, "branch", "feature")
        val found = Git.repo(repo)
        assertEquals(listOf("feature", "main"), found.branches)
        assertEquals("main", found.default)
        assertEquals(repo.toRealPath(), found.mainRoot.toRealPath())
    }

    @Test
    fun `a worktree off a remote base gets a new branch at the base without upstream, and finds the main checkout`() {
        val repo = clone()
        val path = worktreePath(repo, ".claude/worktrees", "ab12cd34")
        Git.addWorktree(repo, path, "worktree-ab12cd34", "origin/feature")
        assertEquals(git(repo, "rev-parse", "origin/feature"), git(path, "rev-parse", "HEAD"))
        assertEquals("worktree-ab12cd34", git(path, "branch", "--show-current"))
        assertEquals("", Git.run(repo, "config", "branch.worktree-ab12cd34.remote", ok = setOf(0, 1)))
        assertEquals(repo.toRealPath(), Git.mainRoot(path).toRealPath())
    }

    @Test
    fun `a worktree at an existing path fails with git's message`() {
        val repo = init("plain")
        val path = repo.resolve("taken").createDirectories()
        path.resolve("file").writeText("x")
        val error = assertThrows<IllegalStateException> { Git.addWorktree(repo, path, "worktree-x", "main") }
        assertContains(error.message!!, "already exists")
    }

    @Test
    fun `included files are the untracked gitignored ones matching worktreeinclude`() {
        val repo = init("plain")
        repo.resolve(".gitignore").writeText(".env\nsecret.txt\ncache/\n")
        repo.resolve(".worktreeinclude").writeText(".env\ntracked.txt\ncache/keep.json\n")
        repo.resolve("tracked.txt").writeText("t")
        git(repo, "add", ".gitignore", "tracked.txt")
        git(repo, "commit", "-m", "files")
        repo.resolve(".env").writeText("KEY=1")
        repo.resolve("secret.txt").writeText("s")
        repo.resolve("cache").createDirectories().resolve("keep.json").writeText("{}")
        repo.resolve("cache/other.json").writeText("{}")
        val files = Git.includedFiles(repo)
        assertEquals(listOf(".env", "cache/keep.json"), files.sorted())

        val worktree = dir.resolve("wt")
        Git.addWorktree(repo, worktree, "worktree-wt", "main")
        copyIncluded(repo, worktree, files)
        assertEquals("KEY=1", worktree.resolve(".env").readText())
        assertEquals("{}", worktree.resolve("cache/keep.json").readText())
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `an input larger than the pipe buffers does not block`() {
        val repo = init("plain")
        repo.resolve(".gitignore").writeText("*.tmp\n")
        val paths = (1..20_000).map { "dir$it/file-with-a-long-name-$it.tmp" }
        val ignored = Git.run(repo, "check-ignore", "-z", "--stdin", input = paths.joinToString("\u0000"))
        assertEquals(paths, ignored.split('\u0000').filter { it.isNotEmpty() })
    }

    @Test
    fun `without worktreeinclude nothing is included`() {
        val repo = init("plain")
        repo.resolve(".gitignore").writeText(".env\n")
        repo.resolve(".env").writeText("KEY=1")
        assertEquals(emptyList(), Git.includedFiles(repo))
    }

    @Test
    fun `a new worktree branch stores its base`() {
        val repo = clone()
        Git.addWorktree(repo, worktreePath(repo, ".claude/worktrees", "ab12cd34"), "worktree-ab12cd34", "origin/feature")
        assertEquals("origin/feature", Git.storedBase(repo, "worktree-ab12cd34"))
    }

    @Test
    fun `a branch without a stored base has none, and a set one survives a rename`() {
        val repo = init("plain")
        git(repo, "branch", "topic")
        assertEquals(null, Git.storedBase(repo, "topic"))
        Git.setBase(repo, "topic", "main")
        git(repo, "branch", "-m", "topic", "renamed")
        assertEquals("main", Git.storedBase(repo, "renamed"))
    }

    @Test
    fun `state of a repository names its branch and counts uncommitted lines`() {
        val repo = init("changed")
        repo.resolve("a.txt").writeText("1\n2\n3\n")
        git(repo, "add", "a.txt")
        git(repo, "commit", "-m", "a")
        repo.resolve("a.txt").writeText("1\nX\n3\n4\n5\n")
        assertEquals(GitState("main", 3, 1), Git.state(repo))
    }

    @Test
    fun `state of a detached head names its commit`() {
        val repo = init("detached")
        git(repo, "checkout", "--detach")
        assertEquals(GitState("detached at ${git(repo, "rev-parse", "--short=7", "HEAD")}", 0, 0), Git.state(repo))
    }

    @Test
    fun `state of a repository without commits is its branch with no changes`() {
        val repo = dir.resolve("unborn").createDirectories().also { git(it, "init", "-b", "main") }
        assertEquals(GitState("main", 0, 0), Git.state(repo))
    }

    @Test
    fun `state outside a repository is null`() {
        assertEquals(null, Git.state(dir.resolve("plain").createDirectories()))
    }

    @Test
    fun `state of a broken repository fails with git's message`() {
        val broken = dir.resolve("broken").createDirectories().also { it.resolve(".git").writeText("gitdir: nowhere\n") }
        assertContains(assertThrows<IllegalStateException> { Git.state(broken) }.message!!, "not a git repository")
    }
}
