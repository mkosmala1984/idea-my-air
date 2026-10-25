package dev.myair

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

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
    ) = findSessions(sessions, structure, now, since, until, query, folder, unfiled, offset, limit).sessions.map { it.id }

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
        val bound = structure.createFolder("rtm", "tf", "testflo", project = Path.of("/testflo").toString())
        fun ids(folder: String? = null, unfiled: Boolean? = null) =
            findSessions(sessions, bound, now, null, null, null, folder, unfiled, null, null).sessions.map { it.id }
        assertEquals(listOf("tf"), ids(folder = "rtm/testflo"))
        assertEquals(listOf("tf", "fix", "old"), ids(folder = "rtm"))
        assertEquals(listOf("new"), ids(unfiled = true))
        val page = findSessions(sessions, bound, now, null, null, "testflo", null, null, null, null)
        assertEquals("rtm/testflo", JsonParser.parseString(page.toJson(bound, zone)).asJsonObject.getAsJsonArray("sessions")[0].asJsonObject["folder"].asString)
        val folder = JsonParser.parseString(bound.treeJson()).asJsonArray[0].asJsonObject.getAsJsonArray("folders")[1].asJsonObject
        assertEquals(Path.of("/testflo").toString(), folder["project"].asString)
    }

    @Test
    fun `pages count every match, default to 50 and refuse more than 100`() {
        val page = findSessions(sessions, structure, now, null, null, null, null, null, 1, 2)
        assertEquals(4, page.total)
        assertEquals(listOf("tf", "fix"), page.sessions.map { it.id })
        assertEquals(emptyList(), ids(offset = 10))
        val many = (1..120).map { session("s$it", "t", "/p", now.minusMinutes(it.toLong())) }
        assertEquals(DEFAULT_LIMIT, findSessions(many, SessionFolders(), now, null, null, null, null, null, null, null).sessions.size)
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
}
