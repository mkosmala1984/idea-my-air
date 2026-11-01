package dev.myair.editor

import com.intellij.mcpserver.annotations.McpTool
import dev.myair.mcp.FoldersMcpToolset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionSystemPromptTest {
    @Test
    fun `the brief is asked for only while briefs are on`() {
        assertTrue("my_air_set_brief" in appendedSystemPrompt(briefs = true))
        assertFalse("my_air_set_brief" in appendedSystemPrompt(briefs = false))
    }

    @Test
    fun `both prompts point at the stored base`() {
        listOf(true, false).forEach { assertTrue("branch.NAME.myAirBase" in appendedSystemPrompt(it)) }
    }

    @Test
    fun `both prompts name every folder tool`() {
        val tools = FoldersMcpToolset::class.java.declaredMethods.filter { it.isAnnotationPresent(McpTool::class.java) }.map { it.name }.toSet()
        assertEquals(setOf("my_air_sessions_find", "my_air_folders_get", "my_air_folders_apply", "my_air_history_list", "my_air_history_restore"), tools)
        listOf(true, false).forEach { briefs -> tools.forEach { assertTrue(it in appendedSystemPrompt(briefs), it) } }
    }

    @Test
    fun `the prompt is one line a Windows command line passes whole`() {
        listOf(true, false).forEach { briefs ->
            assertEquals(emptyList<Char>(), appendedSystemPrompt(briefs).filter { it in "\"<>\r\n" }.toList())
        }
    }
}
