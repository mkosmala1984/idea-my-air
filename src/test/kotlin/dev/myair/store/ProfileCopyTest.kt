package dev.myair.store

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileCopyTest {
    @TempDir
    lateinit var tmp: Path

    private val source by lazy { tmp.resolve("default").createDirectories() }
    private val target by lazy { tmp.resolve("new") }
    private val settingsItems = CopyItem.entries.filter { it.source is CopySource.SettingsKey }

    private fun settings(text: String) = source.resolve("settings.json").writeText(text)

    private fun file(rel: String, text: String = rel) = source.resolve(rel).also { it.parent.createDirectories(); it.writeText(text) }

    private fun listing(dir: Path): List<String> =
        if (!dir.exists()) emptyList() else Files.walk(dir).use { s -> s.map { it.relativeTo(dir).toString() }.sorted().toList() }

    private fun unavailable(map: Map<CopyItem, Availability>) = map.filterValues { it is Availability.Unavailable }

    private var registered = 0
    private fun copy(selection: Set<CopyItem>, register: () -> Unit = { registered++ }) = copyFromDefault(source, target, selection, register)

    @Test
    fun `a missing settings json makes the five settings items unavailable without error`() {
        file("CLAUDE.md"); file("skills/a/SKILL.md"); file("agents/a.md"); file("commands/c.md")
        val map = copyAvailability(source)
        assertEquals(settingsItems.toSet(), unavailable(map).keys)
        settingsItems.forEach { assertFalse((map[it] as Availability.Unavailable).error) }
    }

    @Test
    fun `a malformed settings json makes the settings items unavailable with an error naming the file`() {
        file("CLAUDE.md")
        for (text in listOf("{", "[1, 2]")) {
            settings(text)
            val map = copyAvailability(source)
            settingsItems.forEach {
                val u = map[it] as Availability.Unavailable
                assertTrue(u.error)
                assertTrue("settings.json" in u.reason, u.reason)
            }
            assertEquals(Availability.Available, map[CopyItem.CLAUDE_MD])
        }
    }

    @Test
    fun `a broken custom theme makes only the theme unavailable with an error`() {
        for (theme in listOf("custom:missing", "custom:../x", "custom:a/b", "custom:a\\\\b")) {
            settings("""{"theme":"$theme","model":"opus"}""")
            val map = copyAvailability(source)
            assertTrue((map[CopyItem.THEME] as Availability.Unavailable).error, theme)
            assertEquals(Availability.Available, map[CopyItem.MODEL])
        }
    }

    @Test
    fun `absent keys and files are unavailable without error`() {
        settings("""{"model":"opus"}""")
        val map = copyAvailability(source)
        assertEquals(Availability.Available, map[CopyItem.MODEL])
        (CopyItem.entries - CopyItem.MODEL).forEach { assertFalse((map[it] as Availability.Unavailable).error, it.name) }
    }

    @Test
    fun `copyAvailability writes nothing`() {
        settings("""{"theme":"custom:t","model":"opus"}"""); file("themes/t.json"); file("skills/a/SKILL.md")
        val before = listing(source)
        copyAvailability(source)
        assertEquals(before, listing(source))
    }

    @Test
    fun `selected keys are copied in source order and no other`() {
        settings("""{"model":"opus","env":{"T":"secret"},"theme":"dark","hooks":{},"statusLine":{"type":"command","command":"x"},"language":"pl"}""")
        copy(setOf(CopyItem.STATUS_LINE, CopyItem.THEME, CopyItem.MODEL))
        val copied = com.google.gson.JsonParser.parseString(target.resolve("settings.json").readText()).asJsonObject
        assertEquals(listOf("model", "theme", "statusLine"), copied.keySet().toList())
        assertEquals(1, registered)
        assertEquals(listOf("", "settings.json"), listing(target))
    }

    @Test
    fun `a status line command is written unchanged`() {
        val command = """bash -c 'a=1 && b<c > d' C:\Users\x\line.sh"""
        val statusLine = com.google.gson.JsonObject().apply { addProperty("type", "command"); addProperty("command", command) }
        settings(com.google.gson.Gson().toJson(com.google.gson.JsonObject().apply { add("statusLine", statusLine) }))
        copy(setOf(CopyItem.STATUS_LINE))
        val text = target.resolve("settings.json").readText()
        listOf("&&", "<", ">", "=", "'").forEach { assertTrue(it in text, "$it in $text") }
        assertEquals(command, com.google.gson.JsonParser.parseString(text).asJsonObject["statusLine"].asJsonObject["command"].asString)
    }

    @Test
    fun `a custom theme copies its file and a built-in theme only the key`() {
        settings("""{"theme":"custom:mine"}"""); file("themes/mine.json", "{}"); file("themes/other.json")
        copy(setOf(CopyItem.THEME))
        assertEquals(listOf("", "settings.json", "themes", "themes\\mine.json".replace("\\", tmp.fileSystem.separator)), listing(target))

        Files.walk(target).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        settings("""{"theme":"dark"}""")
        copy(setOf(CopyItem.THEME))
        assertEquals(listOf("", "settings.json"), listing(target))
    }

    @Test
    fun `a nested skills tree and CLAUDE md are copied with their structure`() {
        file("skills/a/SKILL.md"); file("skills/a/refs/x.md"); file("skills/b/SKILL.md"); file("CLAUDE.md", "hello")
        source.resolve("skills/empty").createDirectories()
        copy(setOf(CopyItem.SKILLS, CopyItem.CLAUDE_MD))
        assertEquals(listing(source.resolve("skills")), listing(target.resolve("skills")))
        assertEquals("hello", target.resolve("CLAUDE.md").readText())
        assertEquals("skills/a/refs/x.md", target.resolve("skills/a/refs/x.md").readText())
    }

    @Test
    fun `an empty selection only registers, even into a directory with content`() {
        target.createDirectories(); target.resolve("keep.txt").writeText("x")
        copy(emptySet())
        assertEquals(1, registered)
        assertEquals(listOf("", "keep.txt"), listing(target))
    }

    @Test
    fun `a target with content is refused before anything is written`() {
        file("CLAUDE.md")
        target.createDirectories(); target.resolve("keep.txt").writeText("x")
        val e = assertThrows(ProfileCopyException::class.java) { copy(setOf(CopyItem.CLAUDE_MD)) }
        assertTrue(target.toString() in e.message!!, e.message)
        assertEquals(0, registered)
        assertEquals(listOf("", "keep.txt"), listing(target))
    }

    @Test
    fun `an empty target is filled and an absent one is created`() {
        file("CLAUDE.md")
        target.createDirectories()
        copy(setOf(CopyItem.CLAUDE_MD))
        assertTrue(target.resolve("CLAUDE.md").exists())

        val absent = tmp.resolve("absent")
        copyFromDefault(source, absent, setOf(CopyItem.CLAUDE_MD)) {}
        assertTrue(absent.resolve("CLAUDE.md").exists())
    }

    @Test
    fun `an unavailable selected item is refused before anything is written`() {
        val e = assertThrows(ProfileCopyException::class.java) { copy(setOf(CopyItem.CLAUDE_MD)) }
        assertTrue("CLAUDE.md" in e.message!!, e.message)
        assertFalse(target.exists())
        assertEquals(0, registered)
    }

    @Test
    fun `a symbolic link in skills fails the copy and leaves no target`() {
        file("skills/a/SKILL.md")
        val link = source.resolve("skills/link")
        assumeTrue(runCatching { Files.createSymbolicLink(link, source.resolve("skills/a")) }.isSuccess, "cannot create symbolic links here")
        val e = assertThrows(ProfileCopyException::class.java) { copy(setOf(CopyItem.SKILLS)) }
        assertTrue(link.toString() in e.message!!, e.message)
        assertFalse(target.exists())
        assertEquals(0, registered)
    }

    @Test
    fun `a junction in skills fails the copy and leaves an empty target empty`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "junctions are Windows only")
        file("skills/a/SKILL.md")
        val junction = source.resolve("skills/junction")
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), source.resolve("skills/a").toString())
            .redirectErrorStream(true).start().let { it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0 }
        assumeTrue(made, "cannot create a junction here")
        try {
            target.createDirectories()
            val e = assertThrows(ProfileCopyException::class.java) { copy(setOf(CopyItem.SKILLS)) }
            assertTrue(junction.toString() in e.message!!, e.message)
            assertTrue(target.exists())
            assertEquals(emptyList<Path>(), target.listDirectoryEntries())
            assertEquals(0, registered)
        } finally {
            Files.delete(junction)
        }
    }

    @Test
    fun `a failing registration rolls back an absent and an empty target`() {
        file("CLAUDE.md"); file("skills/a/SKILL.md")
        val e = assertThrows(ProfileCopyException::class.java) { copy(setOf(CopyItem.CLAUDE_MD, CopyItem.SKILLS)) { error("profiles.json broken") } }
        assertTrue("Registration" in e.message!! && "profiles.json broken" in e.message!!, e.message)
        assertFalse(target.exists())

        target.createDirectories()
        assertThrows(ProfileCopyException::class.java) { copy(setOf(CopyItem.CLAUDE_MD, CopyItem.SKILLS)) { error("again") } }
        assertTrue(target.exists())
        assertEquals(emptyList<Path>(), target.listDirectoryEntries())
    }

    @Test
    fun `a deletion that fails during rollback is named in the message`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "an open file blocks deletion only on Windows")
        file("CLAUDE.md")
        var held: SeekableByteChannel? = null
        try {
            val e = assertThrows(ProfileCopyException::class.java) {
                copy(setOf(CopyItem.CLAUDE_MD)) { held = Files.newByteChannel(target.resolve("CLAUDE.md"), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE); error("boom") }
            }
            assertTrue(target.resolve("CLAUDE.md").toString() in e.message!!, e.message)
        } finally {
            held?.close()
        }
    }
}
