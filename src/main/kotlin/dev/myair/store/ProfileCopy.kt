package dev.myair.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.CopyActionResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** What a [CopyItem] takes from the default profile's directory. */
sealed interface CopySource {
    /** One key of `settings.json`. */
    data class SettingsKey(val key: String) : CopySource
    data class File(val name: String) : CopySource
    data class Directory(val name: String) : CopySource
}

/** The items the New Claude Profile dialog can copy once from the default profile; the only place naming their keys and files. */
enum class CopyItem(val group: String, val label: String, val effect: String, val source: CopySource) {
    STATUS_LINE("Look", "Status line", "the same status line at the bottom of the session", CopySource.SettingsKey("statusLine")),
    THEME("Look", "Theme", "the same colours", CopySource.SettingsKey("theme")),
    MODEL("Behaviour", "Model", "the same default model", CopySource.SettingsKey("model")),
    EFFORT_LEVEL("Behaviour", "Effort level", "the same default effort", CopySource.SettingsKey("effortLevel")),
    LANGUAGE("Behaviour", "Language", "answers in the same language", CopySource.SettingsKey("language")),
    CLAUDE_MD("Instructions", "CLAUDE.md", "the same global instructions", CopySource.File("CLAUDE.md")),
    SKILLS("Skills, agents and commands", "Skills", "the same skills", CopySource.Directory("skills")),
    AGENTS("Skills, agents and commands", "Agents", "the same subagents", CopySource.Directory("agents")),
    COMMANDS("Skills, agents and commands", "Commands", "the same slash commands", CopySource.Directory("commands"));

    /** Ticked when the dialog opens: the look and behaviour items. */
    val tickedByDefault get() = source is CopySource.SettingsKey
}

sealed interface Availability {
    data object Available : Availability

    /** [error]: the source is broken rather than absent. */
    data class Unavailable(val reason: String, val error: Boolean) : Availability
}

/** Thrown by [copyFromDefault]; its message names the failing item or step. */
class ProfileCopyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private const val SETTINGS = "settings.json"
private const val CUSTOM_THEME = "custom:"

/** The `themes/` file a `custom:<name>` theme needs, null for a built-in theme; a name that could leave `themes/` throws. */
private fun themeFile(sourceDir: Path, theme: String): Path? {
    if (!theme.startsWith(CUSTOM_THEME)) return null
    val name = theme.removePrefix(CUSTOM_THEME)
    require(name.isNotEmpty() && '/' !in name && '\\' !in name && ".." !in name) { "theme name '$name' is not a plain file name" }
    return sourceDir.resolve("themes").resolve("$name.json")
}

/** [file] as a JSON object, null when it is absent; invalid JSON or another JSON value throws naming the file. */
private fun readSettings(file: Path): JsonObject? {
    if (!file.exists()) return null
    val root = try {
        JsonParser.parseString(file.readText())
    } catch (e: JsonParseException) {
        throw IllegalArgumentException("$file is not valid JSON: ${e.message}", e)
    }
    require(root.isJsonObject) { "$file is not a JSON object" }
    return root.asJsonObject
}

/** Each item's availability in [sourceDir] (the default profile); reads only, writes nothing. */
fun copyAvailability(sourceDir: Path): Map<CopyItem, Availability> {
    val settingsFile = sourceDir.resolve(SETTINGS)
    val settings = runCatching { readSettings(settingsFile) }
    return CopyItem.entries.associateWith { item ->
        when (val source = item.source) {
            is CopySource.SettingsKey -> {
                val root = settings.getOrElse { return@associateWith Availability.Unavailable(it.message!!, true) }
                    ?: return@associateWith Availability.Unavailable("no $SETTINGS in $sourceDir", false)
                val value = root[source.key] ?: return@associateWith Availability.Unavailable("no ${source.key} in $settingsFile", false)
                if (item != CopyItem.THEME || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) return@associateWith Availability.Available
                val file = runCatching { themeFile(sourceDir, value.asString) }
                    .getOrElse { return@associateWith Availability.Unavailable(it.message!!, true) }
                if (file == null || file.isRegularFile()) Availability.Available else Availability.Unavailable("theme file $file is missing", true)
            }
            is CopySource.File -> {
                val file = sourceDir.resolve(source.name)
                when {
                    !file.exists() -> Availability.Unavailable("no $file", false)
                    !file.isRegularFile() -> Availability.Unavailable("$file is not a file", true)
                    else -> Availability.Available
                }
            }
            is CopySource.Directory -> {
                val dir = sourceDir.resolve(source.name)
                when {
                    !dir.exists(LinkOption.NOFOLLOW_LINKS) -> Availability.Unavailable("no $dir", false)
                    !dir.isDirectory() -> Availability.Unavailable("$dir is not a directory", true)
                    else -> Availability.Available
                }
            }
        }
    }
}

