package dev.myair.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import dev.myair.folders.Milestone
import dev.myair.folders.Plan
import dev.myair.folders.PlanStatus
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders
import dev.myair.editor.Closing
import dev.myair.editor.PlanStart
import dev.myair.editor.SessionTerminals
import dev.myair.editor.confirmed
import dev.myair.planview.PlanFiles
import dev.myair.service.ClaudeSessionsService
import java.nio.file.Path
import java.util.UUID
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** The folder and plan actions of the tree's menu and toolbar, acting on its selection. */
class FolderEditing(private val project: Project, private val tree: JTree, private val service: ClaudeSessionsService) {
    fun selected(): Any? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject

    fun selectedFolder() = selected() as? Folder

    /** The plan folder of the selection: the folder itself or the one a milestone, task or `no task` row belongs to. */
    fun selectedPlanFolder() = when (val item = selected()) {
        is Folder -> item.takeIf { it.plan != null }
        is MilestoneRow -> item.folder
        is PlanTaskRow -> item.folder
        is NoTask -> item.folder
        else -> null
    }

    /** The milestone of the selection: a milestone row's, or a task row's milestone. */
    fun selectedMilestone() = when (val item = selected()) {
        is MilestoneRow -> item.milestone.id
        is PlanTaskRow -> service.folders.findTask(item.task.id)?.let { (folder, task) -> folder.plan!!.milestones.first { task in it.tasks }.id }
        else -> null
    }

    /** Where a new session started from the selection goes in a plan: a plan row's plan, and a task row's task. */
    fun selectedPlanStart() = selectedPlanFolder()?.let { PlanStart(it.id, (selected() as? PlanTaskRow)?.task?.id) }

    /** Inside the folder of the selection; at the top level when nothing or something under `sessions` is selected. */
    fun newFolder() {
        val parentId = tree.selectionPath?.let(::targetFolder)
        val name = askName("New Folder", "", parentId, null) ?: return
        update { it.createFolder(parentId, UUID.randomUUID().toString(), name) }
    }

    /** A new plan folder inside the folder of the selection, or at the top level, as [newFolder] places a folder. */
    fun newPlan() {
        val parentId = tree.selectionPath?.let(::targetFolder)
        val name = askName("New Plan", "", parentId, null) ?: return
        update { it.createFolder(parentId, UUID.randomUUID().toString(), name, plan = Plan()) }
    }

    /** Outside every plan folder, since plans do not nest. */
    fun canNewPlan() = tree.selectionPath?.let(::targetFolder)?.let { service.folders.planFolderAt(it) == null } ?: true

    /** Renames the selected folder, milestone or task. */
    fun rename() {
        when (val item = selected()) {
            is Folder -> {
                val parentId = ((tree.selectionPath!!.parentPath.lastPathComponent as DefaultMutableTreeNode).userObject as? Folder)?.id
                val name = askName("Rename Folder", item.name, parentId, item.id) ?: return
                update { it.rename(item.id, name) }
            }
            is MilestoneRow -> askText("Rename Milestone", "Title:", item.milestone.title)?.let { title -> update { it.editMilestone(item.milestone.id) { m -> m.copy(title = title) } } }
            is PlanTaskRow -> askText("Rename Task", "Title:", item.task.title)?.let { title -> update { it.editTask(item.task.id) { t -> t.copy(title = title) } } }
            else -> error("nothing to rename in $item")
        }
    }

    fun canRename() = selected().let { it is Folder || it is MilestoneRow || it is PlanTaskRow }

