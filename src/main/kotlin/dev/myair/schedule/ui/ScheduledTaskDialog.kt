package dev.myair.schedule.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.myair.actions.MODELS
import dev.myair.actions.OWN_SETTING
import dev.myair.schedule.Schedule
import dev.myair.schedule.ScheduledTask
import dev.myair.store.DEFAULT_PROFILE
import dev.myair.store.EFFORTS
import dev.myair.store.ProfileStore
import dev.myair.store.Preset
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private const val NO_FOLDER = ""

/**
 * Edits [task], or a new one when null. [folders] are the folders a run may be filed in, as (id, path); [takenNames]
 * the other tasks' names.
 */
class ScheduledTaskDialog(private val task: ScheduledTask?, private val folders: List<Pair<String, String>>, private val takenNames: Set<String>) :
    DialogWrapper(true) {
    private var name = task?.name.orEmpty()
    private var prompt = task?.preset?.prompt.orEmpty()
    private var project = task?.preset?.project.orEmpty()
    private var addDirs = task?.preset?.addDirs.orEmpty().joinToString("\n")
    private var model: String? = task?.preset?.model.orEmpty()
    private var effort: String? = task?.preset?.effort.orEmpty()
    private var profile: String = task?.preset?.profile ?: DEFAULT_PROFILE
    private var time = task?.schedule?.time?.toString() ?: "08:00"
    private var allowedTools = task?.allowedTools.orEmpty().joinToString("\n")
    private var folder: String? = task?.folder?.let { id -> folders.firstOrNull { it.first == id }?.second } ?: NO_FOLDER
    private var enabled = task?.enabled ?: true
    private val days = DayOfWeek.entries.associateWith { JBCheckBox(it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), task?.schedule?.days?.contains(it) ?: (it.value <= 5)) }

    init {
        title = if (task == null) "New Scheduled Task" else "Edit Scheduled Task"
        init()
    }

    override fun createCenterPanel() = panel {
        row("Name:") {
            textField().bindText(::name).focused().validationOnApply {
                when (it.text.trim()) {
                    "" -> error("Enter a name")
                    in takenNames -> error("Another task has this name")
                    else -> null
                }
            }
        }
        row("Prompt:") {
            textArea().bindText(::prompt).rows(5).align(AlignX.FILL).validationOnApply { if (it.text.isBlank()) error("Enter the prompt") else null }
        }
        row("Days:") { days.values.forEach { cell(it) } }
        row("Time:") {
            textField().bindText(::time).columns(5).comment("HH:mm, local time.").validationOnApply {
                when {
                    days.values.none { box -> box.isSelected } -> error("Choose at least one day")
                    parseTime(it.text) == null -> error("Enter the time as HH:mm")
                    else -> null
                }
            }
        }
        row("Directory:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Directory"))
                .bindText(::project).align(AlignX.FILL).comment("Empty: the directory of the first open IDE project.")
        }
        row("Other directories:") {
            textArea().bindText(::addDirs).rows(2).align(AlignX.FILL).comment("One per line, each passed to --add-dir.")
        }
        row("Model:") {
            comboBox((listOf("") + MODELS + listOfNotNull(model)).distinct(), textListCellRenderer { it?.ifEmpty { OWN_SETTING } })
                .applyToComponent { isEditable = true }.bindItem(::model)
        }
        row("Effort:") {
            comboBox(listOf("") + EFFORTS, textListCellRenderer { it?.ifEmpty { OWN_SETTING } }).bindItem(::effort)
        }
        row("Profile:") {
            comboBox(ProfileStore.getInstance().all().map { it.name }).bindItem({ profile }, { profile = it!! })
        }
        row("Allowed tools:") {
            textArea().bindText(::allowedTools).rows(3).align(AlignX.FILL)
                .comment("One per line, passed to --allowedTools, e.g. Read or Bash(git log:*). Others ask, and the run waits for you.")
        }
        row("Folder:") {
            comboBox(listOf(NO_FOLDER) + folders.map { it.second }, textListCellRenderer { it?.ifEmpty { "where its project's sessions show" } }).bindItem(::folder)
        }
        row { checkBox("Enabled").bindSelected(::enabled) }
    }

    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.trim()).takeIf { it.second == 0 && it.nano == 0 }
    } catch (_: DateTimeParseException) {
        null
    }

    /** The edited task, `since` kept from the old one (or now for a new one) for [dev.myair.schedule.edited] to settle; null when cancelled. */
    fun task(): ScheduledTask? {
        if (!showAndGet()) return null
        fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return ScheduledTask(
            task?.id ?: UUID.randomUUID().toString(),
            Preset(
                name.trim(), model?.trim()?.takeIf { it.isNotEmpty() }, effort?.takeIf { it.isNotEmpty() },
                project.trim().takeIf { it.isNotEmpty() }, addDirs = lines(addDirs), prompt = prompt.trim(),
                profile = profile.takeIf { it != DEFAULT_PROFILE },
            ),
            Schedule(days.filterValues { it.isSelected }.keys, checkNotNull(parseTime(time))),
            enabled,
            lines(allowedTools),
            folders.firstOrNull { it.second == folder }?.first,
            task?.since ?: Instant.now(),
        )
    }
}
