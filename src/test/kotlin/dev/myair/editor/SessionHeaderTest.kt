package dev.myair.editor

import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.TaskStatus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class SessionHeaderTest {
    private val tasks = listOf(BriefTask("a", TaskStatus.COMPLETED), BriefTask("b", TaskStatus.IN_PROGRESS), BriefTask("c", TaskStatus.PENDING))
    private val project = Path.of("/my-air")

    private fun session(brief: Brief?) =
        ClaudeSession("s1", Path.of("/my-air/.claude/worktrees/x"), "Title", Instant.EPOCH, emptyList(), emptyList(), brief, project = project)

    @Test
    fun `the header names the title, the project, the branch, the goal and the task count`() {
        assertEquals(HeaderText("Title", project, "main", "Goal", "1/3"), headerText(session(Brief("Goal", tasks)), "main"))
    }

    @Test
    fun `a plan without a goal says its task in progress`() {
        assertEquals(HeaderText("Title", project, null, "b", "1/3"), headerText(session(Brief(null, tasks)), null))
    }

    @Test
    fun `a session without a brief says only where it works`() {
        assertEquals(HeaderText("Title", project, "main", null, null), headerText(session(null), "main"))
    }
}
