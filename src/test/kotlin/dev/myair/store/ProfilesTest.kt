package dev.myair.store

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilesTest {
    @TempDir
    lateinit var tmp: Path

    private val d: Path get() = tmp.resolve("default").toAbsolutePath()
    private val p1: Path get() = tmp.resolve("p1").toAbsolutePath()

    private fun json(vararg profiles: String, version: Int = 1) = """{"version":$version,"profiles":[${profiles.joinToString(",")}]}"""
    private fun entry(name: String, dir: Path) = """{"name":"$name","dir":${com.google.gson.Gson().toJson(dir.toString())}}"""

    @Test
    fun `the file's profiles follow default`() {
        val store = ProfileStore(tmp.resolve("profiles.json"), d)
        store.write(listOf(Profile("test", p1)))
        assertEquals(listOf("default", "test"), store.all().map { it.name })
        assertEquals(d, store.all().first().dir)
    }

    @Test
    fun `default does not read the file`() {
        val file = tmp.resolve("broken.json").also { it.writeText("{not json") }
        assertEquals(Profile(DEFAULT_PROFILE, d), ProfileStore(file, d).default)
    }

    @Test
    fun `a missing file means only default`() {
        assertEquals(listOf("default"), ProfileStore(tmp.resolve("none.json"), d).all().map { it.name })
    }

    @Test
    fun `rejects bad files`() {
        val bad = listOf(
            json(entry(" ", p1)),
            json(entry("default", p1)),
            json(entry("Default", p1)),
            json(entry("a", p1), entry("a", tmp.resolve("p2").toAbsolutePath())),
            json("""{"name":"a","dir":"rel/x"}"""),
            json(entry("a", p1), entry("b", p1)),
            json(entry("a", d)),
            json(entry("a", tmp.resolve("it's").toAbsolutePath())),
            json("""{"name":"a","dir":"${p1.toString().replace("\\", "\\\\")}","colour":"red"}"""),
            json(entry("a", p1), version = 2),
        )
        bad.forEach { text -> assertThrows<IllegalArgumentException>(text) { parseProfiles(text, d) } }
    }

    @Test
    fun `find answers default without reading the file and null for an unlisted name`() {
        val broken = ProfileStore(tmp.resolve("broken.json").also { it.writeText("{not json") }, d)
        assertEquals(Profile(DEFAULT_PROFILE, d), broken.find(DEFAULT_PROFILE))
        val store = ProfileStore(tmp.resolve("profiles.json"), d)
        store.write(listOf(Profile("test", p1)))
        assertEquals(Profile("test", p1), store.find("test"))
        assertEquals(null, store.find("gone"))
    }

    @Test
    fun `json round-trips`() {
        val list = listOf(Profile("a", p1), Profile("b", tmp.resolve("p2").toAbsolutePath()))
        assertEquals(list, parseProfiles(profilesJson(list), d))
    }

    @Test
    fun `byName of an unlisted profile names the known ones`() {
        val message = assertThrows<IllegalArgumentException> { ProfileStore(tmp.resolve("none.json"), d).byName("x") }.message!!
        assertTrue("no profile 'x'" in message && "default" in message, message)
    }

    @Test
    fun `default profile env clears CLAUDE_CONFIG_DIR`() {
        assertEquals(mapOf("CLAUDE_CONFIG_DIR" to null), profileEnv(Profile(DEFAULT_PROFILE, d)))
        assertEquals(mapOf("CLAUDE_CONFIG_DIR" to p1.toString()), profileEnv(Profile("p", p1)))
    }

    @Test
    fun `an added env sets a profile's dir and refuses default while the IDE has CLAUDE_CONFIG_DIR`() {
        assertEquals(mapOf("CLAUDE_CONFIG_DIR" to p1.toString()), addedProfileEnv(Profile("p", p1), inherited = "E:/other"))
        assertEquals(emptyMap(), addedProfileEnv(Profile(DEFAULT_PROFILE, d), inherited = null))
        val e = assertThrows<IllegalStateException> { addedProfileEnv(Profile(DEFAULT_PROFILE, d), inherited = "E:/other") }
        assertEquals("The IDE runs with CLAUDE_CONFIG_DIR=E:/other; My Air's default profile is ~/.claude", e.message)
    }

    @Test
    fun `a whole process env drops an inherited CLAUDE_CONFIG_DIR for default and sets a profile's dir`() {
        val ide = mapOf("PATH" to "C:/bin", "CLAUDE_CONFIG_DIR" to "E:/other")
        assertEquals(mapOf("PATH" to "C:/bin"), processProfileEnv(Profile(DEFAULT_PROFILE, d), ide))
        assertEquals(mapOf("PATH" to "C:/bin", "CLAUDE_CONFIG_DIR" to p1.toString()), processProfileEnv(Profile("p", p1), ide))
    }
}
