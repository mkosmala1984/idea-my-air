package dev.myair.prompt

import dev.myair.store.EFFORTS
import dev.myair.store.Preset
import java.nio.file.Path

/** What a configuration that names no model or effort starts with. */
const val DEFAULT_MODEL = "opus"
const val DEFAULT_EFFORT = "medium"

/** The configuration with no settings of its own: the IDE project's directory. */
val DEFAULT_PRESET = Preset("Default")

/**
 * The panel's choices: [preset] gives the directories, and the rest start from it and may be changed. [newBranch]: the
 * worktree gets a new branch off the base, named in the panel. [project] is a directory chosen in place of the
 * configuration's own, null keeping it.
 */
data class Choice(
    val preset: Preset, val model: String, val effort: String, val worktree: Boolean, val newBranch: Boolean = false,
    val project: String? = null,
) {
    init {
        require(effort in EFFORTS) { "effort $effort is not one of $EFFORTS" }
        require(worktree || !newBranch) { "a new branch without a worktree" }
    }

    /** The preset to start: [base] only for a worktree, null meaning a base picked in the popup at start. */
    fun toPreset(base: String?) =
        preset.copy(model = model, effort = effort, worktree = worktree, base = base.takeIf { worktree }, project = project ?: preset.project)
}

fun choiceOf(preset: Preset) = Choice(preset, preset.model ?: DEFAULT_MODEL, preset.effort ?: DEFAULT_EFFORT, preset.worktree)

/** The choices of the last start, each null when never made; the configuration is kept by name. */
data class LastChoice(
    val preset: String?, val model: String?, val effort: String?, val worktree: Boolean?, val newBranch: Boolean? = null,
    val project: String? = null,
)

/**
 * A message typed but not sent, with the choices and the worktree [base] it was typed with; the panel opens on it
 * instead of the last start's choices. Kept only while there is a message.
 */
data class Draft(val text: String, val choice: LastChoice, val base: String?) {
    init {
        require(text.isNotBlank()) { "blank draft" }
    }
}

fun Choice.toLast() = LastChoice(preset.name, model, effort, worktree, newBranch, project)

/** The draft's choices when there is one, else the last start's. */
fun opening(presets: List<Preset>, last: LastChoice, draft: Draft?) = restore(presets, draft?.choice ?: last)

/** The last configuration while it still exists, else the first; each last choice over that configuration's own. */
fun restore(presets: List<Preset>, last: LastChoice): Choice {
    val own = choiceOf(presets.firstOrNull { it.name == last.preset } ?: presets.first())
    val worktree = last.worktree ?: own.worktree
    return Choice(own.preset, last.model ?: own.model, last.effort ?: own.effort, worktree, worktree && last.newBranch == true, last.project)
}

/** What the message field holds when [preset] is chosen: its prompt and a space to go on typing, else nothing. */
fun openingMessage(preset: Preset) = preset.prompt?.let { "$it " }.orEmpty()

/** The message after switching [from] one configuration [to] another: replaced while empty or [from]'s untouched, else kept. */
fun switchedMessage(text: String, from: Preset, to: Preset) =
    if (text.isBlank() || text == openingMessage(from)) openingMessage(to) else text

/**
 * The projects the composer offers: the configuration's [own], then [open] ones, then [recent] ones that [exists], each
 * directory once (compared as paths, so case and separators do not tell two apart on Windows).
 */
fun projectChoices(own: String?, open: List<String>, recent: List<String>, exists: (String) -> Boolean): List<String> =
    (listOfNotNull(own) + open + recent.filter(exists)).distinctBy { Path.of(it).normalize() }

/** The choices with a [project] that no longer [exists] forgotten, so the configuration's own directory counts again. */
fun LastChoice.present(exists: (String) -> Boolean) = copy(project = project?.takeIf(exists))

/** A directory's name on its chip: its last part; a drive root has none and is named by itself. */
fun dirName(dir: Path): String = (dir.fileName ?: dir).toString()

/** The gray note after a branch in the base list. */
fun branchTag(branch: String, current: String?, default: String, recent: List<String>) = when (branch) {
    current -> "current"
    default -> "default"
    in recent -> "recent"
    else -> null
}

/** `opus` as `Claude Opus`; a full model name as it is. */
fun modelLabel(model: String) = if (model.all { it.isLetter() }) "Claude " + model.replaceFirstChar { it.uppercase() } else model
