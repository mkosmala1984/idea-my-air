package dev.myair.planview

import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.store.PlanManager
import dev.myair.store.PlanManagers
import dev.myair.store.DEFAULT_PROFILE
import dev.myair.store.Profile
import dev.myair.store.ProfileStore
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNull
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlanFilesTest {
    private val cwd = Path.of("E:/dev/a")

    @Test fun `a plan without a manager gets a new session`() =
        assertEquals(PlanManager("n", cwd, cwd) to listOf("--session-id", "n"), managerStart(null, "n", cwd, cwd, DEFAULT_PROFILE) { _, _ -> true })

    @Test fun `a manager with a transcript resumes in its stored directory`() {
        val m = PlanManager("s", Path.of("E:/dev/b"), Path.of("E:/dev/b"))
        assertEquals(m to listOf("--resume", "s"), managerStart(m, "n", cwd, cwd, DEFAULT_PROFILE) { _, _ -> true })
    }

    @Test fun `a manager closed before its first message starts again under its id`() {
        val m = PlanManager("s", cwd, cwd)
        assertEquals(m to listOf("--session-id", "s"), managerStart(m, "n", cwd, cwd, DEFAULT_PROFILE) { _, _ -> false })
    }

    @Test fun `a new manager takes the given profile`() =
        assertEquals(PlanManager("n", cwd, cwd, "work") to listOf("--session-id", "n"), managerStart(null, "n", cwd, cwd, "work") { _, _ -> error("a new manager has no transcript") })

    @Test fun `an existing manager keeps its profile and checks its transcript there`() {
        val m = PlanManager("s", cwd, cwd, "work")
        assertEquals(m to listOf("--resume", "s"), managerStart(m, "n", cwd, cwd, DEFAULT_PROFILE) { id, profile -> id == "s" && profile == "work" })
    }

    @Test fun `a manager of an unlisted profile is refused`(@TempDir dir: Path) {
        val store = ProfileStore(dir.resolve("profiles.json"), dir.resolve(".claude"))
        assertEquals("no profile 'gone'; profiles: [default]", managerProfileRefusal("gone", store))
        assertNull(managerProfileRefusal(DEFAULT_PROFILE, store))
    }

    @Test fun `a profile whose tools cannot be read is refused with the reason rather than throwing`(@TempDir dir: Path) {
        val store = ProfileStore(dir.resolve("profiles.json"), dir.resolve(".claude"))
        val work = dir.resolve("work")
        store.write(listOf(Profile("work", work)))
        dir.resolve(".claude.json").writeText("""{"mcpServers":{"idea":{"type":"http","url":"http://x"}}}""")
        work.createDirectories()
        work.resolve(".claude.json").writeText("""{"mcpServers":{"ide":{"type":"http"}}}""")
        assertEquals("http MCP server ide in ${work.resolve(".claude.json")} has no url", managerProfileRefusal("work", store))
        dir.resolve("profiles.json").writeText("{broken")
        assertTrue(managerProfileRefusal("work", store)!!.isNotBlank())
    }

    @Test fun `a plan tab path is the folder id`() {
        assertEquals("3f2a9c1e-0000-4000-8000-000000000001", parseFolderTabPath("3f2a9c1e-0000-4000-8000-000000000001"))
        for (bad in listOf("", "a/b", "a?b", "a&b")) assertThrows<IllegalArgumentException>(bad) { parseFolderTabPath(bad) }
    }

    private val managers = PlanManagers().set("p", PlanManager("old", cwd, cwd)).set("q", PlanManager("other", cwd, cwd))
    private fun live(job: String? = null) = LiveSession(7, "cli", SessionStatus.IDLE, null, Instant.EPOCH, cwd, Instant.EPOCH, job)
    private fun refusal(session: String = "s", known: Boolean = true, live: LiveSession? = null, runsHere: Boolean = false, starting: Boolean = false, review: String? = null, canvas: String? = null) =
        managerRefusal(managers, "p", session, known, live, runsHere, starting, review, canvas) { "plans/$it" }

    @Test fun `a review's manager is refused naming the review`() =
        assertEquals("session s manages the review 'rtm/Review: x'", refusal(live = live(), runsHere = true, review = "rtm/Review: x"))

    @Test fun `a canvas's manager is refused naming the canvas`() =
        assertEquals("session s manages the canvas 'rtm/Canvas: x'", refusal(live = live(), runsHere = true, canvas = "rtm/Canvas: x"))

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
