package dev.myair.schedule.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.myair.editor.ClaudeSessionOpener
import dev.myair.editor.sessionIcon
import dev.myair.schedule.RunRecord
import dev.myair.schedule.RunRecords
import dev.myair.schedule.ScheduledRuns
import dev.myair.schedule.ScheduledTask
import dev.myair.schedule.ScheduledTaskStore
import dev.myair.schedule.edited
import dev.myair.service.ClaudeSessionsService
import java.beans.PropertyChangeListener
import java.time.Instant
import java.time.ZonedDateTime
import javax.swing.JComponent
import javax.swing.Timer

class ScheduledTasksEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ScheduledTasksVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ScheduledTasksEditor(project, file as ScheduledTasksVirtualFile)
    override fun getEditorTypeId() = "myair-scheduled"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The registry of scheduled tasks: each task's schedule, next slot and latest run, its actions, and its runs. Read off
 * the EDT on every service change and every 30 s, since a run's record changes outside the service's view.
 */
class ScheduledTasksEditor(private val project: Project, private val file: ScheduledTasksVirtualFile) : UserDataHolderBase(), FileEditor {
    private val service = ClaudeSessionsService.getInstance()
    private val store = ScheduledTaskStore.INSTANCE
    private val scroll = JBScrollPane().apply { border = JBUI.Borders.empty() }
    private val timer = Timer(30_000) { reload() }

    /** What the tab shows: the tasks or why the file cannot be read, and each task's runs, newest first. */
    private data class View(val tasks: Result<List<ScheduledTask>>, val runs: Map<String, List<RunRecord>>)

    init {
        service.subscribe(this) { reload() }
        timer.start()
        reload()
    }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val tasks = store.read()
            val view = View(tasks, tasks.getOrNull().orEmpty().associate { it.id to RunRecords.INSTANCE.of(it.id) })
            ApplicationManager.getApplication().invokeLater({ show(view) }, ModalityState.any()) { project.isDisposed }
        }
    }

    private fun show(view: View) {
        val now = ZonedDateTime.now()
        val position = scroll.verticalScrollBar.value
        scroll.setViewportView(panel {
            row {
                label("Scheduled Tasks").bold()
                link("+ Add task") { add(view) }
            }
            val tasks = view.tasks.getOrElse {
                row { label("Cannot read ${store.file}: ${it.message}. Nothing runs until it is fixed.").applyToComponent { foreground = JBColor.RED } }
                return@panel
            }
            if (tasks.isEmpty()) row { comment("No scheduled tasks yet.") }
            for (task in tasks) {
                val runs = view.runs[task.id].orEmpty()
                val line = taskRow(task, runs, now)
                separator()
                row {
                    label(line.name).bold().applyToComponent { if (line.failed) foreground = JBColor.RED }
                    comment("${line.schedule} · next: ${line.next}")
                }
                row {
                    label(line.last ?: "never ran").applyToComponent { if (line.failed) foreground = JBColor.RED }
                }
                row {
                    link("Run now") { runNow(task) }
                    link("Edit") { edit(view, task) }
                    link(if (task.enabled) "Disable" else "Enable") { save { it.map { t -> if (t.id == task.id) edited(t, t.copy(enabled = !t.enabled), Instant.now()) else t } } }
                    link("Delete") { delete(view, task) }
                }
                if (runs.isNotEmpty()) collapsibleGroup("Runs (${runs.size})") {
                    for (run in runs) row {
                        val session = service.sessions.firstOrNull { it.id == run.session }
                        comment(runText(run, now))
                        if (session != null) {
                            icon(sessionIcon(service.live[session.id]?.status, session.id in service.unread))
                            link(session.title) { ClaudeSessionOpener.open(project, session) }
                        }
                    }
                }
            }
        }.apply { border = JBUI.Borders.empty(12, 16) })
        scroll.verticalScrollBar.value = position
    }

    private fun runNow(task: ScheduledTask) {
        ScheduledRuns.getInstance().runNow(task.id)?.let { Messages.showWarningDialog(project, it, "\"${task.name}\" did not start") }
        reload()
    }

    private fun add(view: View) {
        val tasks = view.tasks.getOrNull() ?: return
        val task = ScheduledTaskDialog(null, folderChoices(), tasks.map { it.name }.toSet()).task() ?: return
        save { it + task }
    }

    private fun edit(view: View, task: ScheduledTask) {
        val others = view.tasks.getOrThrow().filter { it.id != task.id }.map { it.name }.toSet()
        val changed = ScheduledTaskDialog(task, folderChoices(), others).task() ?: return
        save { it.map { t -> if (t.id == task.id) edited(t, changed, Instant.now()) else t } }
    }

    private fun delete(view: View, task: ScheduledTask) {
        val answer = Messages.showYesNoDialog(project, "Delete \"${task.name}\"? Its past runs stay as sessions.", "Delete Scheduled Task", null)
        if (answer == Messages.YES) save { it.filter { t -> t.id != task.id } }
    }

    /** Writes [change] of the file as it is now, not as the tab last read it, so an edit elsewhere meanwhile is kept. */
    private fun save(change: (List<ScheduledTask>) -> List<ScheduledTask>) {
        store.write(change(store.read().getOrThrow()))
        reload()
    }

    /** The folders a run may be filed in: every folder outside a plan, by path. */
    private fun folderChoices(): List<Pair<String, String>> {
        val folders = service.folders
        return folders.all().filter { folders.planFolderAt(it.id) == null }.map { it.id to folders.pathOf(it.id) }.toList()
    }

    override fun getComponent(): JComponent = scroll
    override fun getPreferredFocusedComponent(): JComponent? = null
    override fun getName() = "Scheduled Tasks"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() = timer.stop()
}
