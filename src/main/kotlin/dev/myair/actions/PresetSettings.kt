package dev.myair.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.enteredTextSatisfies
import com.intellij.util.ui.AvatarIcon
import com.intellij.util.ui.AvatarPalette
import com.intellij.util.ui.UIUtil
import dev.myair.editor.projectBase
import dev.myair.service.LatestRead
import com.intellij.util.concurrency.AppExecutorUtil
import dev.myair.prompt.profileLabel
import dev.myair.store.DEFAULT_PROFILE
import dev.myair.store.EFFORTS
import dev.myair.store.ProfileStore
import dev.myair.store.Preset
import dev.myair.store.parseVariants
import dev.myair.store.variantsText
import java.awt.event.MouseEvent
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.event.DocumentEvent

/** The presets list of Settings › Tools › My Air: add, edit, remove and reorder, each edit in a [PresetDialog]. */
class PresetsEditor {
    private val model = CollectionListModel<Preset>()
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<Preset>() {
            override fun customizeCellRenderer(list: JList<out Preset>, value: Preset, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AvatarIcon(16, 0.3, value.name, value.name, AvatarPalette)
                append(value.name)
                append("  ${value.summary()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    val component = ToolbarDecorator.createDecorator(list)
        .setAddAction { PresetDialog(null, names(), "New Session Configuration", ideBase()).preset()?.let(model::add) }
        .setEditAction { edit() }
        .createPanel()

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                edit()
                return true
            }
        }.installOn(list)
    }

    var presets: List<Preset>
        get() = model.items
        set(value) = model.replaceAll(value)

    private fun names() = model.items.map { it.name }.toSet()

    /** The settings are the application's; the window they were opened from gives the project an empty project field means. */
    private fun ideBase() = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(list))?.let(::projectBase)

    private fun edit() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val current = model.getElementAt(index)
        PresetDialog(current, names() - current.name, "Edit Session Configuration", ideBase()).preset()?.let { model.setElementAt(it, index) }
    }
}

/** The profile names a combo offers for [stored]: the listed ones, then [stored] when it is no longer listed. */
internal fun profileChoices(listed: List<String>, stored: String) = if (stored in listed) listed else listed + stored

/**
 * A profile combo bound to [prop]: a stored name no longer listed stays chosen, shown as missing, and the dialog's OK is
 * refused until a listed one is chosen, so it is never saved as `default` unasked.
 */
fun Row.profileComboBox(prop: KMutableProperty0<String>) {
    val listed = ProfileStore.getInstance().all().map { it.name }
    comboBox(profileChoices(listed, prop.get()), textListCellRenderer { it?.let { name -> if (name in listed) profileLabel(name) else "$name (missing)" } })
        .bindItem({ prop.get() }, { prop.set(checkNotNull(it) { "no profile chosen" }) })
        .validationOnApply { if (it.selectedItem in listed) null else error("Profile ${it.selectedItem} is no longer listed; choose another") }
}

private enum class WorktreeMode { NONE, ASK, FIXED }

/** The aliases `claude --help` names for `--model`. */
internal val MODELS = listOf("fable", "opus", "sonnet")
internal const val OWN_SETTING = "claude's own setting"

/**
 * Edits [preset], or a new one when null, under [name]; [takenNames] are the other presets'. [ideBase] is the directory an
 * empty project field stands for, null when no project window is at hand.
 */
