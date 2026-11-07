package dev.myair.folders

import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionFoldersTest {
    /** rtm(migration, fixes), testflo — the user's own order, not alphabetical. */
    private val tree = SessionFolders()
        .createFolder(null, "rtm", "rtm")
        .createFolder("rtm", "mig", "rtm migration")
        .createFolder("rtm", "fix", "fixes")
        .createFolder(null, "tf", "testflo")

    private fun SessionFolders.names(parentId: String? = null) = children(parentId).map { it.name }

    @Test
    fun `new folders go last among their siblings`() {
        assertEquals(listOf("rtm", "testflo"), tree.names())
        assertEquals(listOf("rtm migration", "fixes"), tree.names("rtm"))
    }

    @Test
    fun `a session is filed in one folder at a time and returns to sessions when unassigned`() {
        val filed = tree.assign("s1", "mig").assign("s1", "fix")
        assertEquals("fix", filed.folderOf("s1"))
        assertTrue(filed.find("mig")!!.items.isEmpty())
        assertNull(filed.assign("s1", null).folderOf("s1"))
    }

    @Test
    fun `deleting a folder returns every session of its subtree to sessions`() {
        val filed = tree.assign("s1", "rtm").assign("s2", "fix").assign("s3", "tf")
        assertEquals(2, filed.sessionCount("rtm"))
        val deleted = filed.delete("rtm")
        assertEquals(listOf("testflo"), deleted.names())
        assertNull(deleted.folderOf("s1"))
        assertNull(deleted.folderOf("s2"))
        assertEquals("tf", deleted.folderOf("s3"))
    }

    @Test
    fun `a folder moves to a position counted before the move, within and across parents`() {
        assertEquals(listOf("fixes", "rtm migration"), tree.moveFolder("mig", "rtm", 2).names("rtm"))
        assertEquals(listOf("fixes", "rtm migration"), tree.moveFolder("fix", "rtm", 0).names("rtm"))
        val moved = tree.moveFolder("fix", null, 1)
        assertEquals(listOf("rtm", "fixes", "testflo"), moved.names())
        assertEquals(listOf("rtm migration"), moved.names("rtm"))
        val nested = tree.moveFolder("rtm", "tf", 0)
        assertEquals(listOf("testflo"), nested.names())
        assertEquals(listOf("rtm"), nested.names("tf"))
        assertEquals(listOf("rtm migration", "fixes"), nested.names("rtm"))
    }

    @Test
    fun `a folder cannot move into itself or its descendant`() {
        assertThrows<IllegalArgumentException> { tree.moveFolder("rtm", "rtm", 0) }
        assertThrows<IllegalArgumentException> { tree.moveFolder("rtm", "fix", 0) }
    }

    @Test
    fun `sibling names are unique ignoring case, and names are trimmed and non-blank`() {
        assertThrows<IllegalArgumentException> { tree.createFolder("rtm", "x", "Fixes") }
        assertThrows<IllegalArgumentException> { tree.rename("tf", "RTM") }
        assertThrows<IllegalArgumentException> { tree.createFolder("tf", "y", "fixes").moveFolder("y", "rtm", 0) }
        assertThrows<IllegalArgumentException> { tree.createFolder(null, "x", " ") }
        assertThrows<IllegalArgumentException> { tree.createFolder(null, "x", " padded") }
        assertTrue(tree.nameTaken("rtm", " FIXES "))
        assertTrue(!tree.nameTaken("rtm", "fixes", except = "fix"))
    }

    @Test
    fun `operations on a folder another IDE deleted change nothing`() {
        assertSame(tree, tree.createFolder("gone", "x", "x"))
        assertSame(tree, tree.rename("gone", "x"))
        assertSame(tree, tree.delete("gone"))
        assertSame(tree, tree.moveFolder("gone", null, 0))
        assertSame(tree, tree.moveFolder("rtm", "gone", 0))
        assertSame(tree, tree.assign("s1", "gone"))
    }

    @Test
    fun `clear carries the folder over to a session no scan has seen, resume does not move anything`() {
        val filed = tree.assign("old", "fix").assign("elsewhere", "tf")
        assertEquals("fix", filed.carryOver("old", "new", scanned = setOf("old")).folderOf("new"))
        assertSame(filed, filed.carryOver("old", "resumed", scanned = setOf("old", "resumed")))
        assertSame(filed, filed.carryOver("old", "elsewhere", scanned = setOf("old")))
        assertSame(filed, filed.carryOver("unfiled", "new", scanned = emptySet()))
    }

    @Test
    fun `json round-trips in the user's order`() {
        val filed = tree.assign("s1", "fix")
        assertEquals(filed, SessionFolders.parse(filed.toJson()))
    }

    @Test
    fun `a project binds to one folder, which holds its sessions filed nowhere else`() {
        val bound = tree.createFolder("tf", "app", "app", dirs = listOf(Path.of("/app").toString())).assign("s2", "fix")
        fun at(id: String) = bound.placeOf(ClaudeSession(id, Path.of("/app"), id, java.time.Instant.EPOCH, emptyList(), emptyList()))
        assertEquals("app", at("s1"))
        assertEquals("fix", at("s2"))
        assertEquals(bound, SessionFolders.parse(bound.toJson()))
        assertThrows<IllegalArgumentException> { bound.createFolder(null, "x", "x", dirs = listOf(Path.of("/app").toString())) }
        assertEquals("x", bound.createFolder(null, "x", "x", dirs = listOf(Path.of("/APP").toString())).projectFolder(Path.of("/APP"))?.id)
        assertNull(bound.delete("tf").placeOf(ClaudeSession("s1", Path.of("/app"), "s1", java.time.Instant.EPOCH, emptyList(), emptyList())))
    }

    @Test
    fun `a bound folder holds the sessions of directories under its project, the most specific binding winning`() {
        val app = Path.of("/app")
        val worktree = app.resolve(".claude/worktrees/a5bbab5e")
        val bound = tree.createFolder(null, "app", "app", dirs = listOf(app.toString()))
        fun at(structure: SessionFolders, cwd: Path) = structure.placeOf(ClaudeSession("s", cwd, "s", java.time.Instant.EPOCH, emptyList(), emptyList()))
        assertEquals("app", at(bound, worktree))
        assertEquals("app", at(bound, worktree.resolve("taskbar")))
        assertNull(at(bound, Path.of("/application")))
        assertNull(at(bound, Path.of("/")))
        assertEquals("wt", at(bound.createFolder(null, "wt", "wt", dirs = listOf(worktree.toString())), worktree))
        assertEquals("wt", at(SessionFolders().createFolder(null, "wt", "wt", dirs = listOf(worktree.toString())).createFolder(null, "app", "app", dirs = listOf(app.toString())), worktree))
        val outside = ClaudeSession("s", Path.of("/worktrees/a5bbab5e"), "s", java.time.Instant.EPOCH, emptyList(), emptyList(), project = app)
        assertEquals("app", bound.placeOf(outside))
    }

    @Test
    fun `a version 1 file reads as folders without projects`() {
        assertEquals(SessionFolders().createFolder(null, "a", "a"), SessionFolders.parse("""{"version":1,"folders":[{"id":"a","name":"a","folders":[],"items":[]}]}"""))
    }

    @Test
    fun `a malformed file throws rather than being skipped or repaired`() {
        val folder = """{"id":"a","name":"a","folders":[],"items":[]}"""
        listOf(
            """{"folders":[]}""",
            """{"version":5,"nextId":1,"folders":[],"unfiled":[]}""",
            """{"version":4,"nextId":1,"folders":[]}""",
            """{"version":1,"folders":[{"id":"a","name":"a","folders":[]}]}""",
            """{"version":1,"folders":[{"id":"a","name":"a","folders":[],"items":[{"type":"jira","id":"PW-1"}]}]}""",
            """{"version":1,"folders":[$folder,$folder]}""",
            """{"version":1,"folders":[{"id":"a","name":"a","folders":[],"items":[{"type":"session","id":"s"}]},""" +
                """{"id":"b","name":"b","folders":[],"items":[{"type":"session","id":"s"}]}]}""",
            "not json",
        ).forEach { text -> assertThrows<Exception>(text) { SessionFolders.parse(text) } }
    }

    @Test
    fun `the store reads a missing file as empty and keeps a change another IDE wrote in between`(@TempDir root: Path) {
        val file = root.resolve("my-air/structure.json")
        val mine = StructureStore(file)
        assertEquals(SessionFolders(), mine.read())
        mine.update(ChangeSource.UI) { it.createFolder(null, "a", "a") }
        StructureStore(file).update(ChangeSource.UI) { it.createFolder(null, "b", "b") }
        mine.update(ChangeSource.UI) { it.assign("s1", "a") }
        assertEquals(listOf("a", "b"), mine.read().names())
        assertEquals("a", mine.read().folderOf("s1"))
        assertEquals(emptyList(), file.parent.listDirectoryEntries("*.tmp"))
    }

    @Test
    fun `the store refuses to write over a file it cannot read`(@TempDir root: Path) {
        val file = root.resolve("structure.json")
        file.writeText("broken")
        assertThrows<Exception> { StructureStore(file).update(ChangeSource.UI) { it.createFolder(null, "a", "a") } }
        assertEquals("broken", file.toFile().readText())
    }

    private fun briefed(id: String, folder: String?) =
        ClaudeSession(id, Path.of("/app"), id, java.time.Instant.EPOCH, emptyList(), emptyList(), brief = dev.myair.claude.Brief("g", emptyList(), folder = folder))

    private val appTree = tree.createFolder(null, "app", "app", dirs = listOf(Path.of("/app").toString()))

    @Test
    fun `placeOf follows the brief's folder below hand filing and nowhere, above the project`() {
        val s = briefed("s", "fix")
        assertEquals("fix", appTree.placeOf(s))
        assertEquals("rtm", appTree.assign("s", "rtm").placeOf(s))
        val nowhere = appTree.assign("s", "rtm").unfile("s")
        assertNull(nowhere.placeOf(s))
        assertEquals(listOf("s"), nowhere.unfiled)
        assertNull(nowhere.folderOf("s"))
        val refiled = nowhere.assign("s", "fix")
        assertEquals("fix", refiled.placeOf(s))
        assertEquals(emptyList(), refiled.unfiled)
        assertEquals(listOf("s"), nowhere.assign("s", null).unfiled)
        assertEquals("app", appTree.placeOf(briefed("s", null)))
    }

    @Test
    fun `a brief's folder deleted or in a plan folder is ignored, and a renamed one keeps the session`() {
        val s = briefed("s", "fix")
        assertEquals("app", appTree.delete("fix").placeOf(s))
        assertEquals("app", appTree.addPlan("tf").moveFolder("fix", "tf", 0).placeOf(s))
        assertEquals("fix", appTree.rename("fix", "bugs").placeOf(s))
    }

    @Test
    fun `briefFolder finds a folder or creates one level below an existing one`() {
        val (same, found) = tree.briefFolder("RTM/Fixes") { "new" }
        assertSame(tree, same)
        assertEquals("fix", found.id)
        val (created, made) = tree.briefFolder("rtm/new") { "n1" }
        assertEquals("n1", made.id)
        assertEquals("rtm/new", created.pathOf("n1"))
        listOf("top", "rtm/a/b", "rtm/").forEach { assertThrows<IllegalArgumentException>(it) { tree.briefFolder(it) { "x" } } }
        val planned = tree.addPlan("rtm")
        listOf("rtm", "rtm/fixes", "rtm/new").forEach { assertThrows<IllegalArgumentException>(it) { planned.briefFolder(it) { "x" } } }
    }

    @Test
    fun `version 4 round-trips the unfiled sessions, and version 3 reads with none`() {
        val unfiled = tree.unfile("s1")
        assertEquals(unfiled, SessionFolders.parse(unfiled.toJson()))
        assertEquals(SessionFolders(nextId = 3), SessionFolders.parse("""{"version":3,"nextId":3,"folders":[]}"""))
    }

    @Test
    fun `clear carries a session kept in sessions by hand`() {
        val kept = appTree.unfile("old")
        assertEquals(listOf("old", "new"), kept.carryOver("old", "new", emptySet()).unfiled)
        assertSame(kept, kept.carryOver("old", "seen", setOf("seen")))
    }

    @Test
    fun `a folder name with a control character is refused`() {
        assertThrows<IllegalArgumentException> { tree.briefFolder("rtm/a\nb") { "x" } }
    }

    @Test
    fun `a session both filed and unfiled throws`() {
        assertThrows<IllegalArgumentException> { SessionFolders(listOf(Folder("f", "f", items = listOf(Item(SessionFolders.SESSION, "s")))), unfiled = listOf("s")) }
        assertThrows<IllegalArgumentException> { SessionFolders(unfiled = listOf("s", "s")) }
    }

    @Test
    fun `version 4 round-trips a working directory and writes none when unset`() {
        val set = tree.setWorkdir("fix", "/dev/rtm")
        assertEquals(set, SessionFolders.parse(set.toJson()))
        assertFalse("workdir" in tree.toJson())
    }

    @Test
    fun `a working directory may repeat and may equal a bound directory, but not be blank`() {
        val shared = tree.bind("rtm", "/dev/rtm").setWorkdir("fix", "/dev/rtm").setWorkdir("tf", "/dev/rtm")
        assertEquals("/dev/rtm", shared.find("fix")!!.workdir)
        assertThrows<IllegalArgumentException> { tree.setWorkdir("fix", " ") }
    }

    @Test
    fun `the nearest folder with a working directory or a bound one decides, its working directory first`() {
        val f = tree.bind("rtm", "/bound").createFolder("fix", "deep", "deep")
        assertEquals("/bound", f.startFolder("deep")!!.startDir)
        val w = f.setWorkdir("fix", "/work")
        assertEquals("fix", w.startFolder("deep")!!.id)
        assertEquals("/work", w.setWorkdir("rtm", "/other").startFolder("deep")!!.startDir)
        assertEquals("/other", w.setWorkdir("rtm", "/other").startFolder("rtm")!!.startDir)
        assertEquals("/gone", tree.setWorkdir("tf", "/gone").startFolder("tf")!!.startDir)
        assertNull(tree.startFolder("tf"))
    }
}
