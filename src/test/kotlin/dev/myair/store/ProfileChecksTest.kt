package dev.myair.store

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileChecksTest {
    @TempDir
    lateinit var tmp: Path

    private fun live(profile: String) =
        LiveSession(1, "cli", SessionStatus.WORKING, null, Instant.EPOCH, tmp, Instant.EPOCH, profile = profile)

    private fun claudeJson(text: String) = tmp.resolve(".claude.json").also { it.writeText(text) }

    @Test
    fun `ideServer reads the http idea entry`() {
        val file = claudeJson("""{"mcpServers":{"idea":{"type":"http","url":"http://127.0.0.1:64342/stream"}}}""")
        assertEquals("idea" to "http://127.0.0.1:64342/stream", ideServer(file))
    }

    @Test
    fun `ideServer is null without one`() {
        assertNull(ideServer(claudeJson("""{"mcpServers":{"idea":{"type":"stdio","command":"x"}}}""")))
        assertNull(ideServer(claudeJson("""{}""")))
    }

    @Test
    fun `ideServer is null without the file`() {
        assertNull(ideServer(tmp.resolve("missing.json")))
    }

    @Test
    fun `ideServer throws on malformed json`() {
        assertThrows(Exception::class.java) { ideServer(claudeJson("{")) }
    }

    @Test
    fun `mcpAddCommand refuses a quote in the directory`() {
        assertThrows(IllegalArgumentException::class.java) { dev.myair.store.mcpAddCommand(Profile("q", Path.of("C:/o'x")), "idea" to "u") }
    }

    @Test
    fun mcpAddCommand() {
        assertEquals(
            "\$env:CLAUDE_CONFIG_DIR='C:\\p'; claude mcp add --scope user --transport http idea http://127.0.0.1:64342/stream",
            dev.myair.store.mcpAddCommand(Profile("test", Path.of("C:\\p")), "idea" to "http://127.0.0.1:64342/stream"),
        )
    }

    @Test
    fun `remove is refused while a session of the profile is live`() {
        val reason = removeRefusal("test", listOf(live("default"), live("test")), emptyList(), emptyList(), emptyList())
        assertNotNull(reason)
        assertEquals("A session of profile test is running (pid 1).", reason)
    }

    @Test
    fun `remove is refused while a tab shows it`() {
        assertNotNull(removeRefusal("test", emptyList(), listOf("test"), emptyList(), emptyList()))
    }

    @Test
    fun `remove is allowed otherwise`() {
        assertNull(removeRefusal("test", listOf(live("default")), listOf("default"), emptyList(), emptyList()))
    }

    private val url = "http://127.0.0.1:64342/stream"
    private fun defaultJson() = claudeJson("""{"mcpServers":{"idea":{"type":"http","url":"$url"}}}""")
    private fun profile(json: String?) = Profile("test", tmp.resolve("p")).also { p ->
        p.dir.createDirectories()
        json?.let { p.dir.resolve(".claude.json").writeText(it) }
    }

    @Test
    fun `managerToolsRefusal is null for default`() {
        assertNull(managerToolsRefusal(Profile(DEFAULT_PROFILE, tmp), tmp.resolve("missing.json")))
    }

    @Test
    fun `refuses a profile without the server`() {
        val p = profile(null)
        val expected = "Profile test has no My Air MCP server. Run:\n" + mcpAddCommand(p, "idea" to url)
        assertEquals(expected, managerToolsRefusal(p, defaultJson()))
        profile("""{"mcpServers":{"other":{"type":"http","url":"http://x"}}}""")
        assertEquals(expected, managerToolsRefusal(p, defaultJson()))
    }

    @Test
    fun `accepts a profile with the same url under another name`() {
        assertNull(managerToolsRefusal(profile("""{"mcpServers":{"ide":{"type":"http","url":"$url"}}}"""), defaultJson()))
    }

    @Test
    fun `remove is refused while a plan manager names it`() {
        assertEquals("A plan's or review's manager uses profile test.", removeRefusal("test", emptyList(), emptyList(), listOf("test"), emptyList()))
    }

    @Test
    fun `createProfileDirs makes projects and sessions`() {
        val dir = tmp.resolve("p")
        createProfileDirs(Profile("p", dir))
        assertTrue(dir.resolve("projects").exists() && dir.resolve("sessions").exists())
    }
}
