package dev.myair.toolwindow

import dev.myair.ClaudeSessionsService
import dev.myair.claude.ClaudeSession
import dev.myair.folders.ChangeSource
import dev.myair.folders.Folder
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode

/** Drags sessions (one or many), a single folder or a single project within the tree; nothing crosses to other components. */
class FolderDrop(private val tree: DropLineTree, private val service: ClaudeSessionsService, private val canEdit: () -> Boolean) : TransferHandler() {
    private var dragged = emptyList<Any>()

    override fun getSourceActions(c: JComponent) = MOVE

    override fun createTransferable(c: JComponent): Transferable? {
        val items = tree.selectionPaths.orEmpty().map { (it.lastPathComponent as DefaultMutableTreeNode).userObject }
        dragged = when {
            items.isNotEmpty() && items.all { it is ClaudeSession } -> items
            items.size == 1 && (items[0] is Folder || items[0] is Path) -> items
            else -> return null
        }
        return StringSelection(dragged.joinToString("\n"))
    }

    override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
        dragged = emptyList()
        tree.drop = null
    }

    override fun canImport(support: TransferSupport): Boolean {
        val drop = if (support.isDrop) drop(support) else null
        support.setShowDropLocation(false)
        tree.drop = drop
        return drop != null
    }

    override fun importData(support: TransferSupport): Boolean {
        val drop = drop(support) ?: return false
        service.updateFolders(ChangeSource.UI, drop.change)
        return true
    }

    private fun drop(support: TransferSupport): Drop? {
        if (!canEdit() || dragged.isEmpty()) return null
        val location = support.dropLocation as JTree.DropLocation
        return dropChange(service.folders, dragged, location.path ?: return null, location.childIndex)
    }
}
