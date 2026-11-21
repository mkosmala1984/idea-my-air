package dev.myair.actions

import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.SessionTerminals
import dev.myair.planview.shownSessionFiles
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Profile
import dev.myair.store.ProfileStore
import dev.myair.store.createProfileDirs
import dev.myair.store.ideServer
import dev.myair.store.mcpAddCommand
import dev.myair.store.parseProfiles
import dev.myair.store.profilesJson
import dev.myair.store.removeRefusal
import java.nio.file.Path
import javax.swing.JList

/** The profiles list of Settings › Tools › My Air: Add and Remove act at once on `profiles.json`; a profile's directory is never edited. */
class ProfilesEditor {
    private val store = ProfileStore.getInstance()
    private val model = CollectionListModel<Profile>()
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<Profile>() {
            override fun customizeCellRenderer(list: JList<out Profile>, value: Profile, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.name)
                append("  —  ${value.dir}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    val component = ToolbarDecorator.createDecorator(list)
        .setAddAction { add() }
        .setRemoveAction { remove() }
        .setRemoveActionUpdater { list.selectedValue?.isDefault == false }
        .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Open Folder", null, com.intellij.icons.AllIcons.Nodes.Folder) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                RevealFileAction.openDirectory(checkNotNull(list.selectedValue) { "no profile selected" }.dir)
            }
            override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                e.presentation.isEnabled = list.selectedValue != null
            }
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })
        .createPanel()

    /** Rereads `profiles.json`; throws when it is broken. */
    fun reload() = model.replaceAll(store.all())

    private val Profile.isDefault get() = this == store.default

    private fun add() {
        val project = requireNotNull(CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(list))) { "Settings opened without a project" }
        val profile = NewProfileDialog(store.all().drop(1), store.defaultDir).profile() ?: return
        createProfileDirs(profile)
        store.write(store.all().drop(1) + profile)
        reload()
        ClaudeSessionsService.getInstance().refresh()
        ClaudeSessionOpener.startNew(project, profile = profile)
        val server = ideServer(store.defaultDir.resolveSibling(".claude.json"))
        if (server == null) Messages.showInfoMessage(project, "No http MCP server named idea in ~/.claude.json", "Profile ${profile.name}")
        else McpCommandDialog(mcpAddCommand(profile, server)).show()
    }

    private fun remove() {
        val profile = list.selectedValue ?: return
        val tabs = ProjectManager.getInstance().openProjects.flatMap { shownSessionFiles(FileEditorManager.getInstance(it)) } +
            SessionTerminals.getInstance().detached().map { it.second }
        val refusal = removeRefusal(profile.name, ClaudeSessionsService.getInstance().live.values, tabs.map { it.profile.name }, emptyList(), emptyList())
        if (refusal != null) return Messages.showErrorDialog(refusal, "Cannot Remove Profile")
        store.write(store.all().drop(1) - profile)
        reload()
        ClaudeSessionsService.getInstance().refresh()
    }
}

private class NewProfileDialog(private val taken: List<Profile>, private val defaultDir: Path) : DialogWrapper(true) {
    private var name = ""
    private var dir = ""
    private val dirField = JBTextField()

    init {
        title = "New Claude Profile"
        init()
    }

    override fun createCenterPanel() = panel {
        row("Name:") {
            textField().bindText(::name).focused().applyToComponent {
                document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) {
                        dirField.text = Path.of(System.getProperty("user.home"), ".my-air", "claude-profiles", text.trim()).toString()
                    }
                })
            }
        }
        row("Directory:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Profile Directory"))
                .bindText(::dir).align(AlignX.FILL).applyToComponent { dirField.document = textField.document }
                .validationOnApply {
                    runCatching { parseProfiles(profilesJson(taken + Profile(name.trim(), Path.of(it.text.trim()))), defaultDir) }
                        .exceptionOrNull()?.let { e -> error(e.message!!) }
                }
        }
    }

    fun profile(): Profile? = if (showAndGet()) Profile(name.trim(), Path.of(dir.trim())) else null
}

private class McpCommandDialog(private val command: String) : DialogWrapper(true) {
    init {
        title = "Add the IDE's MCP Server to the Profile"
        init()
    }

    override fun createCenterPanel() = panel {
        row("Log in through the tab that opened, then run this in PowerShell:") {}
        row { textField().applyToComponent { text = command; isEditable = false; caretPosition = 0 }.align(AlignX.FILL) }
    }
}
