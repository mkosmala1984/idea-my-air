package dev.myair.folders

import org.junit.jupiter.api.Test
import dev.myair.mcp.treeJson
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
    private fun applied(changes: String, structure: SessionFolders = tree, reviews: Set<String> = emptySet(), canvases: Set<String> = emptySet()) =
        FolderChanges.apply(structure, changes, scanned = setOf("s1", "s2"), projects = setOf("/app", "/web"), reviews, canvases, newId = { "new${++ids}" })

    private fun apply(changes: String, structure: SessionFolders = tree) = applied(changes, structure).folders

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
    fun `set_workdir refuses a relative directory, though one resolves against the IDE's own`() {
        assertThrows<IllegalArgumentException> { apply("""[{"op":"set_workdir","path":"rtm","dir":"src"}]""") }
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
    fun `a canvas folder is created or added, its id returned, while the plain folders stay plain`() {
        val result = applied("""[{"op":"create","path":"rtm/board","canvas":true},{"op":"create","path":"rtm/plain"},{"op":"add_canvas","path":"testflo"}]""")
        val board = result.folders.findByPath("rtm/board")!!.id
        assertEquals(setOf(board, "tf"), result.canvases)
        // What my_air_folders_get shows once their canvas.json is written: kind canvas on those two only.
        val kinds = Regex(""""path": "([^"]+)",\s*"kind": "(\w+)"""").findAll(result.folders.treeJson(canvases = result.canvases)).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(mapOf("rtm/board" to "canvas", "testflo" to "canvas"), kinds)
        assertEquals(emptySet(), applied("""[{"op":"create","path":"x","canvas":false}]""").canvases)
        // A canvas created and deleted in the same list leaves no canvas behind.
        assertEquals(emptySet(), applied("""[{"op":"create","path":"x","canvas":true},{"op":"delete","path":"x"}]""").canvases)
    }

    @Test
    fun `a canvas folder goes where the tree's New Canvas may put one, in a plan folder but never in a review or canvas folder, holding no other kind`() {
        val base = tree.createFolder(null, "p", "plan", plan = Plan()).createFolder(null, "r", "Review: a").createFolder(null, "c", "Canvas: a")
            .createFolder("c", "cin", "inside").createFolder("rtm", "np", "nested plan", plan = Plan())
        fun kinds(changes: String) = applied(changes, base, reviews = setOf("r"), canvases = setOf("c"))
        assertEquals(1, kinds("""[{"op":"create","path":"plan/board","canvas":true}]""").canvases.size)
        listOf(
            """[{"op":"create","path":"Review: a/x","canvas":true}]""" to "would lie in review folder 'Review: a'",
            """[{"op":"create","path":"Canvas: a/x","canvas":true}]""" to "would lie in canvas folder 'Canvas: a'",
            """[{"op":"create","path":"x","canvas":true,"plan":true}]""" to "would be a plan in canvas folder 'x'",
            """[{"op":"add_canvas","path":"plan"}]""" to "would be a plan in canvas folder 'plan'",
            """[{"op":"add_canvas","path":"rtm"}]""" to "would be a plan in canvas folder 'rtm'",
            """[{"op":"add_canvas","path":"Canvas: a/inside"}]""" to "would lie in canvas folder 'Canvas: a'",
            """[{"op":"add_canvas","path":"Review: a"}]""" to "'Review: a' is a review or canvas folder",
            """[{"op":"add_canvas","path":"Canvas: a"}]""" to "'Canvas: a' is a review or canvas folder",
            """[{"op":"create","path":"x","canvas":true},{"op":"create","path":"x/y","canvas":true}]""" to "would lie in canvas folder 'x'",
            """[{"op":"create","path":"x","canvas":"yes"}]""" to "'canvas' must be true or false",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { kinds(changes) }
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
