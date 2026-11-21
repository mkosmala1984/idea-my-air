package dev.myair.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.SessionTerminals
import dev.myair.planview.PlanFiles
import dev.myair.planview.shownSessionFiles
import dev.myair.review.ReviewService
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Availability
import dev.myair.store.CopyItem
import dev.myair.store.Profile
import dev.myair.store.ProfileStore
import dev.myair.store.ReviewProfileStore
import dev.myair.store.copyAvailability
import dev.myair.store.copyFromDefault
import dev.myair.store.createProfileDirs
import dev.myair.store.ideServer
import dev.myair.store.mcpAddCommand
import dev.myair.store.parseProfiles
import dev.myair.store.profilesJson
import dev.myair.store.removeRefusal
import com.intellij.util.ui.UIUtil
import java.nio.file.Path
import javax.swing.JList
import javax.swing.event.DocumentEvent

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
        val (profile, selection) = NewProfileDialog(store.all().drop(1), store.defaultDir).showAndGetProfile() ?: return
        val copied = selection.sortedBy { it.ordinal }.map { it.name }
        // Modal and not cancellable: Settings stays blocked until the profile is registered or everything copied is removed.
        object : Task.Modal(project, "Creating Profile ${profile.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                copyFromDefault(store.defaultDir, profile.dir, selection) {
                    createProfileDirs(profile)
                    store.write(store.all().drop(1) + profile)
                }
            }

            override fun onSuccess() {
                thisLogger().info("Created profile ${profile.name}, copied from default: $copied")
                reload()
                ClaudeSessionsService.getInstance().refresh()
                ClaudeSessionOpener.startNew(project, profile = profile)
                val server = ideServer(store.defaultDir.resolveSibling(".claude.json"))
                if (server == null) Messages.showInfoMessage(project, "No http MCP server named idea in ~/.claude.json", "Profile ${profile.name}")
                else McpCommandDialog(mcpAddCommand(profile, server)).show()
            }

            override fun onThrowable(error: Throwable) {
                thisLogger().warn("Profile ${profile.name} not created (copying $copied): ${error.message}")
                Messages.showErrorDialog(project, error.message ?: error.toString(), "Cannot Create Profile ${profile.name}")
            }
        }.queue()
    }

    private fun remove() {
        val profile = list.selectedValue ?: return
        val tabs = ProjectManager.getInstance().openProjects.flatMap { shownSessionFiles(FileEditorManager.getInstance(it)) } +
            SessionTerminals.getInstance().detached().map { it.second }
        val managers = PlanFiles.getInstance().store.read().byFolder.values + ReviewService.getInstance().reviews.values.mapNotNull { it.getOrThrow().manager }
        val reviewerProfiles = ReviewProfileStore.INSTANCE.read().getOrThrow().flatMap { p -> p.reviewers.mapNotNull { it.profile } }
        val refusal = removeRefusal(profile.name, ClaudeSessionsService.getInstance().live.values, tabs.map { it.profile.name }, managers.map { it.profile }, reviewerProfiles)
        if (refusal != null) return Messages.showErrorDialog(refusal, "Cannot Remove Profile")
        store.write(store.all().drop(1) - profile)
        reload()
        ClaudeSessionsService.getInstance().refresh()
    }
}

/** What [NewProfileDialog] returns: the profile and the items to copy once from the default profile (ticked and available). */
private data class NewProfile(val profile: Profile, val selection: Set<CopyItem>)

private class NewProfileDialog(private val taken: List<Profile>, private val defaultDir: Path) : DialogWrapper(true) {
    private val nameField = JBTextField()
    private lateinit var dirField: TextFieldWithBrowseButton
    private var dirEdited = false
    private var prefilling = false
    private val boxes = CopyItem.entries.associateWith { JBCheckBox(it.label, it.tickedByDefault).apply { isEnabled = false } }
    private val reasons = CopyItem.entries.associateWith { JBLabel("checking...").apply { foreground = UIUtil.getContextHelpForeground() } }
    private var availability: Map<CopyItem, Availability> = emptyMap()

    init {
        title = "New Claude Profile"
        init()
        // Read once off the EDT; the default profile does not change while the dialog is open.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { copyAvailability(defaultDir) }
            ApplicationManager.getApplication().invokeLater({ if (!isDisposed) showAvailability(result) }, ModalityState.any())
        }
    }

    private fun showAvailability(result: Result<Map<CopyItem, Availability>>) {
        availability = result.getOrElse { e -> CopyItem.entries.associateWith { Availability.Unavailable(e.message ?: e.toString(), true) } }
        for ((item, a) in availability) {
            val box = boxes.getValue(item)
            val reason = reasons.getValue(item)
            when (a) {
                Availability.Available -> {
                    box.isEnabled = true
                    reason.text = ""
                }
                is Availability.Unavailable -> {
                    box.isEnabled = false
                    box.isSelected = false
                    reason.text = a.reason
                    reason.foreground = if (a.error) JBColor.RED else UIUtil.getContextHelpForeground()
                }
            }
        }
    }

    private fun selection() = CopyItem.entries.filter { boxes.getValue(it).isSelected && availability[it] == Availability.Available }.toSet()

    override fun createCenterPanel() = panel {
        row("Name:") {
            cell(nameField).focused().columns(COLUMNS_MEDIUM).validationOnApply { if (it.text.isBlank()) error("Enter a name") else null }
        }
        row("Directory:") {
            dirField = textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Profile Directory"))
                .align(AlignX.FILL)
                .validationOnApply {
                    runCatching { parseProfiles(profilesJson(taken + profile()), defaultDir) }.exceptionOrNull()?.let { e -> error(e.message!!) }
                        ?: insideDefault()?.let { error(it) }
                }.component
        }
        group("Copy Once from the Default Profile (Later Changes Are Not Synced)") {
            CopyItem.entries.groupBy { it.group }.forEach { (group, items) ->
                row { label(group).bold() }
                indent {
                    items.forEach { item -> row { cell(boxes.getValue(item)).comment(item.effect); cell(reasons.getValue(item)) } }
                }
            }
            row {
                comment(
                    "Sign in through the tab that opens, with the same Claude account as the default profile. " +
                        "A status line command is copied as it is, so the scripts it runs stay in the default profile."
                )
            }
        }
    }.also {
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val name = nameField.text.trim()
                if (dirEdited || name.isEmpty()) return
                val dir = runCatching { Path.of(System.getProperty("user.home"), ".my-air", "claude-profiles", name).toString() }.getOrNull() ?: return
                prefilling = true
                dirField.text = dir
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

    /** Why the directory may not be used: inside the default profile, a walk of `skills/` could reach its own target. A path comparison only. */
    private fun insideDefault(): String? {
        val dir = runCatching { profile().dir.toAbsolutePath().normalize() }.getOrNull() ?: return null
        return if (dir.startsWith(defaultDir.toAbsolutePath().normalize())) "The directory lies in the default profile's $defaultDir" else null
    }

    fun showAndGetProfile(): NewProfile? = if (showAndGet()) NewProfile(profile(), selection()) else null
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
