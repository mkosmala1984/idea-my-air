package dev.myair.store

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class SeenTest {
    @TempDir
    lateinit var root: Path

    private val sessions = listOf(
        ClaudeSession("a", Path.of("/p"), "a", Instant.ofEpochMilli(100), emptyList(), emptyList()),
        ClaudeSession("b", Path.of("/p"), "b", Instant.ofEpochMilli(200), emptyList(), emptyList()),
    )

    @Test
    fun `a mark is read back, and temp files of a write in progress are not`() {
        val store = SeenStore(root.resolve("seen"))
        store.mark("a", 42)
        store.dir.resolve("b.3f2c.tmp").writeText("7")
        assertEquals(mapOf("a" to 42L), store.read())
    }

    @Test
    fun `running sessions not seen yet are marked at their last message, others are left alone`() {
        val live = mapOf("a" to LiveSession(1, "cli", SessionStatus.IDLE, null, Instant.EPOCH), "b" to LiveSession(2, "cli", SessionStatus.IDLE, null, Instant.EPOCH))
        assertEquals(mapOf("b" to 200L), firstSight(sessions, live, mapOf("a" to 10L)))
        assertEquals(emptyMap(), firstSight(sessions, emptyMap(), emptyMap()))
    }

    @Test
    fun `a visible session is marked only when it has a message the user has not seen`() {
        assertEquals(listOf("b"), newToUser(listOf("a", "b", "unknown"), sessions, mapOf("a" to 100L, "b" to 150L)))
        assertEquals(listOf("a"), newToUser(listOf("a"), sessions, emptyMap()))
    }
}
