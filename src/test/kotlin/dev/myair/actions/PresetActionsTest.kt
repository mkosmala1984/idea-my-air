package dev.myair.actions

import dev.myair.store.Preset
import dev.myair.store.Variant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PresetActionsTest {
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
