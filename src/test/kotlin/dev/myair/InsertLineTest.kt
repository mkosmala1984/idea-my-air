package dev.myair

import org.junit.jupiter.api.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.test.assertEquals

class InsertLineTest {
    // root (hidden) -> a -> a1 -> a11, b (empty)
    private val a11 = DefaultMutableTreeNode("a11")
    private val a1 = DefaultMutableTreeNode("a1").apply { add(a11) }
    private val a = DefaultMutableTreeNode("a").apply { add(a1) }
    private val b = DefaultMutableTreeNode("b")
    private val root = DefaultMutableTreeNode("root").apply { add(a); add(b) }
    private val tree = JTree(DefaultTreeModel(root)).apply {
        isRootVisible = false
        expandPath(TreePath(arrayOf(root, a, a1)))
        setSize(300, 300)
    }

    private fun bounds(vararg nodes: Any) = tree.getPathBounds(TreePath(arrayOf(root, *nodes)))!!

    @Test
    fun `between rows the line sits on top of the row the drop lands before`() {
        val line = insertLine(tree, TreePath(root), 1)
        assertEquals(bounds(b).y, line.y)
        assertEquals(bounds(b).x, line.x)
        assertEquals(300 - line.x, line.width)
    }

    @Test
    fun `after the last child the line sits below the whole expanded subtree of that child`() {
        assertEquals(bounds(a, a1, a11).let { it.y + it.height }, insertLine(tree, TreePath(arrayOf(root, a)), 1).y)
        assertEquals(bounds(a, a1).x, insertLine(tree, TreePath(arrayOf(root, a)), 1).x)
        assertEquals(bounds(b).let { it.y + it.height }, insertLine(tree, TreePath(root), 2).y)
    }

    @Test
    fun `into an empty folder the line sits below it, indented`() {
        val line = insertLine(tree, TreePath(arrayOf(root, b)), 0)
        assertEquals(bounds(b).let { it.y + it.height }, line.y)
        assertEquals(bounds(b).let { it.x + it.height }, line.x)
    }
}
