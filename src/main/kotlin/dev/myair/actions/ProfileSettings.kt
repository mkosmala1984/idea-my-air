package dev.myair.actions

import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import javax.swing.event.DocumentEvent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
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
        .setAddActionUpdater { project() != null }
        .setRemoveAction { remove() }
        .setRemoveActionUpdater { list.selectedValue?.isDefault == false }
        .addExtraAction(object : AnAction("Open Folder", null, AllIcons.Nodes.Folder) {
            override fun actionPerformed(e: AnActionEvent) {
                RevealFileAction.openDirectory(checkNotNull(list.selectedValue) { "no profile selected" }.dir)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = list.selectedValue != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        .createPanel()

    /** Rereads `profiles.json`; throws when it is broken. */
    fun reload() = model.replaceAll(store.all())

    private val Profile.isDefault get() = this == store.default

    private fun project() = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(list))

    private fun add() {
        val project = checkNotNull(project()) { "Settings opened without a project" }
        val profile = NewProfileDialog(store.all().drop(1), store.defaultDir).profile(true) ?: return
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
    private val nameField = JBTextField()
    private lateinit var dirField: TextFieldWithBrowseButton
    private var dirEdited = false
    private var prefilling = false

    init {
        title = "New Claude Profile"
        init()
    }

    override fun createCenterPanel() = panel {
        row("Name:") {
            cell(nameField).focused().columns(COLUMNS_MEDIUM).validationOnApply { if (it.text.isBlank()) error("Enter a name") else null }
        }
        row("Directory:") {
            dirField = textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Profile Directory"))
                .align(AlignX.FILL)
                .validationOnApply {
                    runCatching { parseProfiles(profilesJson(taken + profile()), defaultDir) }.exceptionOrNull()?.let { e -> error(e.message!!) }
                }.component
        }
    }.also {
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val name = nameField.text.trim()
                if (dirEdited || name.isEmpty() || runCatching { Path.of(name) }.isFailure) return
                prefilling = true
                dirField.text = Path.of(System.getProperty("user.home"), ".my-air", "claude-profiles", name).toString()
                prefilling = false
            }
        })
        dirField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!prefilling) dirEdited = true
            }
        })
    }

    private fun profile() = Profile(nameField.text.trim(), Path.of(dirField.text.trim()))

    fun profile(show: Boolean): Profile? = if (showAndGet()) profile() else null
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
