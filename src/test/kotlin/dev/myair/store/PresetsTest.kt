package dev.myair.store

import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetsTest {
    private val full = Preset("Review", "opus", "high", "E:/dev/rtm", worktree = true, base = "origin/main", addDirs = listOf("E:/dev/lib", "E:/docs"), pinned = true)

    @Test
    fun `adds model, effort and every added directory as claude flags, and leaves out what is not set`() {
        assertEquals(listOf("--model", "opus", "--effort", "high", "--add-dir", "E:/dev/lib", "--add-dir", "E:/docs"), full.claudeArgs())
        assertEquals(emptyList(), Preset("Plain").claudeArgs())
    }

    @Test
    fun `survives a round trip through json, also without optional values`() {
        val presets = listOf(full, Preset("Plain"), Preset("Ask", worktree = true))
        assertEquals(presets, parsePresets(presetsJson(presets)))
    }

    @Test
    fun `reads the file as written by hand, absent keys meaning not set`() {
        val text = """{"presets": [{"name": "Fast", "model": "sonnet", "effort": "low"}, {"name": "Wt", "worktree": true, "base": "main"}]}"""
        assertEquals(listOf(Preset("Fast", "sonnet", "low"), Preset("Wt", worktree = true, base = "main")), parsePresets(text))
    }

    @Test
    fun `rejects unknown keys, wrong types, unknown efforts, a base without a worktree and repeated names`() {
        listOf(
            """{"presets": [], "x": 1}""",
            """{"presets": [{"name": "a", "colour": "red"}]}""",
            """{"presets": [{"name": "a", "worktree": "yes"}]}""",
            """{"presets": [{"name": "a", "model": 4}]}""",
            """{"presets": [{"name": "a", "effort": "huge"}]}""",
            """{"presets": [{"name": "a", "base": "main"}]}""",
            """{"presets": [{"name": " "}]}""",
            """{"presets": [{"name": "a", "addDirs": [""]}]}""",
            """{"presets": [{"name": "a"}, {"name": "a"}]}""",
        ).forEach { text -> assertThrows<Exception>(text) { parsePresets(text) } }
    }

    @Test
    fun `a session's preset has its settings, and a worktree with the base picked at start when it runs in one`() {
        val project = Path.of("E:/dev/rtm")
        val session = ClaudeSession("s1", project, "t", Instant.EPOCH, emptyList(), emptyList(), addedDirs = listOf(Path.of("E:/docs")), model = "claude-opus-5-5", effort = "xhigh")
        assertEquals(Preset("Mine", "claude-opus-5-5", "xhigh", project.toString(), addDirs = listOf(Path.of("E:/docs").toString())), presetOf(session, "Mine"))
        val inWorktree = session.copy(cwd = Path.of("E:/dev/rtm/.claude/worktrees/a1b2c3d4"), model = null, effort = null, addedDirs = emptyList())
        assertEquals(Preset("Mine", project = project.toString(), worktree = true), presetOf(inWorktree, "Mine"))
    }

    @Test
    fun `the store reads what it wrote, sees a later change, and reports a broken file`(@TempDir dir: Path) {
        val store = PresetStore(dir.resolve("presets.json"))
        assertEquals(emptyList(), store.read().getOrThrow())
        store.write(listOf(full))
        assertEquals(listOf(full), store.read().getOrThrow())
        store.write(listOf(Preset("Plain")))
        assertEquals(listOf(Preset("Plain")), store.read().getOrThrow())
        store.file.writeText("{")
        assertTrue(store.read().isFailure)
    }
}
