package dev.myair.folders

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanChangesTest {
    /** Plan folder p: M1 holds T2; plan folder q: M3 holds T4. */
    private val structure = SessionFolders()
        .createFolder(null, "p", "p", plan = Plan()).createFolder(null, "q", "q", plan = Plan())
        .addMilestone("p", "Export").addTask("M1", "Fields")
        .addMilestone("q", "Other").addTask("M3", "Theirs")

    private fun apply(changes: String) = PlanChanges.apply(structure, "p", changes)
    private fun SessionFolders.plan() = find("p")!!.plan!!

    @Test
    fun `changes apply in order, later ones using ids earlier ones allocated`() {
        val result = apply(
            """[{"op":"set_description","description":"# Migrate\n\n- data"},
                {"op":"add_milestone","title":"Import","index":0},
                {"op":"add_task","milestone":"M5","title":"Map","description":"Map **fields**"},
                {"op":"update","id":"T6","status":"in_progress","next":"ask about dates"},
                {"op":"move","id":"T2","milestone":"M5","index":0},
                {"op":"update","id":"M1","status":"done","title":"Export done"}]""",
        )
        val plan = result.plan()
        assertEquals("# Migrate\n\n- data", plan.description)
        assertEquals(listOf("M5" to listOf("T2", "T6"), "M1" to emptyList()), plan.milestones.map { m -> m.id to m.tasks.map { it.id } })
        assertEquals(PlanTask("T6", "Map", PlanStatus.IN_PROGRESS, "ask about dates", "Map **fields**"), plan.tasks()[1])
        assertTrue(plan.milestones[1].done)
        assertEquals("Export done", plan.milestones[1].title)
    }

    @Test
    fun `next and descriptions are kept unless given, null clearing them`() {
        val set = apply(
            """[{"op":"update","id":"T2","next":"x","description":"t"},{"op":"update","id":"M1","description":"m"},
                {"op":"set_description","description":"p"}]""",
        )
        val kept = PlanChanges.apply(set, "p", """[{"op":"update","id":"T2","status":"done"},{"op":"update","id":"M1","title":"E"}]""").plan()
        assertEquals(listOf("x", "t", "m"), listOf(kept.tasks().single().next, kept.tasks().single().description, kept.milestones.single().description))
        val cleared = PlanChanges.apply(
            set, "p",
            """[{"op":"update","id":"T2","next":null,"description":null},{"op":"update","id":"M1","description":null},{"op":"set_description","description":null}]""",
        ).plan()
        assertEquals(listOf(null, null, null, null), listOf(cleared.tasks().single().next, cleared.tasks().single().description, cleared.milestones.single().description, cleared.description))
        assertEquals("d", apply("""[{"op":"add_milestone","title":"x","description":"d"}]""").plan().milestones.last().description)
    }

    @Test
    fun `a milestone with open tasks is not deleted, an empty one is`() {
        assertThrows<IllegalArgumentException> { apply("""[{"op":"delete","id":"M1"}]""") }
        assertEquals(emptyList(), apply("""[{"op":"delete","id":"T2"},{"op":"delete","id":"M1"}]""").plan().milestones)
    }

    @Test
    fun `any invalid change rejects the whole list, naming the change`() {
        listOf(
            """not json""" to "not JSON",
            """[{"op":"explode"}]""" to "unknown op",
            """[{"op":"delete","id":"T2","force":true}]""" to "unknown fields [force]",
            """[{"op":"delete","id":"T4"}]""" to "no task 'T4' in this plan",
            """[{"op":"add_task","milestone":"M3","title":"x"}]""" to "no milestone 'M3' in this plan",
            """[{"op":"add_task","milestone":"M1","title":" "}]""" to "blank title",
            """[{"op":"update","id":"T2","status":"started"}]""" to "unknown status 'started'",
            """[{"op":"update","id":"M1","status":"waiting"}]""" to "unknown milestone status",
            """[{"op":"update","id":"M1","next":"x"}]""" to "no next step",
            """[{"op":"move","id":"T2"}]""" to "'index' is missing",
            """[{"op":"move","id":"T2","index":1}]""" to "index 1 outside 0..0",
            """[{"op":"move","id":"M1","index":1}]""" to "outside the milestones",
            """[{"op":"set_description"}]""" to "'description' is missing",
            """[{"op":"update","id":"T2","description":" "}]""" to "blank description",
            """[{"op":"set_goal","goal":"g"}]""" to "unknown op",
            """[{"op":"add_milestone","title":"ok"},{"op":"delete","id":"T9"}]""" to "change 2",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { apply(changes) }
            assertTrue(message in error.message!!, "'$message' not in '${error.message}'")
        }
    }
}
