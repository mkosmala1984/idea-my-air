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

    /** Writes [text] to [name] in [repo] and commits it. */
    private fun commit(repo: Path, name: String, text: String) {
        repo.resolve(name).writeText(text)
        git(repo, "add", name)
        git(repo, "commit", "-m", name)
    }

    private fun state(dir: Path) = checkNotNull(Git.state(dir))

    @Test
    fun `a worktree diffs against the merge base with its stored base, untracked files included`() {
        val repo = clone()
        val path = worktreePath(repo, ".claude/worktrees", "ab12cd34")
        Git.addWorktree(repo, path, "worktree-ab12cd34", "origin/feature")
        commit(path, "a.txt", "1\n2\n")
        path.resolve("a.txt").writeText("1\n2\n3\n")
        path.resolve("new.txt").writeText("x\ny\n")
        val state = state(path)
        assertEquals("worktree-ab12cd34", state.branch)
        assertEquals(false, state.detached)
        assertEquals("origin/feature", state.base)
        assertEquals(true, state.baseStored)
        assertEquals(git(repo, "rev-parse", "origin/feature"), state.mergeBase)
        assertEquals(path.toRealPath(), state.root.toRealPath())
        assertEquals(listOf(FileChange("a.txt", FileChange.Kind.ADDED, 3, 0), FileChange("new.txt", FileChange.Kind.UNTRACKED, 2, 0)), state.changes)
        assertEquals(5, state.added)
        assertEquals(0, state.removed)
    }

    @Test
    fun `without a stored base the default branch is the base`() {
        val repo = clone()
        git(repo, "checkout", "-b", "topic")
        commit(repo, "t.txt", "t\n")
        val state = state(repo)
        assertEquals("origin/main", state.base)
        assertEquals(false, state.baseStored)
        assertEquals(listOf(FileChange("t.txt", FileChange.Kind.ADDED, 1, 0)), state.changes)
    }

    @Test
    fun `commits the base got after the branch was cut are not changes`() {
        val repo = clone()
        git(repo, "checkout", "-b", "topic")
        commit(repo, "t.txt", "t\n")
        commit(dir.resolve("upstream"), "u.txt", "u\n")
        git(repo, "fetch")
        assertEquals(listOf(FileChange("t.txt", FileChange.Kind.ADDED, 1, 0)), state(repo).changes)
    }

    @Test
    fun `a stored base that names no commit leaves changes null`() {
        val repo = init("plain")
        git(repo, "checkout", "-b", "topic")
        Git.setBase(repo, "topic", "gone")
        val state = state(repo)
        assertEquals("gone", state.base)
        assertEquals(true, state.baseStored)
        assertEquals(null, state.mergeBase)
        assertEquals(null, state.changes)
        assertEquals("Base gone no longer exists", state.problem)
    }

    @Test
    fun `a base without history in common with HEAD is a problem, not a failure`() {
        val repo = init("plain")
        git(repo, "checkout", "-q", "--orphan", "pages")
        commit(repo, "p.txt", "p\n")
        git(repo, "checkout", "-q", "main")
        Git.setBase(repo, "main", "pages")
        val state = state(repo)
        assertEquals("main", state.branch)
        assertEquals(null, state.changes)
        assertEquals("Base pages shares no history with main", state.problem)
    }

    @Test
    fun `a default branch that cannot be told keeps the branch and names the problem`() {
        val repo = dir.resolve("develop").createDirectories().also { git(it, "init", "-b", "develop") }
        commit(repo, "a.txt", "a\n")
        val state = state(repo)
        assertEquals("develop", state.branch)
        assertEquals(null, state.base)
        assertEquals(null, state.changes)
        assertContains(state.problem!!, "cannot tell the default branch")
    }

    @Test
    fun `an untracked nested repository is not a changed file`() {
        val repo = init("outer")
        init("outer/inner")
        repo.resolve("new.txt").writeText("n\n")
        assertEquals(listOf(FileChange("new.txt", FileChange.Kind.UNTRACKED, 1, 0)), state(repo).changes)
    }

    @Test
    fun `uncommitted edits and deletions count against the merge base`() {
        val repo = init("changed")
        commit(repo, "a.txt", "1\n2\n3\n")
        commit(repo, "b.txt", "1\n2\n")
        repo.resolve("a.txt").writeText("1\nX\n3\n4\n5\n")
        git(repo, "rm", "-q", "b.txt")
        val state = state(repo)
        assertEquals("main", state.branch)
        assertEquals("main", state.base)
        assertEquals(git(repo, "rev-parse", "HEAD"), state.mergeBase)
        assertEquals(listOf(FileChange("a.txt", FileChange.Kind.MODIFIED, 3, 1), FileChange("b.txt", FileChange.Kind.DELETED, 0, 2)), state.changes)
    }

    @Test
    fun `a detached head diffs against the default branch`() {
        val repo = init("detached")
        git(repo, "checkout", "--detach")
        val state = state(repo)
        assertEquals("detached at ${git(repo, "rev-parse", "--short=7", "HEAD")}", state.branch)
        assertEquals(true, state.detached)
        assertEquals("main", state.base)
        assertEquals(false, state.baseStored)
        assertEquals(emptyList(), state.changes)
    }

    @Test
    fun `a repository without commits lists its files as untracked`() {
        val repo = dir.resolve("unborn").createDirectories().also { git(it, "init", "-b", "main") }
        repo.resolve("a.txt").writeText("a\n")
        val state = state(repo)
        assertEquals("main", state.branch)
        assertEquals(null, state.base)
        assertEquals(null, state.mergeBase)
        assertEquals(listOf(FileChange("a.txt", FileChange.Kind.UNTRACKED, 1, 0)), state.changes)
    }

    @Test
    fun `from a subdirectory paths are relative to the root and untracked files outside it count`() {
        val repo = init("nested")
        commit(repo, "a.txt", "a\n")
        val sub = repo.resolve("sub").createDirectories()
        repo.resolve("a.txt").writeText("b\n")
        sub.resolve("s.txt").writeText("s\n")
        repo.resolve("top.txt").writeText("t\n")
        val state = state(sub)
        assertEquals(repo.toRealPath(), state.root.toRealPath())
        assertEquals(
            listOf(FileChange("a.txt", FileChange.Kind.MODIFIED, 1, 1), FileChange("sub/s.txt", FileChange.Kind.UNTRACKED, 1, 0), FileChange("top.txt", FileChange.Kind.UNTRACKED, 1, 0)),
            state.changes,
        )
    }

    @Test
    fun `lines are counted as git counts them and a binary file has none`() {
        assertEquals(0, lineCount(byteArrayOf()))
        assertEquals(1, lineCount("a".toByteArray()))
        assertEquals(1, lineCount("a\n".toByteArray()))
        assertEquals(2, lineCount("a\nb".toByteArray()))
        assertEquals(0, lineCount(byteArrayOf(97, 0, 10)))
    }

    @Test
    fun `show reads a file as it was in a commit`() {
        val repo = init("shown")
        commit(repo, "a.txt", "old\n")
        repo.resolve("a.txt").writeText("new\n")
        assertEquals("old\n", Git.show(repo, git(repo, "rev-parse", "HEAD"), "a.txt").toString(Charsets.UTF_8))
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
