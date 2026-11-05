package dev.myair.toolwindow

import dev.myair.actions.Git
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.folders.Folder
import dev.myair.folders.Milestone
import dev.myair.folders.PlanTask
import dev.myair.folders.SessionFolders
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.extension
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** A file a session's agent wrote or edited; toString() is the node id TreeState keeps the selection by. */
class ChangedFile(val path: Path, val cwd: Path) {
    override fun toString() = path.toString()
}

/** The folder [session] works in now ([ClaudeSession.workDir]), the first of its children; toString() is the TreeState id. */
class WorkDirRow(val session: ClaudeSession) {
    override fun toString() = "workdir ${session.id}"
}

/** A directory [session] may use besides its cwd, below its work dir. */
class AddedDir(val session: ClaudeSession, val path: Path) {
    override fun toString() = "dir ${session.id} $path"
}

/** The group of [session]'s brief: its goal and tasks. */
class BriefGroup(val session: ClaudeSession) {
    override fun toString() = "brief ${session.id}"
}

/** The goal of [session]'s brief, the first row of its [BriefGroup]. */
class BriefGoal(val session: ClaudeSession, val text: String) {
    override fun toString() = "goal ${session.id}"
}

/** Task [index] of [session]'s brief, below the goal. */
class BriefTaskRow(val session: ClaudeSession, val index: Int, val task: BriefTask) {
    override fun toString() = "task ${session.id} $index"
}

/** The group of the files [session] wrote that are not outputs ([OUTPUT_EXTENSIONS]); the outputs follow it directly. */
class FilesGroup(val session: ClaudeSession, val count: Int) {
    override fun toString() = "files ${session.id}"
}

/** A milestone of [folder]'s plan; toString() is the TreeState id. */
class MilestoneRow(val folder: Folder, val milestone: Milestone) {
    override fun toString() = milestone.id
}

/** A task of [folder]'s plan, holding the sessions whose brief names it. */
class PlanTaskRow(val folder: Folder, val task: PlanTask) {
    override fun toString() = task.id
}

/** The last group of plan folder [folder]: its sessions that name no task of the plan. */
class NoTask(val folder: Folder) {
    override fun toString() = "no task ${folder.id}"
}

/** The fixed last folder of Folders mode: every session not filed in a user folder, grouped by project. */
object UnfiledSessions {
    override fun toString() = "sessions"
}

/** The fixed folder of Folders mode above [UnfiledSessions]: the [RECENT_COUNT] sessions with the newest last message, wherever they are filed. */
object RecentSessions {
    override fun toString() = "recent"
}

const val RECENT_COUNT = 10

/** Projects mode: projects ordered by their most recent session; [sessions] arrive newest first. */
fun projectsTree(sessions: List<ClaudeSession>) = node(null, projectNodes(sessions))

/**
 * Folders mode: the user's folders in their order, each with its subfolders and then its sessions newest first (see
 * [SessionFolders.placeOf]), then [RecentSessions] and [UnfiledSessions] last. A plan folder holds, after its subfolders,
 * its milestones with their tasks, each task the sessions whose brief names it, then [NoTask] with its other sessions
 * when there are any. Sessions filed but absent from [sessions] are not shown. A [filter] keeps the sessions it accepts,
 * the folders, milestones and tasks on their path, [RecentSessions] and [UnfiledSessions]; [sessions] arrive newest first.
 */
