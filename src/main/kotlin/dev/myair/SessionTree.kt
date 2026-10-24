package dev.myair

import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** A file a session's agent wrote or edited; toString() is the node id TreeState keeps the selection by. */
class ChangedFile(val path: Path, val cwd: Path) {
    override fun toString() = path.toString()
}

/** The fixed last folder of Folders mode: every session not filed in a user folder, grouped by project. */
object UnfiledSessions {
    override fun toString() = "sessions"
}

/** Projects mode: projects ordered by their most recent session; [sessions] arrive newest first. */
fun projectsTree(sessions: List<ClaudeSession>) = node(null, projectNodes(sessions))

/**
 * Folders mode: the user's folders in their order, each with its subfolders and then its sessions newest first, and
 * [UnfiledSessions] last. Sessions filed but absent from [sessions] are not shown. A non-empty [query] keeps the matching
 * sessions, the folders on their path and [UnfiledSessions].
 */
fun foldersTree(structure: SessionFolders, sessions: List<ClaudeSession>, query: String): DefaultMutableTreeNode {
    val shown = sessions.filter { it.matches(query) }
    fun folderNode(folder: Folder): DefaultMutableTreeNode? {
        val ids = folder.items.map { it.id }.toSet()
        val children = folder.folders.mapNotNull(::folderNode) + shown.filter { it.id in ids }.map(::sessionNode)
        return if (query.isNotEmpty() && children.isEmpty()) null else node(folder, children)
    }
    val filed = structure.all().flatMap { it.items }.map { it.id }.toSet()
    return node(null, structure.folders.mapNotNull(::folderNode) + node(UnfiledSessions, projectNodes(shown.filter { it.id !in filed })))
}

/** The folder new sessions go to after [path] was selected: the nearest user folder above it, none under [UnfiledSessions]. */
fun targetFolder(path: TreePath): String? = path.path.map { (it as DefaultMutableTreeNode).userObject }.filterIsInstance<Folder>().lastOrNull()?.id

/**
 * What dropping [dragged] (sessions, or one folder) on the node at [path] does, or null where the drop is refused.
 * [childIndex] is JTree's: -1 drops onto the node, otherwise between its children. Sessions land in the folder of whatever
 * they are dropped on or into, none under [UnfiledSessions]. A folder goes into a folder or the top level, before the
 * folder it is dropped in front of, or last.
 */
fun dropChange(structure: SessionFolders, dragged: List<Any>, path: TreePath, childIndex: Int): ((SessionFolders) -> SessionFolders)? {
    val target = path.lastPathComponent as DefaultMutableTreeNode
    val moved = dragged.singleOrNull() as? Folder ?: run {
        if (target.isRoot) return null
        val folderId = targetFolder(path)
        return { dragged.fold(it) { folders, session -> folders.assign((session as ClaudeSession).id, folderId) } }
    }
    val parentId = when (val item = target.userObject) {
        null -> null
        is Folder -> item.id
        else -> return null
    }
    val current = structure.find(moved.id) ?: return null
    if (current.subtree().any { it.id == parentId } || structure.nameTaken(parentId, moved.name, except = moved.id)) return null
    // A position in the tree, which a filter may have thinned, becomes the index of the folder it lands before.
    val siblings = structure.children(parentId)
    val before = (if (childIndex in 0 until target.childCount) target.getChildAt(childIndex) else null)
        ?.let { (it as DefaultMutableTreeNode).userObject as? Folder }
    val index = if (before == null) siblings.size else siblings.indexOfFirst { it.id == before.id }
    return { it.moveFolder(moved.id, parentId, index) }
}

private fun projectNodes(sessions: List<ClaudeSession>) = sessions.groupBy { it.cwd }.map { (cwd, group) -> node(cwd, group.map(::sessionNode)) }

private fun sessionNode(session: ClaudeSession) = node(session, session.changedFiles.map { node(ChangedFile(it, session.cwd)) })

private fun node(item: Any?, children: List<DefaultMutableTreeNode> = emptyList()) = DefaultMutableTreeNode(item).apply { children.forEach(::add) }