/**
 * Copies [selection] from [sourceDir] (the default profile) into [targetDir], which must be absent or empty, then
 * calls [register]. When the copy or [register] fails, everything under [targetDir] is deleted (and [targetDir] when
 * it did not exist) and a [ProfileCopyException] names the failing item or step. An empty selection only registers.
 */
fun copyFromDefault(sourceDir: Path, targetDir: Path, selection: Set<CopyItem>, register: () -> Unit) {
    if (selection.isEmpty()) return register()
    val existed = targetDir.exists(LinkOption.NOFOLLOW_LINKS)
    if (existed && (!targetDir.isDirectory(LinkOption.NOFOLLOW_LINKS) || targetDir.listDirectoryEntries().isNotEmpty())) {
        throw ProfileCopyException("$targetDir is not empty; a profile copies into an absent or empty directory only")
    }
    val availability = copyAvailability(sourceDir)
    selection.sortedBy { it.ordinal }.forEach { item ->
        val a = availability.getValue(item)
        if (a is Availability.Unavailable) throw ProfileCopyException("${item.label} cannot be copied: ${a.reason}")
    }
    var step = "Creating $targetDir"
    try {
        Files.createDirectories(targetDir)
        val keyItems = selection.filter { it.source is CopySource.SettingsKey }.sortedBy { it.ordinal }
        if (keyItems.isNotEmpty()) {
            step = "Copying " + keyItems.joinToString { it.label }
            val settings = checkNotNull(readSettings(sourceDir.resolve(SETTINGS))) { "${sourceDir.resolve(SETTINGS)} is gone" }
            val keys = keyItems.map { (it.source as CopySource.SettingsKey).key }.toSet()
            val copy = JsonObject()
            settings.entrySet().filter { it.key in keys }.forEach { copy.add(it.key, it.value) }
            val text = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(copy)
            Files.writeString(targetDir.resolve(SETTINGS), text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val theme = copy["theme"]
            if (theme != null && theme.isJsonPrimitive && theme.asJsonPrimitive.isString) {
                step = "Copying the theme file"
                themeFile(sourceDir, theme.asString)?.let { file ->
                    Files.createDirectories(targetDir.resolve("themes"))
                    Files.copy(file, targetDir.resolve("themes").resolve(file.fileName.toString()))
                }
            }
        }
        for (item in selection.sortedBy { it.ordinal }) {
            step = "Copying ${item.label}"
            when (val source = item.source) {
                is CopySource.SettingsKey -> {}
                is CopySource.File -> Files.copy(sourceDir.resolve(source.name), targetDir.resolve(source.name))
                is CopySource.Directory -> copyTree(sourceDir.resolve(source.name), targetDir.resolve(source.name))
            }
        }
        step = "Registration of the profile"
        register()
    } catch (e: Throwable) {
        val left = rollBack(targetDir, existed)
        val cleanup = if (left.isEmpty()) "" else "\nThese could not be deleted, remove them by hand:\n" + left.joinToString("\n")
        throw ProfileCopyException("$step failed: ${e.message}$cleanup", e)
    }
}

/** Copies [source]'s tree to the absent [target]; a link, junction or any entry neither a plain file nor a directory throws, naming it. */
@OptIn(ExperimentalPathApi::class)
private fun copyTree(source: Path, target: Path) {
    source.copyToRecursively(target, followLinks = false) { from, to ->
        val attrs = Files.readAttributes(from, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        // A junction reads as both isDirectory and isOther (not isSymbolicLink), so isOther goes before isDirectory.
        when {
            attrs.isSymbolicLink -> throw ProfileCopyException("$from is a symbolic link, which is not copied")
            attrs.isOther -> throw ProfileCopyException("$from is a link, junction or other special entry, which is not copied")
            attrs.isDirectory -> Files.createDirectory(to)
            attrs.isRegularFile -> Files.copy(from, to, LinkOption.NOFOLLOW_LINKS)
            else -> throw ProfileCopyException("$from is neither a file nor a directory")
        }
        CopyActionResult.CONTINUE
    }
}

/** Deletes everything under [dir], and [dir] unless it [existed]; returns the paths left behind. */
private fun rollBack(dir: Path, existed: Boolean): List<Path> {
    if (!dir.exists(LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val all = Files.walk(dir).use { it.toList() }.sortedByDescending { it.nameCount }
    val left = mutableListOf<Path>()
    for (path in all) {
        if (path == dir && existed) continue
        runCatching { Files.delete(path) }.onFailure { left.add(path) }
    }
    return left
}