fun foldersTree(structure: SessionFolders, sessions: List<ClaudeSession>, filter: ((ClaudeSession) -> Boolean)?): DefaultMutableTreeNode {
    val shown = filter?.let(sessions::filter) ?: sessions
    val (tasked, untasked) = shown.partition { structure.taskOf(it) != null }
    val byTask = tasked.groupBy { it.brief!!.planTask!! }
    val places = untasked.groupBy { structure.placeOf(it) }
    fun keep(item: Any, children: List<DefaultMutableTreeNode>) = if (filter != null && children.isEmpty()) null else node(item, children)
    fun planNodes(folder: Folder) = folder.plan!!.milestones.mapNotNull { m ->
        keep(MilestoneRow(folder, m), m.tasks.mapNotNull { t -> keep(PlanTaskRow(folder, t), byTask[t.id].orEmpty().map(::sessionNode)) })
    }
    fun folderNode(folder: Folder): DefaultMutableTreeNode? {
        val own = places[folder.id].orEmpty().map(::sessionNode)
        val children = folder.folders.mapNotNull(::folderNode) +
            if (folder.plan == null) own else planNodes(folder) + listOfNotNull(own.takeIf { it.isNotEmpty() }?.let { node(NoTask(folder), it) })
        return keep(folder, children)
    }
    return node(
        null,
        structure.folders.mapNotNull(::folderNode) + node(RecentSessions, shown.take(RECENT_COUNT).map(::sessionNode)) +
            node(UnfiledSessions, projectNodes(places[null].orEmpty())),
    )
}

/**
 * Whether [session] works or started in the work tree at [root], by the work trees [heads] reads for its work dir and
 * cwd; a session whose directory [heads] lacks is a bug and throws.
 */
fun inWorkTree(session: ClaudeSession, root: Path, heads: Map<Path, Git.Head?>) =
    listOf(session.workDir, session.cwd).any { heads.getValue(it)?.root == root }

/** What makes a session call for attention, most urgent first. */
enum class Urgency { NEEDS_INPUT, UNREAD, WORKING }

/** A session's [Urgency]: its live status, an idle one only when it has an unread reply; null for none. */
fun urgencyOf(live: LiveSession?, unread: Boolean): Urgency? = when (live?.status) {
    SessionStatus.NEEDS_INPUT -> Urgency.NEEDS_INPUT
    SessionStatus.WORKING -> Urgency.WORKING
    SessionStatus.IDLE -> Urgency.UNREAD.takeIf { unread }
    null -> null
}

/** What a group row shows on its right: how many sessions it holds and the most urgent of them. */
data class FolderSummary(val sessions: Int, val urgent: Urgency?)

/** The [FolderSummary] of a group node (folder, project, recent or unfiled sessions), counting the sessions below it as shown; null for a session and its children. */
fun folderSummary(node: DefaultMutableTreeNode, urgency: (ClaudeSession) -> Urgency?): FolderSummary? = when (node.userObject) {
    is ClaudeSession, is WorkDirRow, is AddedDir, is BriefGroup, is BriefGoal, is BriefTaskRow, is FilesGroup, is ChangedFile -> null
    is Folder, is Path, RecentSessions, UnfiledSessions, is MilestoneRow, is PlanTaskRow, is NoTask -> {
        val sessions = groupSessions(node)
        FolderSummary(sessions.size, sessions.mapNotNull(urgency).minOrNull())
    }
    else -> error("Unexpected tree node ${node.userObject}")
}

/** The sessions below [node], not looking inside a session. */
fun groupSessions(node: DefaultMutableTreeNode): List<ClaudeSession> = node.children().toList().flatMap {
    val child = it as DefaultMutableTreeNode
    (child.userObject as? ClaudeSession)?.let(::listOf) ?: groupSessions(child)
}

/** The folder [path] points into, for a new folder or a drop: the nearest user folder above it, none under [UnfiledSessions]. */
fun targetFolder(path: TreePath): String? = path.path.map { (it as DefaultMutableTreeNode).userObject }.filterIsInstance<Folder>().lastOrNull()?.id

/**
 * The directory a session started from the node at [path] runs in: a project row's, or for a folder or a row of its plan
 * the first directory bound to it or to the nearest folder above it; null for any other node.
 */
fun nodeDir(path: TreePath): Path? = when (val item = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
    is Path -> item
    is Folder, is MilestoneRow, is PlanTaskRow, is NoTask -> path.path.map { (it as DefaultMutableTreeNode).userObject }.filterIsInstance<Folder>().lastOrNull { it.dirs.isNotEmpty() }?.dirs?.first()?.let(Path::of)
    else -> null
}

