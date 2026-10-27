package dev.myair.prompt

import dev.myair.store.EFFORTS
import dev.myair.store.Preset

/** What a configuration that names no model or effort starts with. */
const val DEFAULT_MODEL = "opus"
const val DEFAULT_EFFORT = "medium"

/** The configuration with no settings of its own: the IDE project's directory. */
val DEFAULT_PRESET = Preset("Default")

/** The panel's choices: [preset] gives the directories, and the rest start from it and may be changed. */
data class Choice(val preset: Preset, val model: String, val effort: String, val worktree: Boolean) {
    init {
        require(effort in EFFORTS) { "effort $effort is not one of $EFFORTS" }
    }

    /** The preset to start: [base] only for a worktree, null meaning a base picked in the popup at start. */
    fun toPreset(base: String?) = preset.copy(model = model, effort = effort, worktree = worktree, base = base.takeIf { worktree })
}

fun choiceOf(preset: Preset) = Choice(preset, preset.model ?: DEFAULT_MODEL, preset.effort ?: DEFAULT_EFFORT, preset.worktree)

/** The choices of the last start, each null when never made; the configuration is kept by name. */
data class LastChoice(val preset: String?, val model: String?, val effort: String?, val worktree: Boolean?)

/**
 * A message typed but not sent, with the choices and the worktree [base] it was typed with; the panel opens on it
 * instead of the last start's choices. Kept only while there is a message.
 */
data class Draft(val text: String, val choice: LastChoice, val base: String?) {
    init {
        require(text.isNotBlank()) { "blank draft" }
    }
}

fun Choice.toLast() = LastChoice(preset.name, model, effort, worktree)

/** The draft's choices when there is one, else the last start's. */
fun opening(presets: List<Preset>, last: LastChoice, draft: Draft?) = restore(presets, draft?.choice ?: last)

/** The last configuration while it still exists, else the first; each last choice over that configuration's own. */
fun restore(presets: List<Preset>, last: LastChoice): Choice {
    val own = choiceOf(presets.firstOrNull { it.name == last.preset } ?: presets.first())
    return Choice(own.preset, last.model ?: own.model, last.effort ?: own.effort, last.worktree ?: own.worktree)
}

/** The gray note after a branch in the base list. */
fun branchTag(branch: String, current: String?, default: String, recent: List<String>) = when (branch) {
    current -> "current"
    default -> "default"
    in recent -> "recent"
    else -> null
}

/** `opus` as `Claude Opus`; a full model name as it is. */
fun modelLabel(model: String) = if (model.all { it.isLetter() }) "Claude " + model.replaceFirstChar { it.uppercase() } else model
