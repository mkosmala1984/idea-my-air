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
    fun `puts the first prompt after --, so the variadic --add-dir does not take it and a word like mcp is no subcommand`() {
        assertEquals(listOf("--add-dir", "E:/docs", "--", "mcp"), Preset("Docs", addDirs = listOf("E:/docs")).claudeArgs("mcp"))
        assertThrows<IllegalArgumentException> { Preset("Plain").claudeArgs(" \n") }
    }

    @Test
    fun `names the start directory and added directories that are not directories`(@TempDir temp: Path) {
        val gone = temp.resolve("gone")
        assertEquals(listOf(gone), Preset("Dirs", addDirs = listOf(temp.toString(), gone.toString())).missingDirs(temp))
        assertEquals(listOf(gone), Preset("Dirs").missingDirs(gone))
    }

    @Test
    fun `the prompt's head is its first line, cut with an ellipsis past 40 characters`() {
        assertEquals("/code-review", Preset("R", prompt = "/code-review\nthen wait").promptHead())
        assertEquals("a".repeat(40) + "…", Preset("R", prompt = "a".repeat(41)).promptHead())
        assertEquals("a".repeat(40), Preset("R", prompt = "a".repeat(40)).promptHead())
        assertEquals(null, Preset("R").promptHead())
    }

    @Test
    fun `survives a round trip through json, also without optional values`() {
        val presets = listOf(full, Preset("Plain"), Preset("Ask", worktree = true), Preset("Rv", prompt = "/code-review\nthen wait", askMore = true))
        assertEquals(presets, parsePresets(presetsJson(presets)))
    }

    @Test
    fun `reads the file as written by hand, absent keys meaning not set`() {
        val text = """{"presets": [{"name": "Fast", "model": "sonnet", "effort": "low"}, {"name": "Wt", "worktree": true, "base": "main"}, {"name": "Rv", "prompt": "/code-review", "askMore": true}]}"""
        assertEquals(
            listOf(Preset("Fast", "sonnet", "low"), Preset("Wt", worktree = true, base = "main"), Preset("Rv", prompt = "/code-review", askMore = true)),
            parsePresets(text),
        )
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
            """{"presets": [{"name": "a", "prompt": " "}]}""",
            """{"presets": [{"name": "a", "askMore": "yes"}]}""",
        ).forEach { text -> assertThrows<Exception>(text) { parsePresets(text) } }
    }

    private val pick = Preset("Pick", variants = listOf(Variant("opus", "high"), Variant("sonnet")))

    @Test
    fun `model and effort pairs survive a round trip, and a preset without them writes no variants key`() {
        assertEquals(listOf(pick), parsePresets(presetsJson(listOf(pick))))
        assertTrue("variants" !in presetsJson(listOf(Preset("Plain"))))
    }

    @Test
    fun `reads pairs written by hand`() {
        val text = """{"presets": [{"name": "P", "variants": [{"model": "opus", "effort": "high"}, {"effort": "low"}]}]}"""
        assertEquals(listOf(Preset("P", variants = listOf(Variant("opus", "high"), Variant(effort = "low")))), parsePresets(text))
    }

    @Test
    fun `rejects a single pair, a repeated pair, pairs beside a model, unknown keys, an empty pair and an unknown effort`() {
        listOf(
            """{"presets": [{"name": "a", "variants": [{"model": "opus"}]}]}""",
            """{"presets": [{"name": "a", "variants": [{"model": "opus"}, {"model": "opus"}]}]}""",
            """{"presets": [{"name": "a", "model": "opus", "variants": [{"model": "opus"}, {"model": "sonnet"}]}]}""",
            """{"presets": [{"name": "a", "variants": [{"model": "opus", "x": 1}, {"model": "sonnet"}]}]}""",
            """{"presets": [{"name": "a", "variants": [{}, {"model": "sonnet"}]}]}""",
            """{"presets": [{"name": "a", "variants": [{"effort": "huge"}, {"model": "sonnet"}]}]}""",
        ).forEach { text -> assertThrows<Exception>(text) { parsePresets(text) } }
    }

    @Test
    fun `a picked pair gives a preset with its model and effort and no pairs, the first one where nothing is picked`() {
        assertEquals(Preset("Pick", model = "sonnet"), pick.withVariant(Variant("sonnet")))
        assertEquals(Preset("Pick", "opus", "high"), pick.firstVariant())
        assertEquals(full, full.firstVariant())
        assertThrows<IllegalArgumentException> { pick.withVariant(Variant("fable")) }
    }

    @Test
    fun `pairs typed one per line read back as written`() {
        assertEquals(listOf(Variant("opus", "high"), Variant("sonnet"), Variant(effort = "low")), parseVariants(" opus high\n\nsonnet\n- low\n"))
        assertEquals(listOf(Variant("opus", "high")), parseVariants("high opus"))
        val variants = listOf(Variant("opus", "high"), Variant("sonnet"), Variant(effort = "low"))
        assertEquals(variants, parseVariants(variantsText(variants)))
        listOf("opus sonnet", "low high", "-", "opus huge high").forEach { assertThrows<IllegalArgumentException>(it) { parseVariants(it) } }
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
    fun `a continuation's first message names the session, its title on one line, and its transcript`() {
        val session = ClaudeSession("3fb8-id", Path.of("E:/dev/rtm"), "Fix the\n  login bug ", Instant.EPOCH, emptyList(), emptyList(), transcript = Path.of("C:/p/projects/x/s.jsonl"))
        val prompt = continuationPrompt(session)
        assertTrue(prompt.startsWith("This session continues Claude Code session 3fb8-id (Fix the login bug). "), prompt)
        assertTrue('\n' !in prompt, prompt)
    }

    @Test
    fun `continuationPrompt names the transcript path`() {
        val path = Path.of("C:/p/projects/x/s.jsonl")
        val prompt = continuationPrompt(ClaudeSession("s", Path.of("E:/dev/rtm"), "t", Instant.EPOCH, emptyList(), emptyList(), transcript = path))
        assertTrue(path.toString() in prompt, prompt)
        assertTrue("~/.claude" !in prompt, prompt)
    }

    @Test
    fun `a preset's profile round-trips and is left out when null`() {
        val with = Preset("P", profile = "test")
        assertEquals(listOf(with), parsePresets(presetsJson(listOf(with))))
        assertTrue("profile" !in presetsJson(listOf(Preset("Plain"))))
    }

    @Test
    fun `presetOf keeps a non-default profile`() {
        val session = ClaudeSession("s", Path.of("E:/dev/rtm"), "t", Instant.EPOCH, emptyList(), emptyList(), profile = "test")
        assertEquals("test", presetOf(session, "Mine").profile)
    }

    @Test
    fun `presetOf of a default session has no profile`() {
        assertEquals(null, presetOf(ClaudeSession("s", Path.of("E:/dev/rtm"), "t", Instant.EPOCH, emptyList(), emptyList()), "Mine").profile)
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
