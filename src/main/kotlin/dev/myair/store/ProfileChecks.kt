package dev.myair.store

import com.google.gson.JsonParser
import dev.myair.claude.LiveSession
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/** The `idea` entry of `mcpServers` in a `.claude.json` when it is an http server: name and url; an unreadable file throws. */
fun ideServer(claudeJson: Path): Pair<String, String>? {
    val entry = JsonParser.parseString(claudeJson.readText()).asJsonObject["mcpServers"]?.asJsonObject?.get("idea")?.asJsonObject ?: return null
    if (entry["type"]?.asString != "http") return null
    return "idea" to entry["url"].asString
}

/** The PowerShell command that gives [profile] the same MCP server as [server]. */
fun mcpAddCommand(profile: Profile, server: Pair<String, String>) =
    "\$env:CLAUDE_CONFIG_DIR='${profile.dir}'; claude mcp add --scope user --transport http ${server.first} ${server.second}"

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
    profile in managerProfiles -> "A plan's manager uses profile $profile."
    profile in reviewerProfiles -> "A reviewer profile uses profile $profile."
    else -> null
}

/** The directories `claude` and the scanner expect in a profile's directory. */
fun createProfileDirs(profile: Profile) {
    profile.dir.resolve("projects").createDirectories()
    profile.dir.resolve("sessions").createDirectories()
}
