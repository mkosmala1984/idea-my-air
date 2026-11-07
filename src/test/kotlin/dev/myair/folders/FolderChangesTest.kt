package dev.myair.folders

import org.junit.jupiter.api.Test
import com.google.gson.Gson
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderChangesTest {
    private val tree = SessionFolders()
        .createFolder(null, "rtm", "rtm")
        .createFolder("rtm", "mig", "rtm migration")
        .createFolder("rtm", "fix", "fixes")
        .createFolder(null, "tf", "testflo")
        .assign("s1", "fix")

    private var ids = 0
    private fun apply(changes: String, structure: SessionFolders = tree) =
        FolderChanges.apply(structure, changes, scanned = setOf("s1", "s2"), projects = setOf("/app", "/web"), newId = { "new${++ids}" })

    private fun SessionFolders.names(path: String? = null) = (path?.let { findByPath(it)!!.folders } ?: folders).map { it.name }

    @Test
    fun `changes apply in order, later ones addressing what earlier ones created or renamed`() {
        val result = apply(
            """[{"op":"create","path":"experiments"},
                {"op":"create","path":"experiments/spikes"},
                {"op":"assign","session":"s2","path":"Experiments/SPIKES"},
                {"op":"rename","path":"rtm/fixes","name":"bugs"},
                {"op":"assign","session":"s1","path":"rtm/bugs"}]""",
        )
        assertEquals(listOf("rtm", "testflo", "experiments"), result.names())
        assertEquals("experiments/spikes", result.pathOf(result.folderOf("s2")!!))
        assertEquals("rtm/bugs", result.pathOf(result.folderOf("s1")!!))
    }

    @Test
    fun `a created folder binds known directories, each to one folder`() {
        val result = apply("""[{"op":"create","path":"rtm/app","dirs":["/app","/web"]}]""")
        assertEquals(listOf("/app", "/web"), result.findByPath("rtm/app")!!.dirs)
        assertThrows<IllegalArgumentException> { apply("""[{"op":"create","path":"x","dirs":["/nowhere"]}]""") }
        assertThrows<IllegalArgumentException> { apply("""[{"op":"create","path":"x","dirs":["/app"]}]""", result) }
        assertThrows<IllegalArgumentException> { apply("""[{"op":"create","path":"x","dirs":"/app"}]""") }
        assertThrows<IllegalArgumentException> { apply("""[{"op":"create","path":"x","project":"/app"}]""") }
    }

    @Test
    fun `set_workdir sets, replaces and clears an existing directory, as Path spells it`(@TempDir dir: Path) {
        fun json(path: Any) = Gson().toJson(path.toString())
        val set = apply("""[{"op":"set_workdir","path":"rtm","dir":${json("$dir${File.separator}")}},{"op":"set_workdir","path":"testflo","dir":${json(dir)}}]""")
        assertEquals(dir.toString(), set.findByPath("rtm")!!.workdir)
        assertEquals(dir.toString(), set.findByPath("testflo")!!.workdir)
        assertNull(apply("""[{"op":"set_workdir","path":"rtm","dir":null}]""", set).findByPath("rtm")!!.workdir)
        assertThrows<IllegalArgumentException> { apply("""[{"op":"set_workdir","path":"rtm","dir":${json(dir.resolve("gone"))}}]""") }
        assertThrows<IllegalArgumentException> { apply("""[{"op":"set_workdir","path":"nope","dir":null}]""") }
        assertThrows<IllegalArgumentException> { apply("""[{"op":"set_workdir","path":"rtm"}]""") }
    }

    @Test
    fun `bind and unbind move a directory between folders`() {
        val bound = apply("""[{"op":"bind","path":"rtm","dir":"/app"},{"op":"unbind","dir":"/app"},{"op":"bind","path":"testflo","dir":"/app"}]""")
        assertEquals(listOf("/app"), bound.findByPath("testflo")!!.dirs)
        assertTrue(bound.findByPath("rtm")!!.dirs.isEmpty())
        assertThrows<IllegalArgumentException> { apply("""[{"op":"unbind","dir":"/app"}]""") }
        assertThrows<IllegalArgumentException> { apply("""[{"op":"bind","path":"rtm","dir":"/nowhere"}]""") }
    }

    @Test
    fun `a plan folder is created or added, never inside another`() {
        val planned = apply("""[{"op":"create","path":"p","plan":true},{"op":"add_plan","path":"rtm"}]""")
        assertEquals(Plan(), planned.findByPath("p")!!.plan)
        assertEquals(Plan(), planned.findByPath("rtm")!!.plan)
        listOf(
            """[{"op":"create","path":"rtm/fixes/x","plan":true}]""" to "lies in a plan folder",
            """[{"op":"add_plan","path":"rtm/fixes"}]""" to "has a plan, lies in a plan folder or holds one",
            """[{"op":"move","path":"p","to":"rtm"}]""" to "holds another plan folder",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { apply(changes, planned) }
            assertTrue(message in error.message!!, "'$message' not in '${error.message}'")
        }
    }

    @Test
    fun `a move puts the folder at the final index among its new siblings, last by default`() {
        assertEquals(listOf("fixes", "rtm migration"), apply("""[{"op":"move","path":"rtm/rtm migration","to":"rtm","index":1}]""").names("rtm"))
        assertEquals(listOf("fixes", "rtm migration"), apply("""[{"op":"move","path":"rtm/fixes","to":"rtm","index":0}]""").names("rtm"))
        assertEquals(listOf("rtm", "testflo", "fixes"), apply("""[{"op":"move","path":"rtm/fixes","to":null}]""").names())
        assertEquals(listOf("fixes", "rtm", "testflo"), apply("""[{"op":"move","path":"rtm/fixes","to":null,"index":0}]""").names())
    }

    @Test
    fun `delete returns sessions to sessions and assign with null path unfiles`() {
        assertNull(apply("""[{"op":"delete","path":"rtm"}]""").folderOf("s1"))
        assertNull(apply("""[{"op":"assign","session":"s1","path":null}]""").folderOf("s1"))
    }

    @Test
    fun `any invalid change rejects the whole list, naming the change`() {
        listOf(
            """not json""" to "not JSON",
            """{"op":"delete"}""" to "JSON array",
            """[{"op":"explode","path":"rtm"}]""" to "unknown op",
            """[{"op":"delete","path":"rtm","force":true}]""" to "unknown fields [force]",
            """[{"op":"delete","path":"nowhere"}]""" to "no folder 'nowhere'",
            """[{"op":"create","path":"a/b"}]""" to "no folder 'a'",
            """[{"op":"create","path":"rtm/FIXES"}]""" to "already exists",
            """[{"op":"assign","session":"ghost","path":"rtm"}]""" to "no session 'ghost'",
            """[{"op":"assign","session":"s1"}]""" to "'path' is missing",
            """[{"op":"move","path":"rtm","to":"rtm/fixes"}]""" to "cannot move into itself",
            """[{"op":"move","path":"rtm","to":null,"index":5}]""" to "index 5 outside 0..1",
            """[{"op":"rename","path":"rtm","name":"a/b"}]""" to "bad folder name",
            """[{"op":"rename","path":"rtm","name":"testflo"}]""" to "duplicate folder names",
            """[{"op":"create","path":"ok"},{"op":"delete","path":"missing"}]""" to "change 2",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { apply(changes) }
            assertTrue(message in error.message!!, "'$message' not in '${error.message}'")
        }
    }
}
