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
    fun `a row shows the task count only`() {
        assertEquals("1/3", briefCount(session.brief))
        assertEquals("0/1", briefCount(Brief(null, listOf(BriefTask("x", TaskStatus.PENDING)))))
        assertNull(briefCount(Brief("Goal", emptyList())))
        assertNull(briefCount(null))
    }

    @Test
    fun `the tooltip carries the status, settled, Desktop and project before the brief`() {
        val waiting = LiveSession(1, "cli", SessionStatus.NEEDS_INPUT, "dialog open", Instant.EPOCH)
        assertEquals(
            listOf("Needs input: dialog open", "Settled", "Desktop", "Project: rtm", "Goal: Move AGC config", "✓ export", "▶ map", "○ e2e", "Asked: Migrate AGC"),
            tooltipLines(session.copy(desktop = true), waiting, unread = false, settled = true, project = Path.of("/rtm")),
        )
        assertEquals("Needs input", tooltipLines(session, waiting.copy(waitingFor = null), unread = false, settled = false, project = null).first())
        assertEquals("Working", tooltipLines(session, waiting.copy(status = SessionStatus.WORKING), unread = true, settled = false, project = null).first())
    }

    @Test
    fun `an idle session reads as unread reply or waiting for prompt`() {
        val idle = LiveSession(1, "cli", SessionStatus.IDLE, null, Instant.EPOCH)
        assertEquals("Unread reply", tooltipLines(session, idle, unread = true, settled = false, project = null).first())
        assertEquals("Waiting for prompt", tooltipLines(session, idle, unread = false, settled = false, project = null).first())
        assertEquals(listOf("Asked: Migrate AGC"), tooltipLines(session.copy(brief = null), null, unread = true, settled = false, project = null))
    }
}
