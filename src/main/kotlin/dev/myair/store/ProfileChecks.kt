package dev.myair.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.claude.LiveSession
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** The `idea` entry of `mcpServers` in a `.claude.json` when it is an http server: name and url; an unreadable file throws. */
fun ideServer(claudeJson: Path): Pair<String, String>? {
    if (!claudeJson.exists()) return null
    val entry = JsonParser.parseString(claudeJson.readText()).asJsonObject["mcpServers"]?.asJsonObject?.get("idea")?.asJsonObject ?: return null
    if (entry["type"]?.asString != "http") return null
    return "idea" to httpUrl("idea", entry, claudeJson)
}

/** The `url` of the http MCP server [name] of [file]; an entry without one fails naming both. */
private fun httpUrl(name: String, entry: JsonObject, file: Path): String =
    checkNotNull(entry["url"]) { "http MCP server $name in $file has no url" }.asString

/** The PowerShell command that gives [profile] the same MCP server as [server], leaving no `CLAUDE_CONFIG_DIR` in the shell. */
fun mcpAddCommand(profile: Profile, server: Pair<String, String>): String {
    require("'" !in profile.dir.toString()) { "directory of profile ${profile.name} contains a quote: ${profile.dir}" }
    return "& { \$env:CLAUDE_CONFIG_DIR='${profile.dir}'; claude mcp add --scope user --transport http ${server.first} ${server.second}; Remove-Item Env:CLAUDE_CONFIG_DIR }"
}

/**
 * Why a manager cannot run in [profile], null when it can: `default` always can; another profile only when its
 * `.claude.json` has an `http` server with the url of `default`'s `idea` entry ([defaultClaudeJson]), under any name.
 */
fun managerToolsRefusal(profile: Profile, defaultClaudeJson: Path): String? {
    if (profile.isDefault) return null
    val server = ideServer(defaultClaudeJson) ?: return "No http MCP server named idea in $defaultClaudeJson, so profile ${profile.name} cannot get My Air's."
    val own = profile.dir.resolve(".claude.json")
    val urls = if (!own.exists()) emptyList() else
        JsonParser.parseString(own.readText()).asJsonObject["mcpServers"]?.asJsonObject?.entrySet().orEmpty()
            .filter { it.value.asJsonObject["type"]?.asString == "http" }.map { httpUrl(it.key, it.value.asJsonObject, own) }
    return if (server.second in urls) null else "Profile ${profile.name} has no My Air MCP server. Run:\n${mcpAddCommand(profile, server)}"
}

/** Why [profile] cannot be removed from the list, else null; each argument holds the profile names in use. */
fun removeRefusal(
    profile: String,
    live: Collection<LiveSession>,
    tabProfiles: Collection<String>,
    managerProfiles: Collection<String>,
    reviewerProfiles: Collection<String>,
): String? = when {
    live.any { it.profile == profile } -> "A session of profile $profile is running (pid ${live.first { it.profile == profile }.pid})."
    profile in tabProfiles -> "A tab shows a session of profile $profile."
    profile in managerProfiles -> "A plan's or review's manager uses profile $profile."
    profile in reviewerProfiles -> "A reviewer profile uses profile $profile."
    else -> null
}

/** The directories `claude` and the scanner expect in a profile's directory. */
fun createProfileDirs(profile: Profile) {
    profile.dir.resolve("projects").createDirectories()
    profile.dir.resolve("sessions").createDirectories()
}
