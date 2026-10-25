package dev.myair.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.AvatarIcon
import com.intellij.util.ui.AvatarPalette
import dev.myair.MyAirConfigurable
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.projectBase
import dev.myair.store.Preset
import dev.myair.store.PresetStore
import dev.myair.store.presetOf
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** Starts a session as [preset] says; a missing directory is reported instead. */
fun startPreset(project: Project, preset: Preset, where: RelativePoint) {
    val dir = preset.project?.let(Path::of) ?: projectBase(project)
    val missing = (listOf(dir) + preset.addDirs.map(Path::of)).filterNot { it.isDirectory() }
    if (missing.isNotEmpty()) return Messages.showErrorDialog(project, "Directories of \"${preset.name}\" no longer exist:\n${missing.joinToString("\n")}", "Cannot Start Session")
    if (preset.worktree) startInWorktree(project, dir, preset.base, preset.claudeArgs(), where)
    else ClaudeSessionOpener.startNew(project, dir, args = preset.claudeArgs())
}

/** What a preset sets, for its tooltip. */
fun Preset.summary() = listOfNotNull(
    model?.let { "model $it" },
    effort?.let { "effort $it" },
    project ?: "the project's directory",
    if (!worktree) null else "worktree off ${base ?: "a branch picked at start"}",
    addDirs.takeIf { it.isNotEmpty() }?.joinToString(", ", "also ") { it },
).joinToString("; ")

class PresetAction(private val preset: Preset) : DumbAwareAction(preset.name, preset.summary(), AvatarIcon(16, 0.3, preset.name, preset.name, AvatarPalette)) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = startPreset(e.project!!, preset, popupPoint(e))
}

/**
 * The toolbar's presets: a button for each pinned one, then a dropdown with all of them and the settings that edit them.
 * Asked for on every toolbar update; the same actions come back until the presets change, or the toolbar rebuilds its buttons.
 */
class PresetsGroup : ActionGroup(), DumbAware {
    private var built: Pair<Result<List<Preset>>, Array<AnAction>>? = null

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    @Synchronized
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val read = PresetStore.INSTANCE.read()
        built?.takeIf { it.first == read }?.let { return it.second }
        return build(read).also { built = read to it }
    }

    private fun build(read: Result<List<Preset>>): Array<AnAction> {
        val edit = object : DumbAwareAction("Edit Configurations...", "Settings › Tools › My Air", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) = ShowSettingsUtil.getInstance().showSettingsDialog(e.project, MyAirConfigurable::class.java)
        }
        val dropdown = DefaultActionGroup("New Claude Session from Configuration", true).apply { templatePresentation.icon = AllIcons.Actions.Lightning }
        val presets = read.getOrElse { error ->
            // A broken file is named in the dropdown rather than thrown on every update.
            dropdown.add(Separator.create("Broken ${PresetStore.INSTANCE.file}: ${error.message}"))
            return arrayOf(dropdown)
        }
        dropdown.addAll(presets.map(::PresetAction))
        if (presets.isNotEmpty()) dropdown.add(Separator.getInstance())
        dropdown.add(edit)
        return (presets.filter { it.pinned }.map(::PresetAction) + dropdown).toTypedArray()
    }
}

/** On a session's menu: a new session started as the [selected] one runs ([presetOf]). */
class SameSettingsAction(private val selected: () -> List<ClaudeSession>) :
    DumbAwareAction("New Session with Same Settings", "Same model, effort, project, worktree and added directories", AllIcons.General.Add) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selected().size == 1
    }
    override fun actionPerformed(e: AnActionEvent) {
        val session = selected().single()
        startPreset(e.project!!, presetOf(session, session.title), popupPoint(e))
    }
}

/** On a session's menu: a new preset holding the [selected] session's settings, under a name typed in the dialog. */
class SaveAsPresetAction(private val selected: () -> List<ClaudeSession>) :
    DumbAwareAction("Save Settings as Configuration...", "A session configuration with this session's settings", AllIcons.Actions.Lightning) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selected().size == 1
    }
    override fun actionPerformed(e: AnActionEvent) {
        val session = selected().single()
        val store = PresetStore.INSTANCE
        val names = store.read().getOrThrow().map { it.name }.toSet()
        val preset = PresetDialog(presetOf(session, session.title), names, "New Session Configuration", projectBase(e.project!!), name = "").preset() ?: return
        store.write(store.read().getOrThrow() + preset)
    }
}
