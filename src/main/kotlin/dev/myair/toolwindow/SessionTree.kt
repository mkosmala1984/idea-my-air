package dev.myair.toolwindow

import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.folders.Folder
import dev.myair.folders.SessionFolders
import java.nio.file.Path
import java.util.UUID
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** A file a session's agent wrote or edited; toString() is the node id TreeState keeps the selection by. */
class ChangedFile(val path: Path, val cwd: Path) {
    override fun toString() = path.toString()
}

/** The goal of [session]'s brief, the first of its children; toString() is the TreeState id. */
class BriefGoal(val session: ClaudeSession, val text: String) {
    override fun toString() = "goal ${session.id}"
}

/** Task [index] of [session]'s brief, below the goal. */
class BriefTaskRow(val session: ClaudeSession, val index: Int, val task: BriefTask) {
    override fun toString() = "task ${session.id} $index"
}

/** The fixed last folder of Folders mode: every session not filed in a user folder, grouped by project. */
object UnfiledSessions {
    override fun toString() = "sessions"
}

/** Projects mode: projects ordered by their most recent session; [sessions] arrive newest first. */
fun projectsTree(sessions: List<ClaudeSession>) = node(null, projectNodes(sessions))

/**
 * Folders mode: the user's folders in their order, each with its subfolders and then its sessions newest first (see
 * [SessionFolders.placeOf]), and [UnfiledSessions] last. Sessions filed but absent from [sessions] are not shown. A non-empty [query] keeps the matching
 * sessions, the folders on their path and [UnfiledSessions].
 */
fun foldersTree(structure: SessionFolders, sessions: List<ClaudeSession>, query: String): DefaultMutableTreeNode {
    val shown = sessions.filter { it.matches(query) }
    val places = shown.groupBy { structure.placeOf(it) }
    fun folderNode(folder: Folder): DefaultMutableTreeNode? {
        val children = folder.folders.mapNotNull(::folderNode) + places[folder.id].orEmpty().map(::sessionNode)
        return if (query.isNotEmpty() && children.isEmpty()) null else node(folder, children)
    }
    return node(null, structure.folders.mapNotNull(::folderNode) + node(UnfiledSessions, projectNodes(places[null].orEmpty())))
}

/** The folder new sessions go to after [path] was selected: the nearest user folder above it, none under [UnfiledSessions]. */
fun targetFolder(path: TreePath): String? = path.path.map { (it as DefaultMutableTreeNode).userObject }.filterIsInstance<Folder>().lastOrNull()?.id

/** A drop as it will happen: the [change], and where the tree shows it: [childIndex] -1 frames [path]'s row, otherwise a line before that child. */
class Drop(val change: (SessionFolders) -> SessionFolders, val path: TreePath, val childIndex: Int)

/**
 * What dropping [dragged] (sessions, one folder, or one project from under [UnfiledSessions]) on the node at [path] does,
 * or null where the drop is refused. [childIndex] is JTree's: -1 drops onto the node, otherwise between its children.
 * Sessions land in the folder of whatever they are dropped on or into, none under [UnfiledSessions], and that folder is what
 * the drop shows; a session of a bound project cannot go back to [UnfiledSessions], since it would stay in the project's
 * folder. A folder goes into a folder or the top level, before the folder it is dropped in front of, or after the last one;
 * the drop shows that spot, not the pointer's, since sessions and [UnfiledSessions] always follow the folders. A project
 * lands the same way, as a new folder named after its directory and bound to it.
 */
fun dropChange(structure: SessionFolders, dragged: List<Any>, path: TreePath, childIndex: Int): Drop? {
    val target = path.lastPathComponent as DefaultMutableTreeNode
    val moved = dragged.singleOrNull()?.takeIf { it is Folder || it is Path } ?: run {
        if (target.isRoot) return null
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
            if (current.subtree().any { it.id == parentId } || structure.nameTaken(parentId, moved.name, except = moved.id)) return null
            { index -> { it.moveFolder(moved.id, parentId, index) } }
        }
        else -> {
            val project = moved as Path
            val name = projectName(project)
            if (structure.projectFolder(project) != null || structure.nameTaken(parentId, name)) return null
            // Created last among its siblings, where moveFolder counts it after every index the tree can give.
            { index -> { UUID.randomUUID().toString().let { id -> it.createFolder(parentId, id, name, project.toString()).moveFolder(id, parentId, index) } } }
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

/** The name of the folder a project becomes: its directory's name. */
fun projectName(project: Path) = (project.fileName ?: project).toString()

private fun projectNodes(sessions: List<ClaudeSession>) = sessions.groupBy { it.cwd }.map { (cwd, group) -> node(cwd, group.map(::sessionNode)) }

private fun sessionNode(session: ClaudeSession) = node(
    session,
    listOfNotNull(session.brief?.goal?.let { node(BriefGoal(session, it)) }) +
        session.brief?.tasks.orEmpty().mapIndexed { i, task -> node(BriefTaskRow(session, i, task)) } +
        session.changedFiles.map { node(ChangedFile(it, session.cwd)) },
)

private fun node(item: Any?, children: List<DefaultMutableTreeNode> = emptyList()) = DefaultMutableTreeNode(item).apply { children.forEach(::add) }
