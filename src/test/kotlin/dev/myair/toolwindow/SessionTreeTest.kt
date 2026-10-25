package dev.myair.toolwindow

import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.TaskStatus
import dev.myair.folders.Folder
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionTreeTest {
    private fun session(id: String, cwd: String, time: Long) = ClaudeSession(id, Path.of(cwd), "title $id", Instant.ofEpochMilli(time), emptyList(), emptyList())

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
    fun `a session's brief comes first among its children, goal then tasks, then its added directories and changed files`() {
        val briefed = session("s1", "/rtm", 1).copy(
            changedFiles = listOf(Path.of("/rtm/a.kt")), addedDirs = listOf(Path.of("/plans")),
            brief = Brief("Migrate", listOf(BriefTask("export", TaskStatus.COMPLETED), BriefTask("map", TaskStatus.IN_PROGRESS))),
        )
        val goalless = session("s2", "/rtm", 0).copy(brief = Brief(null, listOf(BriefTask("run", TaskStatus.PENDING))))
        assertEquals(
            "${Path.of("/rtm")}(s1(goal s1, task s1 0, task s1 1, dir s1 ${Path.of("/plans")}, ${Path.of("/rtm/a.kt")}), s2(task s2 0))",
            shape(projectsTree(listOf(briefed, goalless))),
        )
    }

    @Test
    fun `a filter keeps matching sessions and their folders, hides empty folders, and always keeps sessions`() {
        assertEquals("rtm(s1), sessions", shape(foldersTree(structure, sessions, "title s1")))
    }

    @Test
    fun `a dropped folder goes before the folder it lands in front of, last above sessions, or last inside a folder`() {
        val root = foldersTree(structure, sessions, "")
        val rtm = TreePath(arrayOf(root, root.getChildAt(0)))
        fun drop(id: String, path: TreePath, childIndex: Int) = dropChange(structure, listOf(structure.find(id)!!), path, childIndex)
        fun names(drop: Drop?, parentId: String?) = drop!!.change(structure).children(parentId).map { it.name }
        assertEquals(listOf("experiments", "rtm"), names(drop("rtm", TreePath(root), 2), null))
        assertEquals(listOf("experiments", "rtm"), names(drop("empty", TreePath(root), 0), null))
        assertEquals(listOf("fixes", "experiments"), names(drop("empty", rtm, -1), "rtm"))
        assertEquals(listOf("rtm", "fixes", "experiments"), names(drop("fix", TreePath(root), 1), null))
        assertNull(drop("rtm", rtm, -1))
        assertNull(dropChange(structure.createFolder(null, "f2", "fixes"), listOf(structure.find("fix")!!), TreePath(root), 0))
        assertNull(dropChange(structure.createFolder(null, "f2", "Fixes"), listOf(Folder("f2", "Fixes")), rtm, -1))
    }

    @Test
    fun `a folder drop shows where the folder lands, not where the pointer is`() {
        val root = foldersTree(structure, sessions, "")
        val rtm = TreePath(arrayOf(root, root.getChildAt(0)))
        fun drop(path: TreePath, childIndex: Int) = dropChange(structure, listOf(structure.find("empty")!!), path, childIndex)!!
        fun shown(path: TreePath, childIndex: Int) = drop(path, childIndex).let { it.path to it.childIndex }
        // Below `sessions` at the end of the tree: the folder goes last above `sessions`, so the line is there too.
        assertEquals(TreePath(root) to 2, shown(TreePath(root), 3))
        assertEquals(listOf("rtm", "experiments"), drop(TreePath(root), 3).change(structure).children(null).map { it.name })
        assertEquals(TreePath(root) to 0, shown(TreePath(root), 0))
        // Between rtm's sessions: the folder goes after rtm's last subfolder.
        assertEquals(rtm to 1, shown(rtm, 2))
        assertEquals(rtm to -1, shown(rtm, -1))
    }

    @Test
    fun `dropped sessions are filed in the folder they land on, shown as that folder, refused between top-level rows`() {
        val root = foldersTree(structure, sessions, "")
        val dragged = listOf(sessions[1], sessions[2])
        val rtm = TreePath(arrayOf(root, root.getChildAt(0)))
        val onSession = dropChange(structure, dragged, rtm.pathByAddingChild(root.getChildAt(0).getChildAt(1)), -1)!!
        assertEquals(listOf("rtm", "rtm"), dragged.map { onSession.change(structure).folderOf(it.id) })
        assertEquals(rtm to -1, onSession.path to onSession.childIndex)
        val unfiled = TreePath(arrayOf(root, root.getChildAt(2)))
        val underSessions = dropChange(structure, dragged, unfiled.pathByAddingChild(root.getChildAt(2).getChildAt(0)), 0)!!
        assertEquals(listOf(null, null), dragged.map { underSessions.change(structure).folderOf(it.id) })
        assertEquals(unfiled to -1, underSessions.path to underSessions.childIndex)
        assertNull(dropChange(structure, dragged, TreePath(root), 1))
    }

    @Test
    fun `a selected folder or session points into its folder, and into none under sessions`() {
        val root = foldersTree(structure, sessions, "")
        fun pathTo(vararg indices: Int) = TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())
        assertEquals("fix", targetFolder(pathTo(0, 0)))
        assertEquals("rtm", targetFolder(pathTo(0, 1)))
        assertEquals("empty", targetFolder(pathTo(1)))
        assertNull(targetFolder(pathTo(2, 0, 0)))
    }

    @Test
    fun `a bound project's folder holds its subfolders, then its sessions filed nowhere else, and leaves sessions`() {
        val bound = structure.createFolder("empty", "p", "rtm", project = Path.of("/rtm").toString()).createFolder("p", "sub", "sub")
            .assign("s1", null).assign("s4", "sub")
        val all = sessions + session("s4", "/rtm", 4)
        assertEquals("rtm(fix, s3), empty(p(sub(s4), s1)), sessions(${Path.of("/tf")}(s2))", shape(foldersTree(bound, all, "")))
    }

    @Test
    fun `a project dropped on a folder becomes a folder bound to it, refused where its name is taken or it is bound already`() {
        val root = foldersTree(structure, sessions, "")
        val empty = TreePath(arrayOf(root, root.getChildAt(1)))
        val tf = Path.of("/tf")
        val dropped = dropChange(structure, listOf(tf), empty, -1)!!.change(structure)
        val folder = dropped.children("empty").single()
        assertEquals("tf" to tf.toString(), folder.name to folder.project)
        assertEquals(listOf("tf", "rtm", "experiments"), dropChange(structure, listOf(tf), TreePath(root), 0)!!.change(structure).children(null).map { it.name })
        assertNull(dropChange(dropped, listOf(tf), TreePath(root), 0))
        assertNull(dropChange(structure, listOf(Path.of("/x/rtm")), TreePath(root), 0))
    }

    @Test
    fun `a session of a bound project cannot be dropped back on sessions`() {
        val bound = structure.createFolder(null, "p", "tf", project = Path.of("/tf").toString())
        val root = foldersTree(bound, sessions, "")
        val unfiled = TreePath(arrayOf(root, root.lastChild))
        assertNull(dropChange(bound, listOf(sessions[1]), unfiled, -1))
        assertEquals(null, dropChange(bound, listOf(sessions[0]), unfiled, -1)!!.change(bound).placeOf(sessions[0]))
    }

    @Test
    fun `sessions in a project's worktrees join its bound folder and stay under sessions while it is unbound`() {
        val worktree = Path.of("/tf/.claude/worktrees/a5bbab5e")
        val all = listOf(session("s4", worktree.toString(), 4)) + sessions
        assertEquals("rtm(fix, s3, s1), empty, sessions($worktree(s4), ${Path.of("/tf")}(s2))", shape(foldersTree(structure, all, "")))
        val bound = structure.createFolder(null, "p", "tf", project = Path.of("/tf").toString())
        val root = foldersTree(bound, all, "")
        assertEquals("rtm(fix, s3, s1), empty, p(s4, s2), sessions", shape(root))
        assertNull(dropChange(bound, listOf(all.first()), TreePath(arrayOf(root, root.lastChild)), -1))
    }
}
