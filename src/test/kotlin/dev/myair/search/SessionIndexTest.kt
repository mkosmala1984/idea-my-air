package dev.myair.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionIndexTest {
    @TempDir
    lateinit var dir: Path

    private val index by lazy { SessionIndex(dir.resolve("search.db")) }

    private fun user(text: String, flags: String = MAIN) =
        """{$flags"type":"user","message":{"role":"user","content":${quote(text)}},"timestamp":"2026-10-24T10:00:00Z"}"""

    private fun assistant(vararg blocks: String, flags: String = MAIN, model: String = "claude-opus-4") =
        """{$flags"type":"assistant","message":{"model":"$model","role":"assistant","content":[${blocks.joinToString(",")}]}}"""

    private fun text(text: String) = """{"type":"text","text":${quote(text)}}"""

    private fun quote(s: String) = com.google.gson.Gson().toJson(s)

    private fun transcript(name: String, vararg lines: String): Path = dir.resolve("$name.jsonl").apply { writeText(lines.joinToString("") { it + "\n" }) }

    @Test
    fun `finds what the human and the agent said, not tool calls, results, sidechains, meta lines or the harness's blocks`() {
        val file = transcript(
            "s1",
            user("zmień nazwę klasy ClaudeSessionScanner"),
            assistant(text("Zrobione, klasa nazywa się teraz SessionReader."), """{"type":"tool_use","name":"Bash","input":{"command":"grep toolcallmarker"}}"""),
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"toolresultmarker"}]}}""",
            user("<system-reminder>reminderinjected</system-reminder>"),
            user("metaexpansion", flags = """"isSidechain":false,"isMeta":true,"""),
            user("sidechainprompt", flags = SIDE),
            assistant(text("sidechainanswer"), flags = SIDE),
            assistant(text("syntheticerror"), model = "<synthetic>"),
        )
        assertTrue(index.update(listOf(file)))
        assertEquals(setOf(file), index.search("SessionReader").keys)
        assertEquals(setOf(file), index.search("ClaudeSessionScanner").keys)
        for (absent in listOf("toolcallmarker", "toolresultmarker", "reminderinjected", "metaexpansion", "sidechainprompt", "sidechainanswer", "syntheticerror")) {
            assertEquals(emptySet(), index.search(absent).keys, absent)
        }
    }

    @Test
    fun `matches fragments ignoring case and accents, but not l with stroke`() {
        val file = transcript("s1", user("Wyszukiwanie po treści w ClaudeSessionScanner, łączenie"))
        index.update(listOf(file))
        assertEquals(setOf(file), index.search("sessionsc").keys)
        assertEquals(setOf(file), index.search("TRESCI").keys)
        assertEquals(setOf(file), index.search("łączenie").keys)
        assertEquals(emptySet(), index.search("laczenie").keys)
    }

    @Test
    fun `every word must occur in the same message`() {
        val file = transcript("s1", user("alpha is here"), assistant(text("beta is there")), user("alpha and beta together"))
        index.update(listOf(file))
        assertEquals(setOf(file), index.search("alpha beta").keys)
        assertEquals("alpha and beta together", index.search("alpha beta").getValue(file))
        val other = transcript("s2", user("alpha is here"), assistant(text("beta is there")))
        index.update(listOf(file, other))
        assertEquals(setOf(file), index.search("alpha beta").keys)
    }

    @Test
    fun `a word shorter than three characters matches nothing, an empty query is refused`() {
        index.update(listOf(transcript("s1", user("ab cd ef"))))
        assertEquals(emptyMap(), index.search("ab"))
        assertEquals(emptyMap(), index.search("abc ab"))
        assertFailsWith<IllegalArgumentException> { index.search("  ") }
    }

    @Test
    fun `quotes in a query are text, not syntax`() {
        val file = transcript("s1", user("""say "hello" now"""))
        index.update(listOf(file))
        assertEquals(setOf(file), index.search("\"hello\"").keys)
    }

    @Test
    fun `an update reads only what was appended, a second one with nothing new changes nothing`() {
        val file = transcript("s1", user("first marker"))
        assertTrue(index.update(listOf(file)))
        assertFalse(index.update(listOf(file)))
        file.appendText(user("second marker") + "\n")
        assertTrue(index.update(listOf(file)))
        assertEquals(setOf(file), index.search("second").keys)
        // A line still being written is left for the next update; read in full once it ends.
        file.appendText(user("third marker"))
        index.update(listOf(file))
        assertEquals(emptyMap(), index.search("third"))
        file.appendText("\n")
        index.update(listOf(file))
        assertEquals(setOf(file), index.search("third").keys)
        // The earlier messages were not read twice: the best match is one row, so a count by message shows it.
        assertEquals(1, rows("first marker"))
    }

    @Test
    fun `a file rewritten shorter is read again, a file no longer listed is forgotten`() {
        val file = transcript("s1", user("long original text with oldmarker and plenty more words to make it long"))
        val other = transcript("s2", user("othermarker"))
        index.update(listOf(file, other))
        file.writeText(user("newmarker") + "\n")
        assertTrue(index.update(listOf(file, other)))
        assertEquals(emptySet(), index.search("oldmarker").keys)
        assertEquals(setOf(file), index.search("newmarker").keys)
        assertTrue(index.update(listOf(file)))
        assertEquals(emptySet(), index.search("othermarker").keys)
        assertEquals(setOf(file), index.search("newmarker").keys)
    }

    @Test
    fun `the index outlives its object, and another format version starts it over`() {
        val file = transcript("s1", user("persisted marker"))
        index.update(listOf(file))
        assertEquals(setOf(file), SessionIndex(dir.resolve("search.db")).search("persisted").keys)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("search.db")}").use { it.createStatement().execute("PRAGMA user_version = 0") }
        val fresh = SessionIndex(dir.resolve("search.db"))
        assertEquals(emptyMap(), fresh.search("persisted"))
        fresh.update(listOf(file))
        assertEquals(setOf(file), fresh.search("persisted").keys)
    }

    @Test
    fun `one hit per transcript, its snippet on one line`() {
        val many = transcript("many", user("needle"), user("needle needle needle"), user("line one\nneedle\nline three"))
        val one = transcript("one", user("a single needle among a lot of other words that are not the point at all"))
        index.update(listOf(one, many))
        val hits = index.search("needle")
        assertEquals(setOf(one, many), hits.keys)
        assertFalse(hits.values.any { '\n' in it })
    }

    private companion object {
        const val MAIN = """"isSidechain":false,"""
        const val SIDE = """"isSidechain":true,"""
    }

    private fun rows(text: String): Int = java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("search.db")}").use { c ->
        c.createStatement().executeQuery("SELECT count(*) FROM msg WHERE text = '$text'").use { it.next(); it.getInt(1) }
    }
}
