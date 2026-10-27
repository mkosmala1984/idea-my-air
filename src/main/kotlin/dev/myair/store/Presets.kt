package dev.myair.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.claude.ClaudeSession
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

/** The levels `claude --effort` takes. */
val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

/**
 * A way to start a new session. A null [model] or [effort] leaves its flag out, so `claude` takes its own setting; a
 * null [project] is the directory of the IDE project. [worktree]: start in a new worktree off [base], or off a branch
 * picked at start when [base] is null. [addDirs] go to `--add-dir`. [pinned] gives the preset its own toolbar button.
 */
data class Preset(
    val name: String,
    val model: String? = null,
    val effort: String? = null,
    val project: String? = null,
    val worktree: Boolean = false,
    val base: String? = null,
    val addDirs: List<String> = emptyList(),
    val pinned: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "preset name is blank" }
        require(listOfNotNull(model, project, base).plus(addDirs).none { it.isBlank() }) { "preset $name has a blank value" }
        require(effort == null || effort in EFFORTS) { "effort of preset $name is $effort, not one of $EFFORTS" }
        require(worktree || base == null) { "preset $name has a base branch without a worktree" }
    }

    /**
     * The `claude` arguments it adds to `--session-id`, ending with [prompt], the session's first message. It follows
     * `--`: `--add-dir` takes every argument after it, and a prompt naming a subcommand (`mcp`) would run that instead.
     */
    fun claudeArgs(prompt: String? = null): List<String> = buildList {
        model?.let { addAll(listOf("--model", it)) }
        effort?.let { addAll(listOf("--effort", it)) }
        addDirs.forEach { addAll(listOf("--add-dir", it)) }
        prompt?.let {
            require(it.isNotBlank()) { "blank first prompt" }
            addAll(listOf("--", it))
        }
    }
}

/**
 * A preset that starts sessions as [session] runs: its model, effort, project and added directories, and a worktree when it
 * runs in one. The transcript does not name the worktree's base, so that is picked at start.
 */
fun presetOf(session: ClaudeSession, name: String) = Preset(
    name, session.model, session.effort, session.project.toString(), worktree = session.cwd != session.project,
    addDirs = session.addedDirs.map { it.toString() },
)

fun checkPresets(presets: List<Preset>) {
    val names = presets.map { it.name }
    check(names.toSet().size == names.size) { "preset names repeat: $names" }
}

fun presetsJson(presets: List<Preset>): String {
    checkPresets(presets)
    return GsonBuilder().setPrettyPrinting().create().toJson(mapOf("presets" to presets))
}

fun parsePresets(text: String): List<Preset> {
    val root = JsonParser.parseString(text).asJsonObject
    check(root.keySet() == setOf("presets")) { "unexpected keys in presets: ${root.keySet()}" }
    return root["presets"].asJsonArray.map { element ->
        val preset = element.asJsonObject
        check(PRESET_KEYS.containsAll(preset.keySet())) { "unexpected keys in a preset: ${preset.keySet()}" }
        Preset(
            preset.string("name")!!,
            preset.string("model"),
            preset.string("effort"),
            preset.string("project"),
            preset.boolean("worktree"),
            preset.string("base"),
            preset["addDirs"]?.asJsonArray?.map { it.string() }.orEmpty(),
            preset.boolean("pinned"),
        )
    }.also(::checkPresets)
}

private val PRESET_KEYS = setOf("name", "model", "effort", "project", "worktree", "base", "addDirs", "pinned")

private fun JsonObject.string(key: String) = get(key)?.string()

private fun JsonObject.boolean(key: String) = get(key)?.let {
    check(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "$key in a preset is not a boolean: $it" }
    it.asBoolean
} ?: false

private fun JsonElement.string(): String {
    check(isJsonPrimitive && asJsonPrimitive.isString) { "value in a preset is not a string: $this" }
    return asString
}

/**
 * `~/.my-air/presets.json`, shared by every IDE. The toolbar reads it on every update, so it is parsed again only when
 * the file's time or size changes.
 */
class PresetStore(val file: Path) {
    private var cached: Pair<Pair<FileTime, Long>, Result<List<Preset>>>? = null

    /** A failure carries what is wrong with the file. */
    @Synchronized
    fun read(): Result<List<Preset>> {
        if (!file.exists()) return Result.success(emptyList())
        val stamp = file.getLastModifiedTime() to file.fileSize()
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return runCatching { parsePresets(file.readText()) }.also { cached = stamp to it }
    }

    fun write(presets: List<Preset>) {
        file.parent.createDirectories()
        writeAtomically(file, presetsJson(presets))
    }

    companion object {
        val INSTANCE = PresetStore(Path.of(System.getProperty("user.home"), ".my-air", "presets.json"))
    }
}
