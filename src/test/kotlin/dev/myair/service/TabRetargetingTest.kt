package dev.myair.service

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TabRetargetingTest {
    private fun live(pid: Long) = LiveSession(pid, "cli", SessionStatus.IDLE, null)

    private fun retarget(pid: Long?, live: Map<String, LiveSession>, titles: Map<String, String> = emptyMap()) =
        retarget("old", "Old title", pid, live, titles)

    @Test
    fun `a tab whose process is unknown or unregistered keeps its session`() {
        assertEquals("old" to "Old title", retarget(null, mapOf("other" to live(7))))
        assertEquals("old" to "Old title", retarget(42, mapOf("other" to live(7))))
    }

    @Test
    fun `a tab follows its process to the session the registry reports for it`() {
        assertEquals("new" to "Cleared", retarget(42, mapOf("new" to live(42), "other" to live(7)), mapOf("new" to "Cleared")))
    }

    @Test
    fun `a switched-to session without a transcript yet is a new session`() {
        assertEquals("new" to NEW_SESSION_TITLE, retarget(42, mapOf("new" to live(42))))
    }

    @Test
    fun `a tab picks up a title its session gained later`() {
        assertEquals("old" to "Named", retarget(42, mapOf("old" to live(42)), mapOf("old" to "Named")))
    }
}
