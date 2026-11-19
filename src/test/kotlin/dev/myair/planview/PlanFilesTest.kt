package dev.myair.planview

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.store.PlanManager
import dev.myair.store.PlanManagers
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNull
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlanFilesTest {
    private val cwd = Path.of("E:/dev/a")

    @Test fun `a plan without a manager gets a new session`() =
        assertEquals(PlanManager("n", cwd, cwd) to listOf("--session-id", "n"), managerStart(null, "n", cwd, cwd) { true })

    @Test fun `a manager with a transcript resumes in its stored directory`() {
        val m = PlanManager("s", Path.of("E:/dev/b"), Path.of("E:/dev/b"))
        assertEquals(m to listOf("--resume", "s"), managerStart(m, "n", cwd, cwd) { true })
    }

    @Test fun `a manager closed before its first message starts again under its id`() {
        val m = PlanManager("s", cwd, cwd)
        assertEquals(m to listOf("--session-id", "s"), managerStart(m, "n", cwd, cwd) { false })
    }

    @Test fun `a plan tab path is the folder id`() {
        assertEquals("3f2a9c1e-0000-4000-8000-000000000001", parseFolderTabPath("3f2a9c1e-0000-4000-8000-000000000001"))
        for (bad in listOf("", "a/b", "a?b", "a&b")) assertThrows<IllegalArgumentException>(bad) { parseFolderTabPath(bad) }
    }

    private val managers = PlanManagers().set("p", PlanManager("old", cwd, cwd)).set("q", PlanManager("other", cwd, cwd))
    private fun live(job: String? = null) = LiveSession(7, "cli", SessionStatus.IDLE, null, Instant.EPOCH, cwd, Instant.EPOCH, job)
    private fun refusal(session: String = "s", known: Boolean = true, live: LiveSession? = null, runsHere: Boolean = false, starting: Boolean = false, review: String? = null) =
        managerRefusal(managers, "p", session, known, live, runsHere, starting, review) { "plans/$it" }

    @Test fun `a review's manager is refused naming the review`() =
        assertEquals("session s manages the review 'rtm/Review: x'", refusal(live = live(), runsHere = true, review = "rtm/Review: x"))

    @Test fun `a known session not running anywhere may manage a plan`() = assertNull(refusal())

    @Test fun `a session running in this IDE and started may manage a plan`() = assertNull(refusal(live = live(), runsHere = true))

    @Test fun `the plan's own manager is refused`() = assertEquals("session old manages this plan already", refusal("old"))

    @Test fun `the manager of another plan is refused, naming that plan`() = assertEquals("session other manages the plan 'plans/q'", refusal("other"))

    @Test fun `an unknown session is refused`() = assertEquals("no session s", refusal(known = false))

    @Test fun `a background job is refused even when a view of it runs here`() =
        assertEquals("session s is run by the background job j", refusal(live = live("j"), runsHere = true))

    @Test fun `a session running outside this IDE is refused`() =
        assertEquals("session s runs outside this IDE (pid 7); it must end there first", refusal(live = live()))

    @Test fun `a process here still starting is refused`() =
        assertEquals("session s is still starting; call again in a few seconds", refusal(live = live(), runsHere = true, starting = true))
}
