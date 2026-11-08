package dev.myair.toolwindow

import dev.myair.actions.Git
import dev.myair.claude.Brief
import dev.myair.claude.BriefTask
import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.claude.TaskStatus
import dev.myair.folders.Folder
import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTreeTest {
    private fun session(id: String, cwd: String, time: Long) = ClaudeSession(id, Path.of(cwd), "title $id", Instant.ofEpochMilli(time), emptyList(), emptyList())

    private val NO_BRANCH: (Path) -> String? = { null }
    private val NO_WORKTREES: (Path) -> List<WorktreeRow> = { emptyList() }

    // Newest first, as the scanner returns them.
    private val sessions = listOf(session("s3", "/rtm", 3), session("s2", "/tf", 2), session("s1", "/rtm", 1))

    private val structure = SessionFolders()
        .createFolder(null, "rtm", "rtm")
        .createFolder("rtm", "fix", "fixes")
        .createFolder(null, "empty", "experiments")
        .assign("s1", "rtm").assign("s3", "rtm").assign("gone", "fix")

    /** The tree as `item(children), item`, so a whole tree compares in one assertion; a session's children only with [sessionChildren]. */
    private fun shape(node: DefaultMutableTreeNode, sessionChildren: Boolean = false): String = node.children().toList().joinToString(", ") { child ->
        (child as DefaultMutableTreeNode).let {
            "${it.userObject}" + if (it.childCount > 0 && (sessionChildren || it.userObject !is ClaudeSession)) "(${shape(it, sessionChildren)})" else ""
        }
    }

    @Test
    fun `filed sessions sit in their folder newest first, the rest under sessions, missing ones are not shown`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
        assertEquals("rtm(fix, s3, s1), empty, recent(s3, s2, s1), sessions(${Path.of("/tf")}(s2))", shape(root))
    }

    @Test
    fun `a session holds its work dir, added directories, brief, files other than outputs as groups, then its outputs`() {
        val briefed = session("s1", "/rtm", 1).copy(
            changedFiles = listOf(Path.of("/rtm/plan.md"), Path.of("/rtm/a.kt"), Path.of("/rtm/page.HTML"), Path.of("/rtm/b.kt")), addedDirs = listOf(Path.of("/plans")),
            brief = Brief("Migrate", listOf(BriefTask("export", TaskStatus.COMPLETED), BriefTask("map", TaskStatus.IN_PROGRESS))),
        )
        val goalless = session("s2", "/rtm", 0).copy(brief = Brief(null, listOf(BriefTask("run", TaskStatus.PENDING))))
        val bare = session("s3", "/rtm", 0)
        assertEquals(
            "${Path.of("/rtm")}(" +
                "s1(workdir s1, dir s1 ${Path.of("/plans")}, brief s1(goal s1, task s1 0, task s1 1), files s1(${Path.of("/rtm/a.kt")}, ${Path.of("/rtm/b.kt")}), ${Path.of("/rtm/plan.md")}, ${Path.of("/rtm/page.HTML")}), " +
                "s2(workdir s2, brief s2(task s2 0)), s3(workdir s3))",
            shape(projectsTree(listOf(briefed, goalless, bare), NO_BRANCH), sessionChildren = true),
        )
    }

    @Test
    fun `the work dir and each added directory are followed by the branch checked out there, when there is one`() {
        val (rtm, plans, docs) = listOf("/rtm", "/plans", "/docs").map(Path::of)
        val session = session("s1", "/rtm", 1).copy(addedDirs = listOf(plans, docs))
        val root = projectsTree(listOf(session), mapOf(rtm to "main", plans to null, docs to "notes")::getValue)
        assertEquals("$rtm(s1(workdir s1, branch s1 $rtm main, dir s1 $plans, dir s1 $docs, branch s1 $docs notes))", shape(root, sessionChildren = true))
        assertTrue(root.breadthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.filter { it.userObject is BranchRow }.all { folderSummary(it) { null } == null })
    }

    @Test
    fun `a filter keeps matching sessions and their folders, hides empty folders, and always keeps sessions`() {
        assertEquals("rtm(s1), recent(s1), sessions", shape(foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES) { it.matches("title s1", null) }))
    }

    @Test
    fun `a dropped folder goes before the folder it lands in front of, last above sessions, or last inside a folder`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
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
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
        val rtm = TreePath(arrayOf(root, root.getChildAt(0)))
        fun drop(path: TreePath, childIndex: Int) = dropChange(structure, listOf(structure.find("empty")!!), path, childIndex)!!
        fun shown(path: TreePath, childIndex: Int) = drop(path, childIndex).let { it.path to it.childIndex }
        // Below `sessions` at the end of the tree: the folder goes last above `recent`, so the line is there too.
        assertEquals(TreePath(root) to 2, shown(TreePath(root), 4))
        assertEquals(TreePath(root) to 2, shown(TreePath(root), 3))
        assertEquals(listOf("rtm", "experiments"), drop(TreePath(root), 4).change(structure).children(null).map { it.name })
        assertEquals(TreePath(root) to 0, shown(TreePath(root), 0))
        // Between rtm's sessions: the folder goes after rtm's last subfolder.
        assertEquals(rtm to 1, shown(rtm, 2))
        assertEquals(rtm to -1, shown(rtm, -1))
    }

    @Test
    fun `dropped sessions are filed in the folder they land on, shown as that folder, refused between top-level rows`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
        val dragged = listOf(sessions[1], sessions[2])
        val rtm = TreePath(arrayOf(root, root.getChildAt(0)))
        val onSession = dropChange(structure, dragged, rtm.pathByAddingChild(root.getChildAt(0).getChildAt(1)), -1)!!
        assertEquals(listOf("rtm", "rtm"), dragged.map { onSession.change(structure).folderOf(it.id) })
        assertEquals(rtm to -1, onSession.path to onSession.childIndex)
        val unfiled = TreePath(arrayOf(root, root.getChildAt(3)))
        val underSessions = dropChange(structure, dragged, unfiled.pathByAddingChild(root.getChildAt(3).getChildAt(0)), 0)!!
        assertEquals(listOf(null, null), dragged.map { underSessions.change(structure).folderOf(it.id) })
        assertEquals(unfiled to -1, underSessions.path to underSessions.childIndex)
        assertNull(dropChange(structure, dragged, TreePath(root), 1))
    }

    @Test
    fun `a selected folder or session points into its folder, and into none under sessions`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
        fun pathTo(vararg indices: Int) = TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())
        assertEquals("fix", targetFolder(pathTo(0, 0)))
        assertEquals("rtm", targetFolder(pathTo(0, 1)))
        assertEquals("empty", targetFolder(pathTo(1)))
        assertNull(targetFolder(pathTo(3, 0, 0)))
    }

    @Test
    fun `a project row and a folder in a bound project give a directory for a new session, other nodes none`() {
        val bound = structure.createFolder("empty", "p", "rtm", dirs = listOf(Path.of("/rtm").toString())).createFolder("p", "sub", "sub")
        val root = foldersTree(bound, sessions, NO_BRANCH, NO_WORKTREES, null)
        fun pathTo(vararg indices: Int) = TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())
        assertEquals("rtm(fix, s3, s1), empty(p(sub)), recent(s3, s2, s1), sessions(${Path.of("/tf")}(s2))", shape(root))
        assertEquals(Path.of("/rtm"), nodeDir(pathTo(1, 0)))
        assertEquals(Path.of("/rtm"), nodeDir(pathTo(1, 0, 0)))
        assertEquals(Path.of("/tf"), nodeDir(pathTo(3, 0)))
        assertNull(nodeDir(pathTo(0)))
        assertNull(nodeDir(pathTo(0, 1)))
        assertNull(nodeDir(pathTo(1)))
        assertNull(nodeDir(pathTo(2)))
        assertNull(nodeDir(pathTo(2, 0)))
        assertNull(nodeDir(pathTo(3)))
        assertNull(nodeDir(pathTo(3, 0, 0)))
        val projects = projectsTree(sessions, NO_BRANCH)
        assertEquals(Path.of("/rtm"), nodeDir(TreePath(arrayOf(projects, projects.getChildAt(0)))))
    }

    @Test
    fun `a folder's working directory gives the directory below it, a nearer one or binding winning, kept though missing`() {
        val work = Path.of("/work").toString()
        val bound = structure.setWorkdir("empty", work).createFolder("empty", "p", "rtm", dirs = listOf(Path.of("/rtm").toString())).createFolder("p", "sub", "sub")
        fun dirs(folders: SessionFolders): (IntArray) -> Path? {
            val root = foldersTree(folders, sessions, NO_BRANCH, NO_WORKTREES, null)
            return { indices -> nodeDir(TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())) }
        }
        assertEquals(Path.of("/work"), dirs(bound)(intArrayOf(1)))
        assertEquals(Path.of("/rtm"), dirs(bound)(intArrayOf(1, 0)))
        assertEquals(Path.of("/rtm"), dirs(bound)(intArrayOf(1, 0, 0)))
        val worked = bound.setWorkdir("p", Path.of("/gone").toString())
        assertEquals(Path.of("/gone"), dirs(worked)(intArrayOf(1, 0, 0)))
        val planned = structure.setWorkdir("empty", work).createFolder("empty", "plan", "plan", plan = Plan()).addMilestone("plan", "first")
        val withTask = planned.addTask(planned.find("plan")!!.plan!!.milestones.single().id, "task")
        assertEquals("rtm(fix, s3, s1), empty(plan(M1(T2))), recent(s3, s2, s1), sessions(${Path.of("/tf")}(s2))", shape(foldersTree(withTask, sessions, NO_BRANCH, NO_WORKTREES, null)))
        assertEquals(Path.of("/work"), dirs(withTask)(intArrayOf(1, 0, 0, 0)))
    }

    @Test
    fun `a session row opens its own cwd, other nodes as for a new session`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
        fun pathTo(vararg indices: Int) = TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())
        assertEquals(Path.of("/tf"), openDir(pathTo(3, 0, 0)))
        assertEquals(Path.of("/tf"), openDir(pathTo(3, 0)))
        assertNull(openDir(pathTo(0)))
        assertNull(nodeDir(pathTo(3, 0, 0)))
    }

    @Test
    fun `a bound project's folder holds its subfolders, then its sessions filed nowhere else, and leaves sessions`() {
        val bound = structure.createFolder("empty", "p", "rtm", dirs = listOf(Path.of("/rtm").toString())).createFolder("p", "sub", "sub")
            .assign("s1", null).assign("s4", "sub")
        val all = listOf(session("s4", "/rtm", 4)) + sessions
        assertEquals("rtm(fix, s3), empty(p(sub(s4), s1)), recent(s4, s3, s2, s1), sessions(${Path.of("/tf")}(s2))", shape(foldersTree(bound, all, NO_BRANCH, NO_WORKTREES, null)))
    }

    @Test
    fun `a project dropped on a folder becomes a folder bound to it, refused where its name is taken or it is bound already`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES, null)
        val empty = TreePath(arrayOf(root, root.getChildAt(1)))
        val tf = Path.of("/tf")
        val dropped = dropChange(structure, listOf(tf), empty, -1)!!.change(structure)
        val folder = dropped.children("empty").single()
        assertEquals("tf" to tf.toString(), folder.name to folder.dirs.single())
        assertEquals(listOf("tf", "rtm", "experiments"), dropChange(structure, listOf(tf), TreePath(root), 0)!!.change(structure).children(null).map { it.name })
        assertNull(dropChange(dropped, listOf(tf), TreePath(root), 0))
        assertNull(dropChange(structure, listOf(Path.of("/x/rtm")), TreePath(root), 0))
    }

    /** [structure] with rtm made a plan folder bound to /rtm: M4 holds T5 and T6, M7 none; s3 names T5. */
    private val planned = structure.bind("rtm", Path.of("/rtm").toString()).addPlan("rtm")
        .addMilestone("rtm", "Export").addTask("M1", "Fields").addTask("M1", "Checks").addMilestone("rtm", "Import")
    private val tasked = listOf(sessions[0].copy(brief = Brief(null, emptyList(), "T2"))) + sessions.drop(1)

    @Test
    fun `a plan folder holds subfolders, milestones with their tasks and sessions, then no task with its other sessions`() {
        assertEquals("rtm(fix, M1(T2(s3), T3), M4, no task rtm(s1)), empty, recent(s3, s2, s1), sessions(${Path.of("/tf")}(s2))", shape(foldersTree(planned, tasked, NO_BRANCH, NO_WORKTREES, null)))
        // A task wins over filing, and a task deleted lets the session fall back to its folder.
        assertEquals("rtm(fix, M1(T3), M4, no task rtm(s3, s1)), empty, recent(s3, s2, s1), sessions(${Path.of("/tf")}(s2))", shape(foldersTree(planned.deletePlanItem("T2"), tasked, NO_BRANCH, NO_WORKTREES, null)))
        assertEquals("rtm(M1(T2(s3))), recent(s3), sessions", shape(foldersTree(planned, tasked, NO_BRANCH, NO_WORKTREES) { it.matches("s3", null) }))
    }

    @Test
    fun `milestones and tasks drop within their plan, sessions neither onto them nor away from their task`() {
        val root = foldersTree(planned, tasked, NO_BRANCH, NO_WORKTREES, null)
        fun pathTo(vararg indices: Int) = TreePath(indices.fold(listOf(root)) { nodes, i -> nodes + nodes.last().getChildAt(i) as DefaultMutableTreeNode }.toTypedArray())
        fun item(vararg indices: Int) = (pathTo(*indices).lastPathComponent as DefaultMutableTreeNode).userObject
        val rtm = pathTo(0)
        val m1 = pathTo(0, 1)
        fun milestones(s: SessionFolders) = s.find("rtm")!!.plan!!.milestones.map { m -> m.id + m.tasks.map { it.id } }
        assertEquals(listOf("M4[]", "M1[T2, T3]"), milestones(dropChange(planned, listOf(item(0, 1)), rtm, 4)!!.change(planned)))
        assertEquals(listOf("M1[T3, T2]", "M4[]"), milestones(dropChange(planned, listOf(item(0, 1, 0)), m1, -1)!!.change(planned)))
        assertEquals(listOf("M1[T3]", "M4[T2]"), milestones(dropChange(planned, listOf(item(0, 1, 0)), pathTo(0, 2), -1)!!.change(planned)))
        assertNull(dropChange(planned, listOf(item(0, 1)), pathTo(1), -1))
        assertNull(dropChange(planned, listOf(item(0, 1, 0)), rtm, 0))
        assertNull(dropChange(planned, listOf(tasked[2]), m1, -1))
        assertNull(dropChange(planned, listOf(tasked[0]), pathTo(1), -1))
        assertEquals("rtm", dropChange(planned, listOf(tasked[1]), pathTo(0, 3), -1)!!.change(planned).folderOf("s2"))
        val other = planned.addPlan("empty")
        assertNull(dropChange(other, listOf(other.find("empty")!!), rtm, -1))
    }

    @Test
    fun `a session dropped on sessions stays there, a bound project's too`() {
        val bound = structure.createFolder(null, "p", "tf", dirs = listOf(Path.of("/tf").toString()))
        val root = foldersTree(bound, sessions, NO_BRANCH, NO_WORKTREES, null)
        val unfiled = TreePath(arrayOf(root, root.lastChild))
        val dragged = listOf(sessions[0], sessions[1])
        val after = dropChange(bound, dragged, unfiled, -1)!!.change(bound)
        assertEquals(dragged.map { it.id }, after.unfiled)
        assertEquals(listOf(null, null), dragged.map { after.folderOf(it.id) })
        assertEquals(listOf(null, null), dragged.map { after.placeOf(it) })
    }

    @Test
    fun `sessions in a project's worktrees join its bound folder and stay under sessions while it is unbound`() {
        val worktree = Path.of("/tf/.claude/worktrees/a5bbab5e")
        val all = listOf(session("s4", worktree.toString(), 4)) + sessions
        assertEquals("rtm(fix, s3, s1), empty, recent(s4, s3, s2, s1), sessions($worktree(s4), ${Path.of("/tf")}(s2))", shape(foldersTree(structure, all, NO_BRANCH, NO_WORKTREES, null)))
        val bound = structure.createFolder(null, "p", "tf", dirs = listOf(Path.of("/tf").toString()))
        val root = foldersTree(bound, all, NO_BRANCH, NO_WORKTREES, null)
        assertEquals("rtm(fix, s3, s1), empty, p(s4, s2), recent(s4, s3, s2, s1), sessions", shape(root))
        assertEquals(null, dropChange(bound, listOf(all.first()), TreePath(arrayOf(root, root.lastChild)), -1)!!.change(bound).placeOf(all.first()))
    }

    @Test
    fun `recent holds the ten newest sessions wherever they are filed, and takes no drop`() {
        val many = (20 downTo 1).map { session("n$it", "/rtm", it.toLong()) }
        val root = foldersTree(SessionFolders(), many, NO_BRANCH, NO_WORKTREES, null)
        val recent = root.getChildAt(0) as DefaultMutableTreeNode
        assertEquals(RecentSessions, recent.userObject)
        assertEquals((20 downTo 11).map { "n$it" }, recent.children().toList().map { "${(it as DefaultMutableTreeNode).userObject}" })
        val path = TreePath(arrayOf(root, recent))
        assertNull(dropChange(SessionFolders(), listOf(many[15]), path, -1))
        assertNull(dropChange(SessionFolders(), listOf(many[15]), path.pathByAddingChild(recent.getChildAt(0)), -1))
        assertNull(dropChange(structure, listOf(structure.find("rtm")!!), path, -1))
        assertNull(targetFolder(path.pathByAddingChild(recent.getChildAt(0))))
    }

    /** The node whose user object prints as [id]. */
    private fun find(root: DefaultMutableTreeNode, id: String) =
        root.breadthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.first { "${it.userObject}" == id }

    @Test
    fun `a folder counts the sessions below it, subfolders included, and names the most urgent`() {
        val nested = structure.assign("s3", "fix").assign("s2", "fix")
        val urgency = mapOf("s1" to Urgency.WORKING, "s3" to Urgency.NEEDS_INPUT)
        val root = foldersTree(nested, sessions, NO_BRANCH, NO_WORKTREES, null)
        assertEquals(FolderSummary(3, Urgency.NEEDS_INPUT), folderSummary(find(root, "rtm")) { urgency[it.id] })
        assertEquals(FolderSummary(2, Urgency.NEEDS_INPUT), folderSummary(find(root, "fix")) { urgency[it.id] })
        assertEquals(FolderSummary(0, null), folderSummary(find(root, "empty")) { urgency[it.id] })
        assertEquals(FolderSummary(3, Urgency.NEEDS_INPUT), folderSummary(find(root, "recent")) { urgency[it.id] })
    }

    @Test
    fun `a session and its children get no summary`() {
        val briefed = session("s1", "/rtm", 1).copy(
            changedFiles = listOf(Path.of("/rtm/a.kt")), addedDirs = listOf(Path.of("/plans")),
            brief = Brief("Migrate", listOf(BriefTask("export", TaskStatus.COMPLETED))),
        )
        val root = projectsTree(listOf(briefed), NO_BRANCH)
        val nodes = root.breadthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.drop(2)
        // The session, its work dir, added directory, brief group with goal and task, and files group with its file.
        assertEquals(8, nodes.size)
        assertTrue(nodes.all { folderSummary(it) { null } == null })
        assertEquals(FolderSummary(1, null), folderSummary(find(root, "${Path.of("/rtm")}")) { null })
    }

    @Test
    fun `a filtered tree counts only the sessions it shows`() {
        val root = foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES) { it.matches("title s1", null) }
        assertEquals(FolderSummary(1, null), folderSummary(find(root, "rtm")) { null })
        assertEquals(FolderSummary(0, null), folderSummary(find(root, "sessions")) { null })
    }

    @Test
    fun `a session is in the work tree it works or started in, by root, not by path`() {
        val main = Path.of("/m")
        val wt = Path.of("/m/.claude/worktrees/w")
        val heads = mapOf(
            main to Git.Head(main, "main"), Path.of("/m/src") to Git.Head(main, "main"),
            wt to Git.Head(wt, "worktree-w"), Path.of("/gone") to null, Path.of("/other") to Git.Head(Path.of("/other"), "main"),
        )
        fun s(cwd: String, workDir: String = cwd) = session("s", cwd, 0).copy(workDir = Path.of(workDir))
        val cases = listOf(
            s("/m", "/m/src") to (true to false),
            s(wt.toString()) to (false to true),
            s("/m", wt.toString()) to (true to true),
            s("/gone") to (false to false),
            s("/other") to (false to false),
        )
        cases.forEach { (session, expected) ->
            assertEquals(expected, inWorkTree(session, main, heads) to inWorkTree(session, wt, heads), "${session.cwd} -> ${session.workDir}")
        }
        assertFailsWith<NoSuchElementException> { inWorkTree(s("/unread"), main, heads) }
    }

    @Test
    fun `the work tree filter leaves out folders without its sessions, as a search does`() {
        val heads = mapOf(Path.of("/rtm") to Git.Head(Path.of("/rtm"), "main"), Path.of("/tf") to Git.Head(Path.of("/tf"), "main"))
        assertEquals("rtm(s3, s1), recent(s3, s1), sessions", shape(foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES) { inWorkTree(it, Path.of("/rtm"), heads) }))
        assertEquals("recent(s2), sessions(${Path.of("/tf")}(s2))", shape(foldersTree(structure, sessions, NO_BRANCH, NO_WORKTREES) { inWorkTree(it, Path.of("/tf"), heads) }))
    }

    @Test
    fun `urgency follows the live status, unread only when idle`() {
        fun live(status: SessionStatus) = LiveSession(1, "cli", status, null, Instant.EPOCH, Path.of("/p"), Instant.EPOCH)
        assertEquals(Urgency.NEEDS_INPUT, urgencyOf(live(SessionStatus.NEEDS_INPUT), unread = true))
        assertEquals(Urgency.WORKING, urgencyOf(live(SessionStatus.WORKING), unread = true))
        assertEquals(Urgency.UNREAD, urgencyOf(live(SessionStatus.IDLE), unread = true))
        assertNull(urgencyOf(live(SessionStatus.IDLE), unread = false))
        assertNull(urgencyOf(null, unread = true))
    }

    private val appX = Path.of("/app/.claude/worktrees/x")
    private val wY = Path.of("/w/y")
    // Sessions in a worktree belong to the main checkout's project, as inProjects makes them.
    private fun inApp(id: String, cwd: Path, time: Long) = session(id, cwd.toString(), time).copy(project = Path.of("/app"))
    private val bySession = listOf(inApp("a1", appX, 2), inApp("a2", Path.of("/app"), 1))
    private val app = SessionFolders().createFolder(null, "app", "app", listOf(Path.of("/app").toString()))
    private val appWorktrees: (Path) -> List<WorktreeRow> = {
        if (it == Path.of("/app")) listOf(WorktreeRow(appX, Path.of("/app"), "feat", Instant.ofEpochMilli(1), false), WorktreeRow(wY, Path.of("/app"), null, Instant.ofEpochMilli(2), false)) else emptyList()
    }

    @Test
    fun `a bound folder lists its worktrees newest first, each with the sessions working in it, which also stay in the folder`() {
        assertEquals("app(worktrees app(worktree $wY, worktree $appX(a1)), a1, a2), recent(a1, a2), sessions", shape(foldersTree(app, bySession, NO_BRANCH, appWorktrees, null)))
    }

    @Test
    fun `a session's work dir in a worktree puts it under that worktree though it started elsewhere`() {
        val moved = listOf(inApp("a2", Path.of("/app"), 1).copy(workDir = wY.resolve("sub")))
        assertEquals("app(worktrees app(worktree $wY(a2), worktree $appX), a2), recent(a2), sessions", shape(foldersTree(app, moved, NO_BRANCH, appWorktrees, null)))
    }

    @Test
    fun `a folder bound to two repositories merges their worktrees newest first`() {
        val two = SessionFolders().createFolder(null, "app", "app", listOf(Path.of("/app").toString(), Path.of("/lib").toString()))
        val lib = Path.of("/l/z")
        val worktrees: (Path) -> List<WorktreeRow> = { if (it == Path.of("/lib")) listOf(WorktreeRow(lib, Path.of("/lib"), "main", Instant.ofEpochMilli(3), false)) else appWorktrees(it) }
        assertEquals("app(worktrees app(worktree $lib, worktree $wY, worktree $appX(a1)), a1, a2), recent(a1, a2), sessions", shape(foldersTree(two, bySession, NO_BRANCH, worktrees, null)))
    }

    @Test
    fun `a filter keeps a worktree only with a matching session`() {
        assertEquals("app(a2), recent(a2), sessions", shape(foldersTree(app, bySession, NO_BRANCH, appWorktrees) { it.matches("title a2", null) }))
        assertEquals("app(worktrees app(worktree $appX(a1)), a1), recent(a1), sessions", shape(foldersTree(app, bySession, NO_BRANCH, appWorktrees) { it.matches("title a1", null) }))
    }

    @Test
    fun `a session shown in its folder and its worktree counts once`() {
        val folder = foldersTree(app, bySession, NO_BRANCH, appWorktrees, null).getChildAt(0) as DefaultMutableTreeNode
        assertEquals(FolderSummary(2, null), folderSummary(folder) { null })
    }

    @Test
    fun `nothing drops into the worktrees group, and a worktree row starts sessions in its directory, of its main checkout's project`() {
        val root = foldersTree(app, bySession, NO_BRANCH, appWorktrees, null)
        val folder = root.getChildAt(0) as DefaultMutableTreeNode
        val group = folder.getChildAt(0) as DefaultMutableTreeNode
        val row = TreePath(arrayOf(root, folder, group, group.getChildAt(1)))
        assertNull(dropChange(app, listOf(bySession[1]), row, -1))
        assertNull(dropChange(app, listOf(bySession[1]), TreePath(arrayOf(root, folder, group)), 0))
        assertEquals(appX, nodeDir(row))
        assertEquals(appX, openDir(row))
        assertEquals(Path.of("/app"), nodeProject(row))
        assertEquals(Path.of("/app"), nodeProject(TreePath(arrayOf(root, folder))))
        assertNull(nodeDir(TreePath(arrayOf(root, folder, group))))
    }

    @Test
    fun `a worktree holds the sessions under it as text, as a bound folder does, so another case is another directory`() {
        val upper = listOf(inApp("a3", Path.of("/APP/.claude/worktrees/x"), 3))
        assertEquals("app(worktrees app(worktree $wY, worktree $appX), a3), recent(a3), sessions", shape(foldersTree(app, upper, NO_BRANCH, appWorktrees, null)))
    }
}
