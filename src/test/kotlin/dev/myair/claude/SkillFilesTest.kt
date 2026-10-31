package dev.myair.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillFilesTest {
    @TempDir
    lateinit var dir: Path

    private val home by lazy { dir.resolve(".claude") }
    private val cwd by lazy { dir.resolve("project") }

    private fun write(path: Path, text: String = "---\nname: x\ndescription: Does x\n---\nbody") =
        path.apply { parent.createDirectories(); writeText(text) }

    @Test
    fun `a user skill is its directory, with its description and every file below it`() {
        write(home.resolve("skills/tdd/SKILL.md"), "---\nname: tdd\ndescription: |\n  Test first.\n  Always.\n---\n")
        write(home.resolve("skills/tdd/references/cycle.md"), "")
        val files = skillFiles("tdd", home, cwd)!!
        assertEquals(home.resolve("skills/tdd"), files.definition)
        assertEquals("Test first.\nAlways.", files.description.getOrThrow())
        assertEquals(listOf("references", "references/cycle.md", "SKILL.md").map { home.resolve("skills/tdd").resolve(it) }, files.files)
    }

    @Test
    fun `a project skill and a command have no plugin, a command is one file`() {
        write(cwd.resolve(".claude/skills/deploy/SKILL.md"))
        write(home.resolve("commands/review.md"), "Review the diff.")
        assertEquals(cwd.resolve(".claude/skills/deploy"), skillFiles("deploy", home, cwd)!!.definition)
        val command = skillFiles("review", home, cwd)!!
        assertEquals(home.resolve("commands/review.md") to emptyList(), command.definition to command.files)
        assertNull(command.description.getOrThrow())
    }

    @Test
    fun `a plugin skill comes from its install path, its folders, a synced plugin, synced skills or a folder of commands`() {
        val install = dir.resolve("cache/mattpocock-skills/1.3.1")
        write(home.resolve("plugins/installed_plugins.json"),
            """{"version":2,"plugins":{"mattpocock-skills@official":[{"scope":"user","installPath":"${install.toString().replace("\\", "\\\\")}"}]}}""")
        write(install.resolve("skills/engineering/tdd/SKILL.md"))
        write(home.resolve("plugins/synced/account/data~g2/skills/analyze/SKILL.md"))
        write(home.resolve("skills/synced/account/docx/SKILL.md"))
        write(home.resolve("commands/opsx/apply.md"))
        assertEquals(install.resolve("skills/engineering/tdd"), skillFiles("mattpocock-skills:tdd", home, cwd)!!.definition)
        assertEquals(home.resolve("plugins/synced/account/data~g2/skills/analyze"), skillFiles("data:analyze", home, cwd)!!.definition)
        assertEquals(home.resolve("skills/synced/account/docx"), skillFiles("anthropic-skills:docx", home, cwd)!!.definition)
        assertEquals(home.resolve("commands/opsx/apply.md"), skillFiles("opsx:apply", home, cwd)!!.definition)
    }

    private fun escaped(path: Path) = path.toString().replace("\\", "\\\\")

    /** A marketplace `market` cloned in the plugins folder, listing [plugins] (JSON objects), refreshed at noon. */
    private fun marketplace(vararg plugins: String): Path {
        val location = home.resolve("plugins/marketplaces/market")
        write(home.resolve("plugins/known_marketplaces.json"),
            """{"market":{"source":{"source":"github","repo":"owner/market"},"installLocation":"${escaped(location)}","lastUpdated":"2026-10-18T12:00:00.000Z"}}""")
        write(location.resolve(".claude-plugin/marketplace.json"), """{"name":"market","plugins":[${plugins.joinToString(",")}]}""")
        return location
    }

    private fun installed(plugin: String, version: String, commit: String): Path {
        val install = home.resolve("plugins/cache/market/$plugin/$version")
        write(home.resolve("plugins/installed_plugins.json"),
            """{"version":2,"plugins":{"$plugin@market":[{"scope":"user","installPath":"${escaped(install)}","version":"$version","gitCommitSha":"$commit"}]}}""")
        return install
    }

    @Test
    fun `a plugin from the marketplace's repository links its folder there at the installed commit, and has an update when the marketplace lists a newer version`() {
        marketplace("""{"name":"pstack","version":"1.16.0","source":"./plugins/pstack"}""")
        write(installed("pstack", "1.15.0", "9368993ea3d9").resolve("skills/how/SKILL.md"))
        val origin = skillFiles("pstack:how", home, cwd)!!.origin!!
        assertEquals(SkillOrigin(
            "pstack@market", "https://github.com/owner/market/tree/9368993ea3d9/plugins/pstack/skills/how", "1.15.0", "1.16.0", Instant.parse("2026-10-18T12:00:00Z"),
        ), origin)
    }

    @Test
    fun `a plugin with a repository of its own compares commits, one without a listed version the marketplace's plugin json`() {
        val location = marketplace(
            """{"name":"superpowers","source":{"source":"url","url":"https://github.com/obra/superpowers.git","sha":"bb92a77741419a4a"}}""",
            """{"name":"md","source":"./plugins/md"}""",
        )
        write(installed("superpowers", "7.0.0", "bb92a77741419a4a").resolve("skills/tdd/SKILL.md"))
        val superpowers = skillFiles("superpowers:tdd", home, cwd)!!.origin!!
        assertEquals("https://github.com/obra/superpowers/tree/bb92a77741419a4a/skills/tdd", superpowers.url)
        assertEquals("bb92a777" to "bb92a777", superpowers.installed to superpowers.latest)
        write(location.resolve("plugins/md/.claude-plugin/plugin.json"), """{"name":"md","version":"1.0.1"}""")
        write(installed("md", "1.0.0", "61c0597779bd").resolve("commands/revise.md"))
        val md = skillFiles("md:revise", home, cwd)!!.origin!!
        assertEquals("https://github.com/owner/market/blob/61c0597779bd/plugins/md/commands/revise.md", md.url)
        assertEquals("1.0.0" to "1.0.1", md.installed to md.latest)
    }

    @Test
    fun `a skill the skills tool linked in links its folder in the repository it came from, a skill written by hand has no origin`() {
        val linked = write(dir.resolve(".agents/skills/teach/SKILL.md")).parent
        home.resolve("skills").createDirectories()
        Files.createSymbolicLink(home.resolve("skills/teach"), linked)
        write(dir.resolve(".agents/.skill-lock.json"), """{"version":3,"skills":{"teach":{"source":"mattpocock/skills","sourceType":"github",""" +
            """"skillPath":"skills/productivity/teach/SKILL.md","updatedAt":"2026-06-10T11:52:01.872Z"}}}""")
        write(home.resolve("skills/mine/SKILL.md"))
        assertEquals(
            SkillOrigin("mattpocock/skills", "https://github.com/mattpocock/skills/tree/HEAD/skills/productivity/teach", "2026-06-10", null, null),
            skillFiles("teach", home, cwd)!!.origin,
        )
        assertNull(skillFiles("mine", home, cwd)!!.origin)
    }

    @Test
    fun `a skill built into claude has no files`() {
        assertNull(skillFiles("init", home, cwd))
    }

    @Test
    fun `a skill defined in two places fails`() {
        write(home.resolve("skills/tdd/SKILL.md"))
        write(cwd.resolve(".claude/skills/tdd/SKILL.md"))
        assertFailsWith<IllegalStateException> { skillFiles("tdd", home, cwd) }
    }

    @Test
    fun `frontmatter that is no valid YAML fails the description, not the files`() {
        write(home.resolve("skills/mem/SKILL.md"), "---\nname: mem\ndescription: Search: memory: fast\n---\n")
        val files = skillFiles("mem", home, cwd)!!
        assertTrue(files.description.isFailure)
        assertEquals(1, files.files.size)
    }
}