/** The directory to open from the node at [path]: a session's own cwd, else [nodeDir]. */
fun openDir(path: TreePath): Path? = ((path.lastPathComponent as DefaultMutableTreeNode).userObject as? ClaudeSession)?.cwd ?: nodeDir(path)

/** A drop as it will happen: the [change], and where the tree shows it: [childIndex] -1 frames [path]'s row, otherwise a line before that child. */
class Drop(val change: (SessionFolders) -> SessionFolders, val path: TreePath, val childIndex: Int)

/**
 * What dropping [dragged] (sessions, one folder, one project from under [UnfiledSessions], one milestone or one task) on
 * the node at [path] does, or null where the drop is refused. A milestone moves among its plan's milestones, before the
 * one it is dropped in front of or after the last; a task into a milestone of its plan, before the task it is dropped in
 * front of or last. A session whose brief names a task stays under it, so it does not move, and no session drops onto a
 * milestone or task, which only a brief puts it under. A plan folder does not go into another. [childIndex] is JTree's: -1 drops onto the node, otherwise between its children.
 * Sessions land in the folder of whatever they are dropped on or into, none under [UnfiledSessions], and that folder is what
 * the drop shows; a session of a bound project cannot go back to [UnfiledSessions], since it would stay in the project's
 * folder. A folder goes into a folder or the top level, before the folder it is dropped in front of, or after the last one;
 * the drop shows that spot, not the pointer's, since sessions, [RecentSessions] and [UnfiledSessions] always follow the folders. Nothing drops on or into [RecentSessions]. A project
 * lands the same way, as a new folder named after its directory and bound to it.
 */
fun dropChange(structure: SessionFolders, dragged: List<Any>, path: TreePath, childIndex: Int): Drop? {
    val target = path.lastPathComponent as DefaultMutableTreeNode
    // Recent sessions are a view of the others: nothing is filed into it.
    if (path.path.any { (it as DefaultMutableTreeNode).userObject == RecentSessions }) return null
    when (val row = dragged.singleOrNull()) {
        is MilestoneRow -> return milestoneDrop(structure, row, target, path, childIndex)
        is PlanTaskRow -> return taskDrop(structure, row, target, path, childIndex)
    }
    val moved = dragged.singleOrNull()?.takeIf { it is Folder || it is Path } ?: run {
        if (target.isRoot || path.path.any { (it as DefaultMutableTreeNode).userObject.let { item -> item is MilestoneRow || item is PlanTaskRow } }) return null
        if (dragged.any { structure.taskOf(it as ClaudeSession) != null }) return null
        val folderId = targetFolder(path)
        if (folderId == null && dragged.any { structure.projectFolder((it as ClaudeSession).project) != null }) return null
        val home = generateSequence(path) { it.parentPath }
            .first { (it.lastPathComponent as DefaultMutableTreeNode).userObject.let { item -> item is Folder || item == UnfiledSessions } }
        return Drop({ dragged.fold(it) { folders, session -> folders.assign((session as ClaudeSession).id, folderId) } }, home, -1)
    }
    val parentId = when (val item = target.userObject) {
        null -> null
        is Folder -> item.id
        else -> return null
    }
    val place: (Int) -> (SessionFolders) -> SessionFolders = when (moved) {
        is Folder -> {
            val current = structure.find(moved.id) ?: return null
            if (current.subtree().any { it.id == parentId } || structure.nameTaken(parentId, moved.name, except = moved.id) || !structure.canHold(parentId, current)) return null
            { index -> { it.moveFolder(moved.id, parentId, index) } }
        }
        else -> {
            val project = moved as Path
            val name = projectName(project)
            if (structure.projectFolder(project) != null || structure.nameTaken(parentId, name)) return null
            // Created last among its siblings, where moveFolder counts it after every index the tree can give.
            { index -> { UUID.randomUUID().toString().let { id -> it.createFolder(parentId, id, name, listOf(project.toString())).moveFolder(id, parentId, index) } } }
        }
    }
    val siblings = structure.children(parentId)
    if (childIndex < 0) return Drop(place(siblings.size), path, -1)
    // A position in the tree, which a filter may have thinned, becomes the index of the folder it lands before.
    val items = target.children().toList().map { (it as DefaultMutableTreeNode).userObject }
    val before = items.getOrNull(childIndex) as? Folder
    if (before != null) return Drop(place(siblings.indexOfFirst { f -> f.id == before.id }), path, childIndex)
    val afterFolders = items.indexOfFirst { it !is Folder }.takeIf { it >= 0 } ?: items.size
    return Drop(place(siblings.size), path, afterFolders)
}

