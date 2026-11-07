package dev.myair.mcp

import com.google.gson.JsonParser
import dev.myair.claude.ClaudeSession
import dev.myair.claude.Brief
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.folders.Plan
import dev.myair.folders.PlanStatus
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderQueriesTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone)

    private fun session(id: String, title: String, project: String, at: ZonedDateTime, prompts: List<String> = emptyList()) =
        ClaudeSession(id, Path.of(project), title, at.toInstant(), emptyList(), prompts)

    private val sessions = listOf(
        session("old", "Migration import", "/rtm", now.minusDays(10)),
        session("fix", "Fix NPE in report", "/rtm", now.minusDays(2)),
        session("tf", "TestFLO export", "/testflo", now.minusHours(3)),
        session("new", "Spike: MCP", "/my-air", now.minusMinutes(5)),
    )

    private val structure = SessionFolders()
        .createFolder(null, "rtm", "rtm").createFolder("rtm", "fixes", "fixes")
        .assign("fix", "fixes").assign("old", "rtm")

    private fun ids(
        since: String? = null, until: String? = null, query: String? = null, folder: String? = null, unfiled: Boolean? = null,
        offset: Int? = null, limit: Int? = null,
    ) = findSessions(sessions, structure, now, since, until, query, folder, unfiled, offset, limit) { null }.sessions.map { it.id }

    @Test
    fun `time bounds accept relative, date and date-time forms, inclusive, a bare until covering its day`() {
        assertEquals(listOf("new", "tf", "fix"), ids(since = "7d"))
        assertEquals(listOf("new", "tf"), ids(since = "12h"))
        assertEquals(listOf("tf", "fix", "old"), ids(until = now.minusHours(3).toLocalDateTime().toString()))
        assertEquals(listOf("fix", "old"), ids(until = now.minusDays(2).toLocalDate().toString()))
        assertEquals(listOf("fix"), ids(since = now.minusDays(2).toLocalDate().toString(), until = now.minusDays(1).toLocalDate().toString()))
        listOf("7 days", "2026-13-01", "yesterday", "7w").forEach { bad -> assertThrows<IllegalArgumentException>(bad) { ids(since = bad) } }
    }

    @Test
    fun `query, folder with its subfolders, and filed or unfiled narrow the result`() {
        assertEquals(listOf("tf"), ids(query = "testflo export"))
        assertEquals(listOf("fix", "old"), ids(folder = "RTM"))
        assertEquals(listOf("fix"), ids(folder = "rtm/fixes"))
        assertEquals(listOf("new", "tf"), ids(unfiled = true))
        assertEquals(listOf("fix", "old"), ids(unfiled = false))
        assertThrows<IllegalArgumentException> { ids(folder = "rtm", unfiled = true) }
        assertThrows<IllegalArgumentException> { ids(folder = "nowhere") }
    }

    @Test
    fun `a session in its bound project's folder counts as filed there`() {
        val bound = structure.createFolder("rtm", "tf", "testflo", dirs = listOf(Path.of("/testflo").toString()))
        fun ids(folder: String? = null, unfiled: Boolean? = null) =
            findSessions(sessions, bound, now, null, null, null, folder, unfiled, null, null) { null }.sessions.map { it.id }
        assertEquals(listOf("tf"), ids(folder = "rtm/testflo"))
        assertEquals(listOf("tf", "fix", "old"), ids(folder = "rtm"))
        assertEquals(listOf("new"), ids(unfiled = true))
        val page = findSessions(sessions, bound, now, null, null, "testflo", null, null, null, null) { null }
        assertEquals("rtm/testflo", JsonParser.parseString(page.toJson(bound, zone)).asJsonObject.getAsJsonArray("sessions")[0].asJsonObject["folder"].asString)
        val folder = JsonParser.parseString(bound.treeJson()).asJsonArray[0].asJsonObject.getAsJsonArray("folders")[1].asJsonObject
        assertEquals(Path.of("/testflo").toString(), folder["dirs"].asJsonArray.single().asString)
    }

    @Test
    fun `the tree json names each folder's working directory, null when unset`() {
        val tree = JsonParser.parseString(structure.setWorkdir("fixes", "/work").treeJson()).asJsonArray[0].asJsonObject
        assertTrue(tree["workdir"].isJsonNull)
        assertEquals("/work", tree.getAsJsonArray("folders").map { it.asJsonObject }.single { it["path"].asString.endsWith("fixes") }["workdir"].asString)
    }

    @Test
    fun `pages count every match, default to 50 and refuse more than 100`() {
        val page = findSessions(sessions, structure, now, null, null, null, null, null, 1, 2) { null }
        assertEquals(4, page.total)
        assertEquals(listOf("tf", "fix"), page.sessions.map { it.id })
        assertEquals(emptyList(), ids(offset = 10))
        val many = (1..120).map { session("s$it", "t", "/p", now.minusMinutes(it.toLong())) }
        assertEquals(DEFAULT_LIMIT, findSessions(many, SessionFolders(), now, null, null, null, null, null, null, null) { null }.sessions.size)
        assertThrows<IllegalArgumentException> { ids(limit = 101) }
        assertThrows<IllegalArgumentException> { ids(limit = 0) }
        assertThrows<IllegalArgumentException> { ids(offset = -1) }
    }

    @Test
    fun `the json gives each session its folder path or null, and prompts cut short`() {
        val long = "x".repeat(PROMPT_CHARS + 50)
        val page = SessionPage(1, 0, listOf(session("fix", "Fix", "/rtm", now, listOf(long, "b"))))
        val json = JsonParser.parseString(page.toJson(structure, zone)).asJsonObject
        val entry = json.getAsJsonArray("sessions")[0].asJsonObject
        assertEquals("rtm/fixes", entry["folder"].asString)
        assertEquals(PROMPT_CHARS, entry.getAsJsonArray("prompts")[0].asString.length)
        assertEquals("2026-10-24T12:00+02:00", entry["lastMessage"].asString)
        val unfiled = JsonParser.parseString(SessionPage(1, 0, listOf(session("tf", "T", "/t", now))).toJson(structure, zone)).asJsonObject
        assertEquals(true, unfiled.getAsJsonArray("sessions")[0].asJsonObject["folder"].isJsonNull)
    }

    @Test
    fun `the tree json names folders by path with the sessions filed directly in them`() {
        val tree = JsonParser.parseString(structure.treeJson()).asJsonArray
        val rtm = tree[0].asJsonObject
        assertEquals("rtm", rtm["path"].asString)
        assertEquals(listOf("old"), rtm.getAsJsonArray("sessions").map { it.asString })
        assertEquals("rtm/fixes", rtm.getAsJsonArray("folders")[0].asJsonObject["path"].asString)
    }

    @Test
    fun `the plan json lists milestones and tasks, each task with the sessions naming it and their live status`() {
        val planned = structure.createFolder(null, "p", "plan", dirs = listOf(Path.of("/plan").toString()), plan = Plan("ship"))
            .addMilestone("p", "Export").addTask("M1", "Fields").editTask("T2") { it.copy(status = PlanStatus.IN_PROGRESS, next = "dates") }
        fun briefed(id: String, task: String?) = session(id, id, "/elsewhere", now.minusHours(id.length.toLong())).copy(brief = Brief("g", emptyList(), task))
        val live = mapOf("a" to LiveSession(1, "cli", SessionStatus.WORKING, null, java.time.Instant.EPOCH))
        val sessionList = listOf(briefed("a", "T2"), briefed("bb", "T2"), briefed("c", null))
        val json = JsonParser.parseString(planned.planJson(planned.find("p")!!, sessionList, live, zone)).asJsonObject
        assertEquals(json, JsonParser.parseString(planned.plansJson(listOf(planned.find("p")!!), sessionList, live, zone)).asJsonArray.single())
        assertEquals("plan", json["folder"].asString)
        assertEquals("ship", json["description"].asString)
        val milestone = json.getAsJsonArray("milestones")[0].asJsonObject
        assertEquals(listOf("M1", "open", "0/1"), listOf("id", "status", "progress").map { milestone[it].asString })
        val task = milestone.getAsJsonArray("tasks")[0].asJsonObject
        assertEquals(listOf("T2", "in_progress", "dates"), listOf("id", "status", "next").map { task[it].asString })
        val sessions = task.getAsJsonArray("sessions").map { it.asJsonObject }
        assertEquals(listOf("a" to "working", "bb" to null), sessions.map { it["id"].asString to it["status"].takeUnless { s -> s.isJsonNull }?.asString })
        assertEquals("0/1 tasks done", JsonParser.parseString(planned.treeJson()).asJsonArray[1].asJsonObject["plan"].asString)
    }
}