    /** Deletes the selected folder, milestone or task after the user confirms. */
    fun delete() {
        when (val item = selected()) {
            is Folder -> {
                val count = service.folders.sessionCount(item.id)
                val dirs = item.subtree().flatMap { it.dirs }.toList()
                val plans = item.subtree().mapNotNull { it.plan }.toList()
                val message = "Delete \"${item.name}\" and its subfolders?" +
                    (if (count > 0) "\n$count session(s) filed there go back to their project's folder or \"sessions\"." else "") +
                    (if (dirs.isNotEmpty()) "\nThe other sessions of ${dirs.joinToString()}, and those whose brief names a folder deleted, go back to \"sessions\"." else "") +
                    (if (plans.isNotEmpty()) "\nIts plan with ${plans.sumOf { it.tasks().size }} task(s) is deleted too." else "")
                if (confirm(project, message, "Delete Folder") && managersConfirmed(item.subtree(), Closing.DELETE_FOLDER)) update { it.delete(item.id) }
            }
            is MilestoneRow -> deleteMilestone(project, item.milestone)
            is PlanTaskRow -> deleteTask(project, item.task)
            else -> error("nothing to delete in $item")
        }
    }

    fun addPlan() = update { it.addPlan(selectedFolder()!!.id) }

    fun canAddPlan() = selectedFolder()?.let { service.folders.canAddPlan(it.id) } == true

    fun removePlan() {
        val folder = selectedFolder()!!
        if (managersConfirmed(sequenceOf(folder), Closing.REMOVE_PLAN)) update { it.removePlan(folder.id) }
    }

    /** Whether [closing] may end the managers of this IDE that the plans of [folders] have and that work now, asking when one does. */
    private fun managersConfirmed(folders: Sequence<Folder>, closing: Closing): Boolean {
        val files = PlanFiles.getInstance()
        val working = folders.filter { it.plan != null }.mapNotNull { files.manager(it.id) }.filter(SessionTerminals.getInstance()::wouldInterrupt)
        return confirmed(project, closing, working.map { it.title }.toList())
    }

    fun canRemovePlan() = selectedFolder()?.plan?.milestones?.isEmpty() == true

    /** Binds a directory picked in a chooser to the selected folder, unless it is bound already. */
    fun bindDirectory() {
        val folder = selectedFolder()!!
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null) ?: return
        val dir = Path.of(file.path).toString()
        service.folders.all().firstOrNull { dir in it.dirs }?.let {
            Messages.showErrorDialog(project, "$dir is bound to \"${service.folders.pathOf(it.id)}\" already.", "Bind Directory")
            return
        }
        update { it.bind(folder.id, dir) }
    }

    /** Sets the selected folder's working directory to one picked in a chooser; any folder may share it. */
    fun setWorkingDirectory() {
        val folder = selectedFolder()!!
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null) ?: return
        update { it.setWorkdir(folder.id, Path.of(file.path).toString()) }
    }

    fun clearWorkingDirectory() = update { it.setWorkdir(selectedFolder()!!.id, null) }

    /** Unbinds one of the selected folder's directories, asking which when it has several. */
    fun unbindDirectory() {
        val dirs = selectedFolder()!!.dirs
        val index = if (dirs.size == 1) 0 else Messages.showChooseDialog(project, "Directory:", "Unbind Directory", null, dirs.toTypedArray(), dirs.first())
        if (index < 0) return
        update { it.unbind(dirs[index]) }
    }

    fun addMilestone() {
        val folder = selectedPlanFolder()!!
        val title = askText("Add Milestone", "Title:", "") ?: return
        update { it.addMilestone(folder.id, title) }
    }

    fun addTask() {
        val milestone = selectedMilestone()!!
        val title = askText("Add Task", "Title:", "") ?: return
        update { it.addTask(milestone, title) }
    }

    fun setMilestoneDone(done: Boolean) {
        val row = selected() as MilestoneRow
        update { it.editMilestone(row.milestone.id) { m -> m.copy(done = done) } }
    }

    fun setStatus(status: PlanStatus) {
        val row = selected() as PlanTaskRow
        update { it.editTask(row.task.id) { t -> t.copy(status = status) } }
    }

    /** Edits the selected task's next step; an empty one clears it. */
    fun editNext() {
        val row = selected() as PlanTaskRow
        val next = Messages.showInputDialog(project, "Next step (empty clears it):", "Edit Next Step", null, row.task.next.orEmpty(), null) ?: return
        update { it.editTask(row.task.id) { t -> t.copy(next = next.trim().ifEmpty { null }) } }
    }

    /** Edits the Markdown description of the selected plan folder, milestone or task; an empty one clears it. */
    fun editDescription() {
        val (title, current, set) = when (val item = selected()) {
            is Folder -> Triple("Plan ${item.name}", item.plan!!.description) { d: String? -> update { it.setDescription(item.id, d) } }
            is MilestoneRow -> Triple("Milestone ${item.milestone.id}", item.milestone.description) { d: String? -> update { it.editMilestone(item.milestone.id) { m -> m.copy(description = d) } } }
            is PlanTaskRow -> Triple("Task ${item.task.id}", item.task.description) { d: String? -> update { it.editTask(item.task.id) { t -> t.copy(description = d) } } }
            else -> error("no description in $item")
        }
        val text = Messages.showMultilineInputDialog(project, "Markdown (empty clears it):", "Description of $title", current.orEmpty(), null, null) ?: return
        set(text.trim().ifEmpty { null })
    }

    /** "Set Status" with one entry per status, for a task row. */
    fun statusGroup(visible: () -> Boolean, enabled: () -> Boolean) = DefaultActionGroup("Set Status", true).apply {
        PlanStatus.entries.forEach { status ->
            add(FolderAction(status.label.replace('_', ' ').replaceFirstChar(Char::uppercase), status.icon, visible, { enabled() && (selected() as? PlanTaskRow)?.task?.status != status }) { setStatus(status) })
        }
        templatePresentation.isHideGroupIfEmpty = true
    }

    private fun update(change: (SessionFolders) -> SessionFolders) = service.updateFolders(ChangeSource.UI, change)

    /** A trimmed non-blank text, or null when cancelled. */
    private fun askText(title: String, label: String, initial: String): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(input: String) = if (input.isBlank()) "Cannot be empty" else null
        }
        return Messages.showInputDialog(project, label, title, null, initial, validator)?.trim()
    }

    private fun askName(title: String, initial: String, parentId: String?, except: String?) = askFolderName(project, title, initial, parentId, except)
}

