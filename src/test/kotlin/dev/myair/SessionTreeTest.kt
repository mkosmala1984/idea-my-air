package dev.myair

import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionTreeTest {
    private fun session(id: String, cwd: String, time: Long) = ClaudeSession(id, Path.of(cwd), "title $id", FileTime.fromMillis(time), emptyList())

    // Newest first, as the scanner returns them.
    private val sessions = listOf(session("s3", "/rtm", 3), session("s2", "/tf", 2), session("s1", "/rtm", 1))

    private val structure = SessionFolders()
        .createFolder(null, "rtm", "rtm")
        .createFolder("rtm", "fix", "fixes")
        .createFolder(null, "empty", "experiments")
        .assign("s1", "rtm").assign("s3", "rtm").assign("gone", "fix")

    /** The tree as `item(children), item`, so a whole tree compares in one assertion. */
    private fun shape(node: DefaultMutableTreeNode): String = node.children().toList().joinToString(", ") { child ->
        (child as DefaultMutableTreeNode).let { "${it.userObject}" + if (it.childCount > 0) "(${shape(it)})" else "" }
    }

    @Test
    fun `filed sessions sit in their folder newest first, the rest under sessions, missing ones are not shown`() {
        val root = foldersTree(structure, sessions, "")
        assertEquals("rtm(fix, s3, s1), empty, sessions(${Path.of("/tf")}(s2))", shape(root))
    }

    @Test
    fun `a filter keeps matching sessions and their folders, hides empty folders, and always keeps sessions`() {
        assertEquals("rtm(s1), sessions", shape(foldersTree(structure, sessions, "title s1")))
    }

    @Test
    fun `a dropped folder goes before the folder it lands in front of, last above sessions, or last inside a folder`() {
        val root = foldersTree(structure, sessions, "")
        val rtm = TreePath(arrayOf(root, root.getChildAt(0)))
        fun drop(id: String, path: TreePath, childIndex: Int) = dropChange(structure, listOf(structure.find(id)!!), path, childIndex)?.invoke(structure)
        assertEquals(listOf("experiments", "rtm"), drop("rtm", TreePath(root), 2)!!.children(null).map { it.name })
        assertEquals(listOf("experiments", "rtm"), drop("empty", TreePath(root), 0)!!.children(null).map { it.name })
        assertEquals(listOf("fixes", "experiments"), drop("empty", rtm, -1)!!.children("rtm").map { it.name })
        assertEquals(listOf("rtm", "fixes", "experiments"), drop("fix", TreePath(root), 1)!!.children(null).map { it.name })
        assertNull(drop("rtm", rtm, -1))
        assertNull(dropChange(structure.createFolder(null, "f2", "fixes"), listOf(structure.find("fix")!!), TreePath(root), 0))
        assertNull(dropChange(structure.createFolder(null, "f2", "Fixes"), listOf(Folder("f2", "Fixes")), rtm, -1))
    }

    @Test
    fun `dropped sessions are filed in the folder they land on, unassigned under sessions, refused between top-level rows`() {
        val root = foldersTree(structure, sessions, "")
        val dragged = listOf(sessions[1], sessions[2])
        val onRtm = dropChange(structure, dragged, TreePath(arrayOf(root, root.getChildAt(0))), -1)!!.invoke(structure)
        assertEquals(listOf("rtm", "rtm"), dragged.map { onRtm.folderOf(it.id) })
        val onSessions = dropChange(structure, dragged, TreePath(arrayOf(root, root.getChildAt(2))), 0)!!.invoke(structure)
        assertEquals(listOf(null, null), dragged.map { onSessions.folderOf(it.id) })
        assertNull(dropChange(structure, dragged, TreePath(root), 1))
    }

    @Test
    fun `new sessions go to the folder of the selected folder or session, and nowhere under sessions`() {
        val root = foldersTree(structure, sessions, "")
        fun pathTo(vararg indices: Int) = TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())
        assertEquals("fix", targetFolder(pathTo(0, 0)))
        assertEquals("rtm", targetFolder(pathTo(0, 1)))
        assertEquals("empty", targetFolder(pathTo(1)))
        assertNull(targetFolder(pathTo(2, 0, 0)))
    }
}
