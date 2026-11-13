package dev.myair.prompt

import dev.myair.store.Preset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class PromptChoicesTest {
    private val review = Preset("Review", "sonnet", "high", "E:/dev/rtm", worktree = true, base = "origin/main")
    private val presets = listOf(DEFAULT_PRESET, review)

    @Test
    fun `a configuration without a model or effort starts with opus and medium`() {
        assertEquals(Choice(DEFAULT_PRESET, "opus", "medium", false), choiceOf(DEFAULT_PRESET))
        assertEquals(Choice(review, "sonnet", "high", true), choiceOf(review))
    }

    @Test
    fun `the last choices win over the last configuration's own, and a gone configuration gives way to the first`() {
        assertEquals(Choice(review, "fable", "high", false), restore(presets, LastChoice("Review", "fable", null, false)))
        assertEquals(choiceOf(DEFAULT_PRESET), restore(presets, LastChoice(null, null, null, null)))
        assertEquals(Choice(DEFAULT_PRESET, "opus", "max", true), restore(presets, LastChoice("Gone", null, "max", true)))
    }

    @Test
    fun `a new branch is restored only with a worktree, and never without one`() {
        assertEquals(Choice(review, "sonnet", "high", true, newBranch = true), restore(presets, LastChoice("Review", null, null, null, true)))
        assertEquals(Choice(review, "sonnet", "high", false), restore(presets, LastChoice("Review", null, null, false, true)))
        assertEquals(Choice(DEFAULT_PRESET, "opus", "medium", true, newBranch = true).toLast().newBranch, true)
        assertThrows<IllegalArgumentException> { Choice(review, "opus", "low", false, newBranch = true) }
    }

    @Test
    fun `a draft opens on the choices it was typed with, not the last start's`() {
        val last = LastChoice("Default", "fable", "low", false)
        val typed = Choice(review, "opus", "max", true)
        assertEquals(typed, opening(presets, last, Draft("fix the test", typed.toLast(), "origin/main")))
        assertEquals(restore(presets, last), opening(presets, last, null))
        assertThrows<IllegalArgumentException> { Draft(" ", last, null) }
    }

    @Test
    fun `the started preset carries the choices, and a base only in a worktree`() {
        assertEquals(review.copy(model = "opus", effort = "low", base = "feature"), Choice(review, "opus", "low", true).toPreset("feature"))
        assertEquals(review.copy(worktree = false, base = null), Choice(review, "sonnet", "high", false).toPreset("feature"))
        assertEquals(review.copy(base = null), choiceOf(review).toPreset(null))
    }

    @Test
    fun `an unknown effort is refused`() {
        assertThrows<IllegalArgumentException> { Choice(DEFAULT_PRESET, "opus", "turbo", false) }
    }

    @Test
    fun `tags the current, default and recent branches, in that order`() {
        assertEquals("current", branchTag("main", "main", "main", listOf("main")))
        assertEquals("default", branchTag("main", "feature", "main", emptyList()))
        assertEquals("recent", branchTag("feature", null, "main", listOf("feature")))
        assertEquals(null, branchTag("other", "feature", "main", listOf("feature")))
    }

    @Test
    fun `names an alias as Claude's model and leaves a full name alone`() {
        assertEquals("Claude Opus", modelLabel("opus"))
        assertEquals("claude-opus-5-5", modelLabel("claude-opus-5-5"))
    }

    @Test
    fun `opening message of a configuration`() {
        assertEquals("/code-review ", openingMessage(Preset("R", prompt = "/code-review")))
        assertEquals("", openingMessage(Preset("P")))
    }

    @Test
    fun `switch replaces an empty or untouched message`() {
        val a = Preset("A", prompt = "/a")
        val b = Preset("B", prompt = "/b")
        assertEquals("/b ", switchedMessage("", a, b))
        assertEquals("/b ", switchedMessage("/a ", a, b))
        assertEquals("", switchedMessage("/a ", a, Preset("C")))
    }

    @Test
    fun `switch keeps edited text`() {
        val a = Preset("A", prompt = "/a")
        assertEquals("/a 7506", switchedMessage("/a 7506", a, Preset("B", prompt = "/b")))
        assertEquals("own words", switchedMessage("own words", a, Preset("B", prompt = "/b")))
    }

    @Test
    fun `project list has no repeats`() {
        assertEquals(
            listOf("E:/a", "E:/b", "E:/c"),
            projectChoices(listOf("E:/a", "E:/b"), listOf("E:\\a", "E:/c", "E:/gone")) { it != "E:/gone" },
        )
    }

    @Test
    fun `the project is a choice like the model`() {
        val own = Preset("P", project = "E:/p")
        val presets = listOf(DEFAULT_PRESET, own)
        assertEquals("E:/q", restore(presets, LastChoice("P", null, null, null, project = "E:/q")).project)
        assertEquals(null, restore(presets, LastChoice("P", null, null, null)).project)
        assertEquals("E:/q", Choice(own, "opus", "low", false, project = "E:/q").toLast().project)
        assertEquals("E:/x", choiceOf(own).copy(project = "E:/x").toPreset(null).project)
        assertEquals("E:/p", choiceOf(own).toPreset(null).project)
    }
}