/** A trimmed name no other folder under [parentId] has, or null when cancelled. */
fun askFolderName(project: Project, title: String, initial: String, parentId: String?, except: String?): String? {
    val validator = object : InputValidatorEx {
        override fun getErrorText(input: String) = when {
            input.isBlank() -> "Name cannot be empty"
            '/' in input -> "Name cannot contain /, which separates folders in a path"
            ClaudeSessionsService.getInstance().folders.nameTaken(parentId, input, except) -> "A folder named \"${input.trim()}\" already exists here"
            else -> null
        }
    }
    return Messages.showInputDialog(project, "Name:", title, null, initial, validator)?.trim()
}

/** Deletes [milestone] after the user confirms; one with open tasks is refused, naming them. */
fun deleteMilestone(project: Project, milestone: Milestone) {
    val open = milestone.tasks.filterNot { it.status.closed }
    if (open.isNotEmpty()) Messages.showErrorDialog(project, "Close or move its open tasks first: ${open.joinToString { it.id }}.", "Delete Milestone")
    else if (confirm(project, "Delete milestone ${milestone.id} \"${milestone.title}\" with ${milestone.tasks.size} closed task(s)?", "Delete Milestone")) {
        ClaudeSessionsService.getInstance().updateFolders(ChangeSource.UI) { it.deletePlanItem(milestone.id) }
    }
}

/** Deletes [task] after the user confirms. */
fun deleteTask(project: Project, task: PlanTask) {
    if (confirm(project, "Delete task ${task.id} \"${task.title}\"? Sessions naming it go back to the folder they are filed in or bound to.", "Delete Task")) {
        ClaudeSessionsService.getInstance().updateFolders(ChangeSource.UI) { it.deletePlanItem(task.id) }
    }
}

private fun confirm(project: Project, message: String, title: String) = Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES

/** A folder action, shown in Folders mode only. */
class FolderAction(text: String, icon: Icon?, private val visible: () -> Boolean, private val enabled: () -> Boolean, private val run: () -> Unit) :
    DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = visible()
        e.presentation.isEnabled = enabled()
    }
    override fun actionPerformed(e: AnActionEvent) = run()
}
