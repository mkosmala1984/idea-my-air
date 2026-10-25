package dev.myair.toolwindow

import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JTree
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

/**
 * Draws a drop itself: a line between rows, a frame around the row dropped onto. IntelliJ's tree UI would instead fill the
 * row of the drop's parent, which flickers as the pointer crosses rows, so the drop handler keeps it from showing.
 */
class DropLineTree : SimpleTree() {
    lateinit var trailer: SessionTrailer
    var drop: Drop? = null
        set(value) {
            field = value
            repaint()
        }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        trailer.paint(g)
        val drop = drop ?: return
        g.color = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR
        if (drop.childIndex >= 0) insertLine(this, drop.path, drop.childIndex).let { g.fillRect(it.x, it.y - 1, it.width, 2) }
        else getPathBounds(drop.path)?.let { g.drawRect(it.x, it.y, width - it.x - 1, it.height - 1) }
    }
}

/**
 * The line for a drop between the children of [parent] at [childIndex]: above that child's row, or below the last row of
 * the parent's subtree when dropping after its last child, indented like the children.
 */
fun insertLine(tree: JTree, parent: TreePath, childIndex: Int): Rectangle {
    val node = parent.lastPathComponent as TreeNode
    fun line(x: Int, y: Int) = Rectangle(x, y, tree.width - x, 0)
    if (childIndex < node.childCount) {
        val row = checkNotNull(tree.getPathBounds(parent.pathByAddingChild(node.getChildAt(childIndex)))) { "drop into collapsed $parent" }
        return line(row.x, row.y)
    }
    if (node.childCount == 0) {
        val row = checkNotNull(tree.getPathBounds(parent)) { "drop into hidden $parent" }
        return line(row.x + row.height, row.y + row.height)
    }
    val last = parent.pathByAddingChild(node.getChildAt(node.childCount - 1))
    var bottom = tree.getRowForPath(last)
    while (bottom + 1 < tree.rowCount && last.isDescendant(tree.getPathForRow(bottom + 1))) bottom++
    return line(checkNotNull(tree.getPathBounds(last)) { "drop into collapsed $parent" }.x, tree.getRowBounds(bottom).let { it.y + it.height })
}
