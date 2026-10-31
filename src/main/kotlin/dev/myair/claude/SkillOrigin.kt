package dev.myair.claude

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Where a skill was installed from, as the files `claude` and the `skills` tool keep tell it, without asking the network.
 * [source]: the plugin and its marketplace (`pstack@open-pstack`) or the repository (`mattpocock/skills`); [url] the skill
 * on GitHub, at the installed commit when known; [installed] its version, a commit for a plugin a marketplace pins by
 * commit; [latest] the marketplace's version as of its last refresh at [checked], null when it does not tell.
 */
data class SkillOrigin(val source: String, val url: String?, val installed: String?, val latest: String?, val checked: Instant?)

/** A plugin as `plugins/installed_plugins.json` lists it, in one scope. */
internal class Install(val plugin: String, val marketplace: String, val path: Path, val version: String?, val commit: String?)

internal fun installs(home: Path): List<Install> {
    val file = home.resolve("plugins/installed_plugins.json")
    if (!file.isRegularFile()) return emptyList()
    return json(file).getAsJsonObject("plugins").entrySet().flatMap { (key, scopes) ->
        scopes.asJsonArray.map { it.asJsonObject }.map {
            Install(
                key.substringBefore('@'), key.substringAfter('@'),
                Path.of(checkNotNull(it.string("installPath")) { "$key without installPath in $file" }), it.string("version"), it.string("gitCommitSha"),
            )
        }
    }
}

/**
 * The origin of the skill [skill] defined at [definition]: of the installed [plugin] holding it, or of a skill the `skills`
 * tool linked into `~/.claude/skills` from its `~/.agents/skills` (`~/.agents/.skill-lock.json`); null for others.
 */
internal fun skillOrigin(home: Path, plugin: String, skill: String, definition: Path): SkillOrigin? =
    if (plugin.isEmpty()) lockedOrigin(home, skill, definition)
    else installs(home).firstOrNull { it.plugin == plugin && definition.startsWith(it.path) }?.let { pluginOrigin(home, it, definition) }

private fun pluginOrigin(home: Path, install: Install, definition: Path): SkillOrigin {
    val marketplace = home.resolve("plugins/known_marketplaces.json").takeIf { it.isRegularFile() }
        ?.let { json(it).getAsJsonObject(install.marketplace) }
    val location = marketplace?.string("installLocation")?.let { Path.of(it) }
    val entry = location?.resolve(".claude-plugin/marketplace.json")?.takeIf { it.isRegularFile() }
        ?.let { json(it).getAsJsonArray("plugins").map { p -> p.asJsonObject }.firstOrNull { p -> p.string("name") == install.plugin } }
    val source = entry?.get("source")
    val inPlugin = install.path.relativize(definition).joinToString("/")
    // A source is a path in the marketplace's repository, or a repository of its own.
    val (repo, path) = when {
        source == null -> null to inPlugin
        source.isJsonPrimitive -> checkNotNull(marketplace).getAsJsonObject("source").takeIf { it.string("source") == "github" }?.string("repo") to
            "${source.asString.removePrefix("./")}/$inPlugin"
        else -> source.asJsonObject.let { (it.string("repo") ?: it.string("url")?.let(::githubRepo)) to inPlugin }
    }
    val sha = source?.takeIf { it.isJsonObject }?.asJsonObject?.string("sha")
    val (installed, latest) = when {
        entry?.string("version") != null -> install.version to entry.string("version")
        sha != null -> install.commit?.take(SHORT_SHA) to sha.take(SHORT_SHA)
        source?.isJsonPrimitive == true -> install.version to checkNotNull(location).resolve(source.asString).resolve(".claude-plugin/plugin.json")
            .takeIf { it.isRegularFile() }?.let { json(it).string("version") }
        else -> install.version to null
    }
    return SkillOrigin(
        "${install.plugin}@${install.marketplace}", repo?.let { githubUrl(it, install.commit ?: "HEAD", path, definition) },
        installed, latest, marketplace?.string("lastUpdated")?.let(Instant::parse),
    )
}

private fun lockedOrigin(home: Path, skill: String, definition: Path): SkillOrigin? {
    val agents = home.resolveSibling(".agents")
    val lock = agents.resolve(".skill-lock.json")
    if (!lock.isRegularFile() || !definition.toRealPath().startsWith(agents.resolve("skills").toRealPath())) return null
    val entry = json(lock).getAsJsonObject("skills").getAsJsonObject(skill) ?: return null
    val repo = entry.string("source").takeIf { entry.string("sourceType") == "github" }
    val path = entry.string("skillPath")?.substringBeforeLast('/', "")
    return SkillOrigin(
        checkNotNull(entry.string("source")) { "$skill without source in $lock" },
        if (repo != null && path != null) githubUrl(repo, "HEAD", path, definition) else null,
        entry.string("updatedAt")?.take(DATE), null, null,
    )
}

private const val SHORT_SHA = 8
private const val DATE = 10

private val GITHUB = Regex("https://github\\.com/([^/]+/[^/]+?)(?:\\.git)?/?")

private fun githubRepo(url: String) = GITHUB.matchEntire(url)?.groupValues?.get(1)

private fun githubUrl(repo: String, ref: String, path: String, definition: Path) =
    "https://github.com/$repo/${if (definition.isDirectory()) "tree" else "blob"}/$ref/$path"

private fun json(file: Path): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

private fun JsonObject.string(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
