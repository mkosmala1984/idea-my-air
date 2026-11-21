package dev.myair.store

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
    fun `a missing file means only default`() {
        assertEquals(listOf("default"), ProfileStore(tmp.resolve("none.json"), d).all().map { it.name })
    }

    @Test
    fun `rejects bad files`() {
        val bad = listOf(
            json(entry(" ", p1)),
            json(entry("default", p1)),
            json(entry("a", p1), entry("a", tmp.resolve("p2").toAbsolutePath())),
            json("""{"name":"a","dir":"rel/x"}"""),
            json(entry("a", p1), entry("b", p1)),
            json(entry("a", d)),
            json("""{"name":"a","dir":"${p1.toString().replace("\\", "\\\\")}","colour":"red"}"""),
            json(entry("a", p1), version = 2),
        )
        bad.forEach { text -> assertThrows<IllegalArgumentException>(text) { parseProfiles(text, d) } }
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
}
