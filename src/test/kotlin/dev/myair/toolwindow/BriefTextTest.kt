package dev.myair.toolwindow

import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BriefTextTest {
    private val tasks = listOf(BriefTask("export", TaskStatus.COMPLETED), BriefTask("map", TaskStatus.IN_PROGRESS), BriefTask("e2e", TaskStatus.PENDING))
    private val session = ClaudeSession("s1", Path.of("/rtm"), "Title", Instant.EPOCH, emptyList(), listOf("Migrate AGC\nwith details"), Brief("Move AGC config", tasks))

    @Test
    fun `the row shows progress and the task in progress, progress alone without one, nothing without tasks`() {
        assertEquals("1/3 · map", briefLabel(session.brief))
        assertEquals("0/1", briefLabel(Brief(null, listOf(BriefTask("x", TaskStatus.PENDING)))))
        assertNull(briefLabel(Brief("Goal", emptyList())))
        assertNull(briefLabel(null))
    }

    @Test
    fun `the tooltip shows what the session waits for, its goal, its tasks and the first prompt`() {
        val waiting = LiveSession(1, "cli", SessionStatus.NEEDS_INPUT, "dialog open", Instant.EPOCH)
        assertEquals(
            listOf("Waiting for: dialog open", "Goal: Move AGC config", "✓ export", "▶ map", "○ e2e", "Asked: Migrate AGC"),
            tooltipLines(session, waiting),
        )
        assertEquals(listOf("Asked: Migrate AGC"), tooltipLines(session.copy(brief = null), null))
    }
}
