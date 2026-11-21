package dev.myair.claude

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeSessionTest {
    private fun session(profile: String? = null) = ClaudeSession("s", Path.of("/p"), "Title", Instant.EPOCH, emptyList(), emptyList())
        .let { if (profile == null) it else it.copy(profile = profile) }

    @Test
    fun `a session is found by its non-default profile name and a default one is not found by default`() {
        assertTrue(session("work").matches("work", null))
        assertFalse(session().matches("default", null))
    }
}
