package dev.myair.dashboard

import dev.myair.actions.Git
import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** Runs the real `git` on PATH against repositories in a temp directory. */
class GitFactsTest {
    @TempDir
    lateinit var dir: Path

    private fun init(name: String): Path = dir.resolve(name).createDirectories().also {
        Git.run(it, "init", "-b", "main")
        Git.run(it, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--allow-empty", "-m", "first")
    }

    private fun session(id: String, at: String, file: Path) =
        ClaudeSession(id, file.parent, id, Instant.parse(at), listOf(file), emptyList())

    @Test
    fun `facts land work tree by work tree, the one of the newest session first`() {
        val older = init("older").resolve("a.txt").apply { writeText("a") }
        val newer = init("newer").resolve("b.txt").apply { writeText("b") }
        val outside = dir.resolve("loose").createDirectories().resolve("c.txt").apply { writeText("c") }
        val sessions = listOf(
            session("old", "2026-11-01T10:00:00Z", older), session("new", "2026-11-12T10:00:00Z", newer),
            session("loose", "2026-11-05T10:00:00Z", outside),
        )
        val landed = ArrayList<Set<String>>()
        val all = GitFacts().read(sessions) { landed += it.keys }
        assertEquals(listOf(setOf("loose"), setOf("new"), setOf("old")), landed)
        assertEquals(mapOf("old" to true, "new" to true, "loose" to false), all.mapValues { it.value.getOrThrow().uncommitted })
    }

    @Test
    fun `a branch is read on its own, so an old session's branch in a busy repository does not hold back newer sessions`() {
        val busy = init("busy")
        listOf("x", "y").forEach { Git.run(busy, "branch", it) }
        val other = init("other").resolve("c.txt").apply { writeText("c") }
        fun onBranch(id: String, at: String, branch: String) =
            ClaudeSession(id, busy, id, Instant.parse(at), emptyList(), emptyList(), lastBranch = branch)
        val sessions = listOf(onBranch("newest", "2026-11-12T10:00:00Z", "x"), session("middle", "2026-11-08T10:00:00Z", other), onBranch("oldest", "2026-11-01T10:00:00Z", "y"))
        val landed = ArrayList<Set<String>>()
        GitFacts().read(sessions) { if (it.isNotEmpty()) landed += it.keys }
        assertEquals(listOf(setOf("newest"), setOf("middle"), setOf("oldest")), landed)
    }
}