/** A milestone dropped between the children of its plan folder's row: the line goes after the last milestone unless it falls before one. */
private fun milestoneDrop(structure: SessionFolders, row: MilestoneRow, target: DefaultMutableTreeNode, path: TreePath, childIndex: Int): Drop? {
    if ((target.userObject as? Folder)?.id != row.folder.id || childIndex < 0) return null
    val milestones = structure.find(row.folder.id)?.plan?.milestones ?: return null
    val current = milestones.indexOfFirst { it.id == row.milestone.id }.takeIf { it >= 0 } ?: return null
    val items = target.children().toList().map { (it as DefaultMutableTreeNode).userObject }
    val before = items.getOrNull(childIndex) as? MilestoneRow
    val index = before?.let { b -> milestones.indexOfFirst { it.id == b.milestone.id } } ?: milestones.size
    val line = if (before != null) childIndex else items.indexOfLast { it is MilestoneRow } + 1
    return Drop({ it.moveMilestone(row.milestone.id, if (current < index) index - 1 else index) }, path, line)
}

/** A task dropped onto a milestone of its plan (last there) or before one of its tasks. */
private fun taskDrop(structure: SessionFolders, row: PlanTaskRow, target: DefaultMutableTreeNode, path: TreePath, childIndex: Int): Drop? {
    val milestone = (target.userObject as? MilestoneRow)?.takeIf { it.folder.id == row.folder.id }?.milestone ?: return null
    val tasks = structure.findMilestone(milestone.id)?.second?.tasks ?: return null
    if (structure.findTask(row.task.id) == null) return null
    val items = target.children().toList().map { (it as DefaultMutableTreeNode).userObject }
    val before = if (childIndex < 0) null else items.getOrNull(childIndex) as? PlanTaskRow
    val index = before?.let { b -> tasks.indexOfFirst { it.id == b.task.id } } ?: tasks.size
    val current = tasks.indexOfFirst { it.id == row.task.id }
    return Drop({ it.moveTask(row.task.id, milestone.id, if (current in 0 until index) index - 1 else index) }, path, if (before != null) childIndex else -1)
}

/** The name of the folder a project becomes: its directory's name. */
fun projectName(project: Path) = (project.fileName ?: project).toString()

private fun projectNodes(sessions: List<ClaudeSession>) = sessions.groupBy { it.cwd }.map { (cwd, group) -> node(cwd, group.map(::sessionNode)) }

/**
 * A session with, below it, the folder it works in, the directories added to it, its brief (goal and tasks) as a group,
 * the files it wrote other than outputs as a group, and its outputs.
 */
private fun sessionNode(session: ClaudeSession): DefaultMutableTreeNode {
    val (outputs, files) = session.changedFiles.partition { it.extension.lowercase() in OUTPUT_EXTENSIONS }
    val brief = session.brief?.let {
        node(BriefGroup(session), listOfNotNull(it.goal?.let { goal -> node(BriefGoal(session, goal)) }) + it.tasks.mapIndexed { i, task -> node(BriefTaskRow(session, i, task)) })
    }
    return node(
        session,
        listOf(node(WorkDirRow(session))) + session.addedDirs.map { node(AddedDir(session, it)) } + listOfNotNull(brief) +
            listOfNotNull(files.takeIf { it.isNotEmpty() }?.let { node(FilesGroup(session, it.size), it.map { f -> node(ChangedFile(f, session.cwd)) }) }) +
            outputs.map { node(ChangedFile(it, session.cwd)) },
    )
}

private fun node(item: Any?, children: List<DefaultMutableTreeNode> = emptyList()) = DefaultMutableTreeNode(item).apply { children.forEach(::add) }
