package dev.myair.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

const val DEFAULT_PROFILE = "default"

/** A Claude Code configuration directory (`CLAUDE_CONFIG_DIR`) with a name. */
data class Profile(val name: String, val dir: Path)

val Profile.isDefault get() = name == DEFAULT_PROFILE

/** The profiles of `profiles.json` (never `default`, which is [defaultDir]), validated. */
fun parseProfiles(text: String, defaultDir: Path): List<Profile> {
    val root = JsonParser.parseString(text).asJsonObject
    require(root.keySet() == setOf("version", "profiles")) { "unexpected keys in profiles: ${root.keySet()}" }
    require(root["version"].asInt == 1) { "unsupported profiles version ${root["version"]}" }
    val profiles = root["profiles"].asJsonArray.map {
        val entry = it.asJsonObject
        require(entry.keySet() == setOf("name", "dir")) { "unexpected keys in a profile: ${entry.keySet()}" }
        Profile(entry["name"].asString, Path.of(entry["dir"].asString))
    }
    val names = profiles.map { it.name }
    require(names.none { it.isBlank() }) { "a profile name is blank" }
    require(DEFAULT_PROFILE !in names) { "'$DEFAULT_PROFILE' is the profile of $defaultDir and is not listed" }
    require(names.toSet().size == names.size) { "profile names repeat: $names" }
    profiles.forEach { require(it.dir.isAbsolute) { "directory of profile ${it.name} is not absolute: ${it.dir}" } }
    val dirs = profiles.map { it.dir }
    require(dirs.toSet().size == dirs.size) { "profile directories repeat: $dirs" }
    require(defaultDir !in dirs) { "a profile uses the default directory $defaultDir" }
    return profiles
}

fun profilesJson(profiles: List<Profile>): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    return gson.toJson(mapOf("version" to 1, "profiles" to profiles.map { mapOf("name" to it.name, "dir" to it.dir.toString()) }))
}

/** What to set in a `claude` process's environment: a null value removes the variable. */
fun profileEnv(profile: Profile): Map<String, String?> = mapOf("CLAUDE_CONFIG_DIR" to profile.dir.toString().takeUnless { profile.isDefault })

/**
 * [profileEnv] for a process that inherits the IDE's environment and can only have variables added (the terminal,
 * `GeneralCommandLine`): a `default` start throws while the IDE has `CLAUDE_CONFIG_DIR` ([inherited]), which it could not remove.
 */
fun addedProfileEnv(profile: Profile, inherited: String? = System.getenv("CLAUDE_CONFIG_DIR")): Map<String, String> {
    check(!profile.isDefault || inherited == null) { "The IDE runs with CLAUDE_CONFIG_DIR=$inherited; My Air's default profile is ~/.claude" }
    return profileEnv(profile).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}

/** `~/.my-air/profiles.json`, shared by every IDE; parsed again only when the file's time or size changes. */
class ProfileStore(val file: Path, val defaultDir: Path) {
    private var cached: Pair<Pair<FileTime, Long>, List<Profile>>? = null

    /** `default` first, then the file's. Throws when the file is broken. */
    @Synchronized
    fun all(): List<Profile> = listOf(Profile(DEFAULT_PROFILE, defaultDir)) + fromFile()

    private fun fromFile(): List<Profile> {
        if (!file.exists()) return emptyList()
        val stamp = file.getLastModifiedTime() to file.fileSize()
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return parseProfiles(file.readText(), defaultDir).also { cached = stamp to it }
    }

    fun byName(name: String): Profile {
        val all = all()
        return requireNotNull(all.find { it.name == name }) { "no profile '$name'; profiles: ${all.map { it.name }}" }
    }

    fun write(profiles: List<Profile>) {
        val text = profilesJson(profiles)
        parseProfiles(text, defaultDir)
        file.parent.createDirectories()
        writeAtomically(file, text)
    }

    companion object {
        private val instance = ProfileStore(Homes.myAir.resolve("profiles.json"), Homes.claude)
        fun getInstance() = instance
    }
}
