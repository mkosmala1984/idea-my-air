package dev.myair.toolwindow

import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.util.JDOMUtil
import dev.myair.claude.ClaudeSession
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** `TreeState.applyTo` needs the platform's application, so these read what a state holds from its XML. */
class TreeStatesTest {
    /** Root › projects a and b, each holding a folder holding a session. */
    private val tree = JTree(DefaultTreeModel(DefaultMutableTreeNode("root").apply {
        for (name in listOf("a", "b")) add(DefaultMutableTreeNode(name).apply {
            add(DefaultMutableTreeNode("$name-folder").apply { add(DefaultMutableTreeNode("$name-session")) })
        })
    })).apply { isRootVisible = false }

    private fun path(vararg names: String): TreePath {
        var node = tree.model.root as DefaultMutableTreeNode
        val nodes = mutableListOf(node)
        for (name in names) {
            node = node.children().asSequence().map { it as DefaultMutableTreeNode }.single { it.userObject == name }
            nodes += node
        }
        return TreePath(nodes.toTypedArray())
    }

    /** The node names of each expanded path in [xml], one path a list. */
    private fun expanded(xml: String) = JDOMUtil.load(xml).getChild("expand")?.getChildren("path").orEmpty()
        .map { path -> path.getChildren("item").map { it.getAttributeValue("name") } }

    @Test
    fun `a state written as XML and read back holds the same expanded nodes`() {
        tree.expandPath(path("a", "a-folder"))
        val xml = treeStateXml(TreeState.createOn(tree))
        assertEquals(xml, treeStateXml(treeStateOf(xml)))
        assertEquals(listOf(listOf("root", "a"), listOf("root", "a", "a-folder")), expanded(xml))
    }

    @Test
    fun `the rows of the session tree are named the same in a tree built anew, as after a restart`() {
        fun built(): String {
            val sessions = listOf(
                ClaudeSession("s2", Path.of("/tf"), "two", Instant.ofEpochMilli(2), emptyList(), emptyList()),
                ClaudeSession("s1", Path.of("/rtm"), "one", Instant.ofEpochMilli(1), emptyList(), emptyList()),
            )
            val structure = SessionFolders().createFolder(null, "rtm", "rtm").assign("s1", "rtm")
            val tree = JTree(DefaultTreeModel(foldersTree(structure, sessions, { null }, null))).apply { isRootVisible = false }
            for (row in generateSequence(0) { it + 1 }) {
                if (row >= tree.rowCount) break
                tree.expandRow(row)
            }
            return treeStateXml(TreeState.createOn(tree))
        }
        val xml = built()
        assertEquals(xml, built())
        val names = expanded(xml).flatten().toSet()
        // The root, a folder, the recent and unfiled groups, a project and the sessions, which always hold their work dir.
        assertEquals(setOf("", "rtm", "s1", "recent", "s2", "sessions", Path.of("/tf").toString()), names)
    }

    @Test
    fun `no saved state holds no expanded node`() {
        assertEquals(emptyList<List<String>>(), expanded(treeStateXml(treeStateOf(null))))
    }
}
