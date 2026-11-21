package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.store.Profile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileReadsTest {
    @TempDir
    lateinit var dir: Path

    private val default = Profile("default", Path.of("/home/.claude"))
    private val test = Profile("test", Path.of("/profiles/test"))

    private fun session(id: String, at: Long) = ClaudeSession(id, Path.of("/rtm"), id, Instant.ofEpochMilli(at), emptyList(), emptyList())

    private fun live(pid: Long) = LiveSession(pid, "cli", SessionStatus.IDLE, null, Instant.EPOCH, Path.of("/rtm"), Instant.EPOCH)

    @Test
    fun `sessions get their profile`() {
        val merged = mergeSessions(mapOf(default to listOf(session("a", 1)), test to listOf(session("b", 2))))
        assertEquals(listOf("b" to "test", "a" to "default"), merged.map { it.id to it.profile })
    }

    @Test
    fun `one id in two profiles throws naming both dirs`() {
        val e = assertFailsWith<IllegalStateException> { mergeSessions(mapOf(default to listOf(session("s", 1)), test to listOf(session("s", 2)))) }
        assertEquals("session s is in ${default.dir} and ${test.dir}", e.message)
        val live = assertFailsWith<IllegalStateException> { mergeLive(mapOf(default to mapOf("s" to live(1)), test to mapOf("s" to live(2)))) }
        assertEquals("session s is in ${default.dir} and ${test.dir}", live.message)
    }

    @Test
    fun `merged live entries keep pid and profile`() {
        val merged = mergeLive(mapOf(default to mapOf("d" to live(7)), test to mapOf("s" to live(42))))
        assertEquals(42L, merged["s"]!!.pid)
        assertEquals("test", merged["s"]!!.profile)
        assertEquals("default", merged["d"]!!.profile)
    }

    @Test
    fun `a profile without its sessions dir throws naming it`() {
        dir.resolve("projects").createDirectories()
        val e = assertFailsWith<IllegalStateException> { requireProfileDirs(Profile("test", dir)) }
        assertTrue(dir.resolve("sessions").toString() in e.message!!, e.message)
        dir.resolve("sessions").createDirectories()
        requireProfileDirs(Profile("test", dir))
    }
}
