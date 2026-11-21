package dev.myair.actions

import dev.myair.store.Preset
import dev.myair.store.Profile
import dev.myair.store.ProfileStore
import dev.myair.store.Variant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class PresetActionsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a preset's profile no longer listed refuses the start naming it, default and a listed one start`() {
        val store = ProfileStore(tmp.resolve("profiles.json"), tmp.resolve("default"))
        store.write(listOf(Profile("work", tmp.resolve("work"))))
        assertEquals(store.default, presetProfile(Preset("Plain"), store).getOrThrow())
        assertEquals(Profile("work", tmp.resolve("work")), presetProfile(Preset("Work", profile = "work"), store).getOrThrow())
        assertEquals(
            "Profile gone of \"Old\" is no longer listed in My Air's settings.",
            presetProfile(Preset("Old", profile = "gone"), store).exceptionOrNull()!!.message,
        )
    }

    @Test
    fun `a stored profile no longer listed stays among the choices instead of becoming default`() {
        assertEquals(listOf("default", "work"), profileChoices(listOf("default", "work"), "work"))
        assertEquals(listOf("default", "work", "gone"), profileChoices(listOf("default", "work"), "gone"))
    }

    @Test
    fun `a pair reads as the model's name and the effort, a missing part as claude's own setting`() {
        assertEquals("Claude Opus · High", variantLabel(Variant("opus", "high")))
        assertEquals("claude-sonnet-5-5 · claude's own setting", variantLabel(Variant("claude-sonnet-5-5")))
        assertEquals("claude's own setting · Low", variantLabel(Variant(effort = "low")))
    }

    @Test
    fun `the summary names the pairs`() {
        val pick = Preset("Pick", variants = listOf(Variant("opus", "high"), Variant("sonnet", "low")))
        assertEquals("picks from Claude Opus · High, Claude Sonnet · Low; the project's directory", pick.summary())
    }
}
