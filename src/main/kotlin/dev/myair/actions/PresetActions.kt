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
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.AvatarIcon
import com.intellij.util.ui.AvatarPalette
import dev.myair.MyAirConfigurable
import dev.myair.claude.ClaudeSession
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.NEW_SESSION_ICON
import dev.myair.editor.projectBase
import dev.myair.planview.startAsPlan
import dev.myair.prompt.modelLabel
import dev.myair.prompt.showPromptPopup
import dev.myair.store.Preset
import dev.myair.store.PresetStore
import dev.myair.store.Variant
import dev.myair.store.continuationPrompt
import dev.myair.store.presetOf
import java.nio.file.Path
import java.util.UUID

/**
 * Starts a session as [preset] says, with [prompt] as its first message, in a worktree on a new [branch] when given;
 * a missing directory is reported instead. With [planName] the session starts as the manager of a new plan of that name,
 * in a plan tab ([startAsPlan]), rather than in a session tab.
 */
fun startPreset(project: Project, preset: Preset, where: RelativePoint, prompt: String? = null, branch: String? = null, planName: String? = null) {
    require(branch == null || preset.worktree) { "new branch $branch without a worktree" }
    val dir = preset.dir(projectBase(project))
    if (!allExist(project, preset, dir)) return
    val open: (Path, String, List<String>) -> Unit =
        if (planName == null) { cwd, id, args -> ClaudeSessionOpener.startNew(project, dir, cwd, id, args) }
        else { cwd, id, args -> startAsPlan(project, planName, dir, cwd, id, args) }
    if (preset.worktree) startInWorktree(project, dir, preset.base, preset.claudeArgs(prompt), where, branch = branch, open = open)
    else open(dir, UUID.randomUUID().toString(), preset.claudeArgs(prompt))
}

/**
 * Starts a session continuing [session] in [workTree], the folder it works in now (its worktree too, no new one), with
 * its model, effort and added directories; the first message names [session] ([continuationPrompt]).
 */
fun continueSession(project: Project, session: ClaudeSession, workTree: Path) {
    val preset = presetOf(session, session.title).copy(worktree = false)
    if (!allExist(project, preset, workTree)) return
    ClaudeSessionOpener.startNew(project, session.project, workTree, args = preset.claudeArgs(continuationPrompt(session)))
}

/** Whether [dir] and [preset]'s added directories all exist; the missing ones are reported. */
private fun allExist(project: Project, preset: Preset, dir: Path): Boolean {
    val missing = preset.missingDirs(dir)
    if (missing.isNotEmpty()) Messages.showErrorDialog(project, "Directories of \"${preset.name}\" no longer exist:\n${missing.joinToString("\n")}", "Cannot Start Session")
    return missing.isEmpty()
}

/** A model and effort pair as its popup lists it: `Claude Opus · High`. */
fun variantLabel(variant: Variant) =
    "${variant.model?.let(::modelLabel) ?: OWN_SETTING} · ${variant.effort?.replaceFirstChar(Char::uppercase) ?: OWN_SETTING}"

/** What a preset sets, for its tooltip. */
fun Preset.summary() = listOfNotNull(
    model?.let { "model $it" },
    effort?.let { "effort $it" },
    variants.takeIf { it.isNotEmpty() }?.joinToString(", ", "picks from ") { variantLabel(it) },
    project ?: "the project's directory",
    if (!worktree) null else "worktree off ${base ?: "a branch picked at start"}",
    addDirs.takeIf { it.isNotEmpty() }?.joinToString(", ", "also ") { it },
    promptHead()?.let { "starts with \"$it\"" },
    "opens the composer".takeIf { askMore },
).joinToString("; ")

private fun presetIcon(preset: Preset) = AvatarIcon(16, 0.3, preset.name, preset.name, AvatarPalette)

/** Starts [preset], which has no pairs left to pick from, or opens the composer on it with [Preset.askMore]. */
private fun start(project: Project, preset: Preset, where: RelativePoint) {
    require(preset.variants.isEmpty()) { "preset ${preset.name} still has pairs to pick from" }
    if (preset.askMore) showPromptPopup(project, preset) else startPreset(project, preset, where, preset.prompt)
}

/** A configuration's button: starts it, or with model and effort pairs lists them under the button, the pick starting it. */
class PresetAction(private val preset: Preset) : DumbAwareAction(preset.name, preset.summary(), presetIcon(preset)) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        if (preset.variants.isEmpty()) return start(e.project!!, preset, popupPoint(e))
        JBPopupFactory.getInstance().createActionGroupPopup(
            preset.name, DefaultActionGroup(variantActions(preset)), e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        ).show(popupPoint(e))
    }
}

/** One of [preset]'s model and effort pairs, starting it with that pair. */
private class VariantAction(private val preset: Preset, private val variant: Variant) : DumbAwareAction(variantLabel(variant)) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) = start(e.project!!, preset.withVariant(variant), popupPoint(e))
}

private fun variantActions(preset: Preset): List<AnAction> = preset.variants.map { VariantAction(preset, it) }

/** A configuration in the dropdown: its action, or with model and effort pairs a submenu of them. */
private fun dropdownEntry(preset: Preset): AnAction = if (preset.variants.isEmpty()) PresetAction(preset)
else DefaultActionGroup(preset.name, variantActions(preset)).apply {
    isPopup = true
    templatePresentation.icon = presetIcon(preset)
    templatePresentation.description = preset.summary()
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
        dropdown.addAll(presets.map(::dropdownEntry))
        if (presets.isNotEmpty()) dropdown.add(Separator.getInstance())
        dropdown.add(edit)
        return (presets.filter { it.pinned }.map(::PresetAction) + dropdown).toTypedArray()
    }
}

/** On a session's menu: a new session started as the [selected] one runs ([presetOf]). */
class SameSettingsAction(private val selected: () -> List<ClaudeSession>) :
    DumbAwareAction("New Session with Same Settings", "Same model, effort, project, worktree and added directories", NEW_SESSION_ICON) {
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