class PresetDialog(
    preset: Preset?,
    private val takenNames: Set<String>,
    dialogTitle: String,
    private val ideBase: Path?,
    private var name: String = preset?.name.orEmpty(),
) : DialogWrapper(true) {
    private var model: String? = preset?.model.orEmpty()
    private var effort: String? = preset?.effort.orEmpty()
    private var profile: String = preset?.profile ?: DEFAULT_PROFILE
    private var project = preset?.project.orEmpty()
    private var worktree = when {
        preset?.worktree != true -> WorktreeMode.NONE
        preset.base == null -> WorktreeMode.ASK
        else -> WorktreeMode.FIXED
    }
    private var base: String? = preset?.base
    private val branches = CollectionComboBoxModel(listOfNotNull(preset?.base).toMutableList())
    private lateinit var branchStatus: JLabel

    private val branchReads = LatestRead.onPool<Path, GitRepo>(
        AppExecutorUtil.getAppExecutorService(), { ModalityState.stateForComponent(contentPane) }, Git::repo,
    ) { _, repo -> showBranches(repo) }
    private var addDirs = preset?.addDirs.orEmpty().joinToString("\n")
    private var pinned = preset?.pinned ?: false
    private var prompt = preset?.prompt.orEmpty()
    private var askMore = preset?.askMore ?: false
    private var variants = variantsText(preset?.variants.orEmpty())
    private val variantsArea = JBTextArea()

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel() = panel {
        row("Name:") {
            textField().bindText(::name).focused().validationOnApply {
                when (it.text.trim()) {
                    "" -> error("Enter a name")
                    in takenNames -> error("Another configuration has this name")
                    else -> null
                }
            }
        }
        row("Model:") {
            comboBox((listOf("") + MODELS + listOfNotNull(model)).distinct(), textListCellRenderer { it?.ifEmpty { OWN_SETTING } })
                .applyToComponent { isEditable = true }
                .bindItem(::model)
                .comment("An alias, or a model's full name typed in.")
        }.enabledIf(noPairs)
        row("Effort:") {
            comboBox(listOf("") + EFFORTS, textListCellRenderer { it?.ifEmpty { OWN_SETTING } }).bindItem(::effort)
        }.enabledIf(noPairs)
        row("Profile:") {
            profileComboBox(::profile)
        }
        row("Or pick from:") {
            cell(variantsArea).bindText(::variants).rows(3).align(AlignX.FILL)
                .comment("Model and effort pairs, one per line, e.g. \"opus high\" or \"- low\"; at least two, listed at the click.")
                .validationOnApply { area -> runCatching { Preset("check", variants = parseVariants(area.text)) }.exceptionOrNull()?.let { error(it.message!!) } }
        }
        row("Project:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Project Directory"))
                .bindText(::project).align(AlignX.FILL).comment("Empty: the directory of the IDE project the session starts from.")
                .applyToComponent {
                    textField.document.addDocumentListener(object : DocumentAdapter() {
                        override fun textChanged(e: DocumentEvent) = loadBranches(text)
                    })
                }
        }
        buttonsGroup("Worktree:") {
            row { radioButton("None", WorktreeMode.NONE) }
            row { radioButton("New worktree, base branch picked at start", WorktreeMode.ASK) }
            row {
                val fixed = radioButton("New worktree off branch:", WorktreeMode.FIXED)
                comboBox(branches).bindItem(::base).enabledIf(fixed.selected).validationOnApply {
                    if (fixed.component.isSelected && it.selectedItem == null) error("Choose a branch") else null
                }
            }
            row { branchStatus = label("").applyToComponent { foreground = UIUtil.getContextHelpForeground() }.component }
        }.bind(::worktree)
        row("Other directories:") {
            textArea().bindText(::addDirs).rows(3).align(AlignX.FILL).comment("One per line, each passed to --add-dir.")
        }
        row("Initial prompt:") {
            textArea().bindText(::prompt).rows(3).align(AlignX.FILL).comment("The session's first message, e.g. /code-review.")
        }
        row { checkBox("Open the composer to add to it before the start").bindSelected(::askMore) }
        row { checkBox("Own button on the toolbar").bindSelected(::pinned) }
    }.also { loadBranches(project) }

    private val noPairs get() = variantsArea.enteredTextSatisfies { it.isBlank() }

    /** Fills the branch list from the repository of [projectText], or of [ideBase] when it is empty, off the EDT. */
    private fun loadBranches(projectText: String) {
        val dir = projectText.trim().takeIf { it.isNotEmpty() }?.let(Path::of) ?: ideBase
        if (dir == null) {
            branchReads.forget()
            return showBranches(Result.failure(IllegalStateException("Choose a project to list its branches.")))
        }
        branchStatus.text = "Reading branches of $dir..."
        branchReads.request(dir)
    }

    /** The branches of [repo], keeping the chosen one when it is among them, else the default; a failure empties the list. */
    private fun showBranches(repo: Result<GitRepo>) {
        val chosen = branches.selected
        branches.replaceAll(repo.getOrNull()?.branches.orEmpty())
        branches.selectedItem = repo.getOrNull()?.let { if (chosen in it.branches) chosen else it.default }
        branchStatus.text = repo.exceptionOrNull()?.message.orEmpty()
    }

    /** The edited preset, or null when cancelled. */
    fun preset(): Preset? {
        if (!showAndGet()) return null
        val pairs = parseVariants(variants)
        return Preset(
            name.trim(),
            model?.trim()?.takeIf { it.isNotEmpty() && pairs.isEmpty() },
            effort?.takeIf { it.isNotEmpty() && pairs.isEmpty() },
            project.trim().takeIf { it.isNotEmpty() },
            worktree != WorktreeMode.NONE,
            base.takeIf { worktree == WorktreeMode.FIXED },
            addDirs.lines().map { it.trim() }.filter { it.isNotEmpty() },
            pinned,
            prompt.trim().ifEmpty { null },
            askMore,
            pairs,
            profile.takeIf { it != DEFAULT_PROFILE },
        )
    }
}
