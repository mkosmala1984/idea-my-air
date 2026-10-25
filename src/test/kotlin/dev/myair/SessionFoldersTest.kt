package dev.myair

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
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
        val bound = tree.createFolder("tf", "app", "app", project = Path.of("/app").toString()).assign("s2", "fix")
        fun at(id: String) = bound.placeOf(ClaudeSession(id, Path.of("/app"), id, java.time.Instant.EPOCH, emptyList(), emptyList()))
        assertEquals("app", at("s1"))
        assertEquals("fix", at("s2"))
        assertEquals(bound, SessionFolders.parse(bound.toJson()))
        assertThrows<IllegalArgumentException> { bound.createFolder(null, "x", "x", project = Path.of("/app").toString()) }
        assertEquals("x", bound.createFolder(null, "x", "x", project = Path.of("/APP").toString()).projectFolder(Path.of("/APP"))?.id)
        assertNull(bound.delete("tf").placeOf(ClaudeSession("s1", Path.of("/app"), "s1", java.time.Instant.EPOCH, emptyList(), emptyList())))
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
            """{"version":3,"folders":[]}""",
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
}
