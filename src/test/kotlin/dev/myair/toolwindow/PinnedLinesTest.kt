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

class PinnedLinesTest {
    private val tasks = listOf(
        BriefTask("a", TaskStatus.COMPLETED), BriefTask("b", TaskStatus.COMPLETED), BriefTask("c", TaskStatus.IN_PROGRESS),
        BriefTask("d", TaskStatus.PENDING), BriefTask("e", TaskStatus.PENDING),
    )
    private val project = Path.of("/rtm-cloud")
    private val session = ClaudeSession(
        "s1", Path.of("/rtm-cloud/.claude/worktrees/devconsole"), "Title", Instant.EPOCH, emptyList(), emptyList(), Brief("Goal", tasks), project = project,
    )

    private fun live(status: SessionStatus, waitingFor: String? = null) = LiveSession(1, "cli", status, waitingFor, Instant.EPOCH, Path.of("/p"), Instant.EPOCH)

    @Test
    fun `a session needing input shows what it waits for, else says it needs input`() {
        assertEquals(SecondLine.NeedsInput("permission"), secondLine(session, live(SessionStatus.NEEDS_INPUT, "permission"), "main"))
        assertEquals(SecondLine.NeedsInput("Needs input"), secondLine(session, live(SessionStatus.NEEDS_INPUT), "main"))
    }

    @Test
    fun `a working session shows its project, not its worktree, the branch, the count and the progress`() {
        assertEquals(SecondLine.Place(project, "worktree-devconsole", "2/5", 0.4), secondLine(session, live(SessionStatus.WORKING), "worktree-devconsole"))
    }

    @Test
    fun `an idle session or one without a process shows no progress`() {
        val place = SecondLine.Place(project, "main", "2/5", null)
        assertEquals(place, secondLine(session, live(SessionStatus.IDLE), "main"))
        assertEquals(place, secondLine(session, null, "main"))
    }

    @Test
    fun `without tasks or a branch the parts are left out, with no separator behind`() {
        val bare = secondLine(session.copy(brief = Brief("Goal", emptyList())), live(SessionStatus.WORKING), null)
        assertEquals(SecondLine.Place(project, null, null, null), bare)
        assertEquals(emptyList(), (bare as SecondLine.Place).rest)
        assertEquals(listOf("2/5"), (secondLine(session.copy(brief = Brief("Goal", tasks)), null, null) as SecondLine.Place).rest)
        assertEquals(SecondLine.Place(project, "main", null, null), secondLine(session.copy(brief = null), live(SessionStatus.WORKING), "main"))
    }

    @Test
    fun `until dragged the list is as high as its rows, up to its share of the panel`() {
        assertEquals(90, pinnedHeight(content = 90, row = 45, dragged = null, share = 400, panel = 1000, treeMin = 60))
        assertEquals(400, pinnedHeight(content = 450, row = 45, dragged = null, share = 400, panel = 1000, treeMin = 60))
    }

    @Test
    fun `a dragged list is as high as dragged, above or below its rows`() {
        assertEquals(300, pinnedHeight(content = 90, row = 45, dragged = 300, share = 400, panel = 1000, treeMin = 60))
        assertEquals(135, pinnedHeight(content = 450, row = 45, dragged = 135, share = 400, panel = 1000, treeMin = 60))
    }

    @Test
    fun `a dragged height keeps one row and leaves the tree its least`() {
        assertEquals(45, pinnedHeight(content = 450, row = 45, dragged = 10, share = 400, panel = 1000, treeMin = 60))
        assertEquals(940, pinnedHeight(content = 2000, row = 45, dragged = 5000, share = 400, panel = 1000, treeMin = 60))
        assertEquals(45, pinnedHeight(content = 450, row = 45, dragged = 300, share = 400, panel = 80, treeMin = 60))
    }
}
