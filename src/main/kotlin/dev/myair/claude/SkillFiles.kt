package dev.myair.claude

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A skill's files: its [definition], a directory holding `SKILL.md` for a skill, a single `.md` file for a command;
 * [description] the `description` of its frontmatter, null without one, a failure when that is no valid YAML; [files]
 * everything under a skill's directory, sorted by path; [origin] where it was installed from, null for one written by hand.
 */
data class SkillFiles(val definition: Path, val description: Result<String?>, val files: List<Path>, val origin: SkillOrigin?)

private const val SKILL_FILE = "SKILL.md"

/** The plugin name `claude` gives the skills synced from claude.ai, which it keeps in `skills/synced/<account>/`. */
private const val SYNCED_SKILLS = "anthropic-skills"

private val FRONTMATTER = Regex("\\A---\\r?\\n(.*?)\\r?\\n---", RegexOption.DOT_MATCHES_ALL)

/**
 * The files of the skill [name] as `claude` lays them out under [home] (`~/.claude`) and the session's [cwd]. A
 * `plugin:skill` comes from that installed plugin (`plugins/installed_plugins.json`), a plugin synced from claude.ai
 * (`plugins/synced/<account>/<plugin>~<generation>`), the skills synced from claude.ai ([SYNCED_SKILLS]) or a folder of
 * the user's commands; a name without a plugin from the user's or the project's skills or commands. Null for a skill
 * built into `claude`, which has no files; two places defining it throw.
 */
fun skillFiles(name: String, home: Path, cwd: Path): SkillFiles? {
    val plugin = name.substringBeforeLast(':', "")
    val skill = name.substringAfterLast(':')
    val candidates = if (plugin.isEmpty()) {
        listOf(home, cwd.resolve(".claude")).flatMap { listOf(it.resolve("skills/$skill/$SKILL_FILE"), it.resolve("commands/$skill.md")) }
    } else {
        // listOf around single paths: a Path is an Iterable of its names, so `list + path` would add those.
        installs(home).filter { it.plugin == plugin }.map { it.path }
            .flatMap { skillsNamed(it.resolve("skills"), skill) + listOf(it.resolve("commands/$skill.md")) } +
            dirs(home.resolve("plugins/synced")).flatMap(::dirs).filter { it.name.startsWith("$plugin~") }
                .flatMap { skillsNamed(it.resolve("skills"), skill) } +
            (if (plugin == SYNCED_SKILLS) dirs(home.resolve("skills/synced")).map { it.resolve("$skill/$SKILL_FILE") } else emptyList()) +
            listOf(home.resolve("commands/$plugin/$skill.md"))
    }
    val found = candidates.filter { it.isRegularFile() }.distinct()
    check(found.size <= 1) { "$name is defined in more than one place: ${found.joinToString()}" }
    val file = found.singleOrNull() ?: return null
    val description = runCatching {
        FRONTMATTER.find(file.readText())?.let { (Yaml().load<Any?>(it.groupValues[1]) as? Map<*, *>)?.get("description")?.toString() }
    }
    val definition = if (file.name == SKILL_FILE) file.parent else file
    val files = if (file.name == SKILL_FILE) Files.walk(definition).use { paths -> paths.filter { it != definition }.sorted().toList() } else emptyList()
    return SkillFiles(definition, description, files, skillOrigin(home, plugin, skill, definition))
}

/** The `SKILL.md` of each directory named [skill] under [root]: a plugin may sort its skills into folders. */
private fun skillsNamed(root: Path, skill: String): List<Path> = if (!root.isDirectory()) emptyList()
else Files.walk(root).use { paths -> paths.filter { it.isDirectory() && it.name == skill }.map { it.resolve(SKILL_FILE) }.toList() }

private fun dirs(dir: Path): List<Path> = if (dir.isDirectory()) dir.listDirectoryEntries().filter { it.isDirectory() } else emptyList()
