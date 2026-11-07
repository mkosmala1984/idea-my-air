package dev.myair

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.components.JBCheckBox
import dev.myair.editor.ClaudeSessionEditor
import com.intellij.ui.dsl.builder.bindText
import dev.myair.actions.PresetsEditor
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Preset
import dev.myair.store.PresetStore
import com.intellij.ui.JBColor
import javax.swing.JLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty

/** Settings of this IDE, shared by all its projects. */
@Service
@State(name = "MyAirSettings", storages = [Storage("myAir.xml")])
class MyAirSettings : SimplePersistentStateComponent<MyAirSettings.Options>(Options()) {
    class Options : BaseState() {
        /** Where new worktrees go; a relative path resolves against the root of the repository's main checkout. */
        var worktreesDir by string(".claude/worktrees")

        /** Offer `my_air_set_brief` to agents and show the briefs they write. */
        var briefs by property(true)

        /** Let agents name their session's folder and title in `my_air_set_brief`; only while [briefs] is on. */
        var fileSessions by property(true)

        /** Width of the terminal in session tabs, centered in a wider tab; 0 fills the tab. */
        var terminalColumns by property(0)

        /** A session tab that closes leaves its `claude` running until it is idle ([dev.myair.editor.SessionTerminals]). */
        var keepDetached by property(true)

        /** The tools Haiku may call to read a Jira issue when it names a branch, separated by spaces. */
        var jiraTools by string("mcp__atlassian__getJiraIssue mcp__atlassian__getAccessibleAtlassianResources")
    }

    val jiraTools: List<String> get() = state.jiraTools.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val worktreesDir: String get() = checkNotNull(state.worktreesDir) { "worktrees directory is not set" }

    val briefs: Boolean get() = state.briefs

    val fileSessions: Boolean get() = state.briefs && state.fileSessions

    val terminalColumns: Int get() = state.terminalColumns

    val keepDetached: Boolean get() = state.keepDetached

    companion object {
        fun getInstance(): MyAirSettings = service()
    }
}

/** Settings › Tools › My Air. */
class MyAirConfigurable : BoundConfigurable("My Air") {
    override fun createPanel() = panel {
        row("Worktrees directory:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Worktrees Directory"))
                .bindText(MyAirSettings.getInstance().state::worktreesDir.toNonNullableProperty(""))
                .columns(COLUMNS_LARGE)
                .validationOnApply { if (it.text.isBlank()) error("Enter a directory") else null }
                .comment("New Claude Session in Worktree creates &lt;directory&gt;/&lt;id&gt;. A relative path resolves against the root of the repository's main checkout.")
        }
        lateinit var briefs: Cell<JBCheckBox>
        row {
            briefs = checkBox("Session briefs: let agents describe their goal and tasks (my_air_set_brief) and show them")
                .bindSelected(MyAirSettings.getInstance().state::briefs)
                .comment("Briefs show or hide at once. The IDE's MCP server lists its tools when the IDE starts, so the tool itself appears or goes away after a restart.")
        }
        row {
            checkBox("Agents file and title their sessions (folder and title in my_air_set_brief)")
                .bindSelected(MyAirSettings.getInstance().state::fileSessions)
                .enabledIf(briefs.selected)
                .comment("An agent may create a folder one level below an existing one; undo it in the folder history. Your own filing and /rename win. Sessions already placed stay when this is turned off.")
        }
        row("Session tab width:") {
            spinner(0..1000).bindIntValue(MyAirSettings.getInstance().state::terminalColumns)
                .comment("Columns of the claude terminal, centered in a wider tab. 0 fills the tab.")
        }
        row("Jira tools for branch names:") {
            textField().bindText(MyAirSettings.getInstance().state::jiraTools.toNonNullableProperty("")).columns(COLUMNS_LARGE)
                .comment("MCP tools Haiku may call to read the Jira issue a task names when it suggests a branch name, separated by spaces.")
        }
        val presets = PresetsEditor()
        // What the file held at reset; null when it is broken, which leaves it to be fixed by hand.
        var saved: List<Preset>? = null
        lateinit var broken: JLabel
        group("Session Configurations") {
            row { broken = label("").applyToComponent { foreground = JBColor.RED }.component }
            row {
                cell(presets.component).align(Align.FILL)
                    .comment("Started from the toolbar of Claude Sessions. Kept in ${PresetStore.INSTANCE.file}, shared by every IDE.")
            }.resizableRow()
        }
        onReset {
            val read = PresetStore.INSTANCE.read()
            saved = read.getOrNull()
            presets.presets = saved.orEmpty()
            presets.component.isVisible = saved != null
            broken.text = read.exceptionOrNull()?.let { "Fix ${PresetStore.INSTANCE.file} by hand: ${it.message}" }.orEmpty()
        }
        onIsModified { saved != null && presets.presets != saved }
        onApply {
            if (saved != null) {
                PresetStore.INSTANCE.write(presets.presets)
                saved = presets.presets
            }
        }
        onApply { ClaudeSessionsService.getInstance().refresh() }
        onApply {
            ProjectManager.getInstance().openProjects.flatMap { FileEditorManager.getInstance(it).allEditors.asList() }
                .filterIsInstance<ClaudeSessionEditor>().forEach { it.component.revalidate() }
        }
    }
}
