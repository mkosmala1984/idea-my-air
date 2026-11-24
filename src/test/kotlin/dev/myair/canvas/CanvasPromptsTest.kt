package dev.myair.canvas

import com.intellij.mcpserver.annotations.McpTool
import dev.myair.mcp.CanvasMcpToolset
import dev.myair.store.Canvas
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasPromptsTest {
    private val tools = CanvasMcpToolset::class.java.declaredMethods.filter { it.isAnnotationPresent(McpTool::class.java) }.map { it.name }.toSet()
    private val prompt = canvasManagerPrompt("f1", Canvas(null, 1, emptyList()))

    @Test
    fun `the manager prompt names every canvas tool and the folder, and holds the OpenUI prompt`() {
        assertEquals(setOf("my_air_canvas_show", "my_air_canvas_patch", "my_air_canvas_get", "my_air_canvas_apply", "my_air_canvas_language"), tools)
        tools.forEach { assertContains(prompt, it) }
        assertContains(prompt, "folder 'f1'")
        assertContains(prompt, "[canvas <folder> · <frame>]")
        assertTrue(openUiPrompt.length > 30_000)
        assertTrue(prompt.endsWith(openUiPrompt))
        // trimMargin took every margin off My Air's part.
        assertFalse(prompt.removeSuffix(openUiPrompt).lines().any { it.startsWith("|") })
    }

    @Test
    fun `the language tool gives the rules and the OpenUI prompt`() {
        val language = canvasLanguage()
        assertTrue(language.startsWith(canvasRules()))
        assertTrue(language.endsWith(openUiPrompt))
        tools.forEach { assertContains(canvasRules(), it) }
        assertContains(CANVAS_MANAGER_FIRST_MESSAGE, "my_air_canvas_get")
    }
}
