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
            """[{"op":"set_goal","goal":"migrate"},
                {"op":"add_milestone","title":"Import","index":0},
                {"op":"add_task","milestone":"M5","title":"Map"},
                {"op":"update","id":"T6","status":"in_progress","next":"ask about dates"},
                {"op":"move","id":"T2","milestone":"M5","index":0},
                {"op":"update","id":"M1","status":"done","title":"Export done"}]""",
        )
        val plan = result.plan()
        assertEquals("migrate", plan.goal)
        assertEquals(listOf("M5" to listOf("T2", "T6"), "M1" to emptyList()), plan.milestones.map { m -> m.id to m.tasks.map { it.id } })
        assertEquals(PlanTask("T6", "Map", PlanStatus.IN_PROGRESS, "ask about dates"), plan.tasks()[1])
        assertTrue(plan.milestones[1].done)
        assertEquals("Export done", plan.milestones[1].title)
    }

    @Test
    fun `next is kept unless given, null clearing it, and a goal clears with null`() {
        val set = apply("""[{"op":"update","id":"T2","next":"x"},{"op":"set_goal","goal":"g"}]""")
        assertEquals("x", PlanChanges.apply(set, "p", """[{"op":"update","id":"T2","status":"done"}]""").plan().tasks().single().next)
        val cleared = PlanChanges.apply(set, "p", """[{"op":"update","id":"T2","next":null},{"op":"set_goal","goal":null}]""")
        assertNull(cleared.plan().tasks().single().next)
        assertNull(cleared.plan().goal)
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
            """[{"op":"set_goal"}]""" to "'goal' is missing",
            """[{"op":"add_milestone","title":"ok"},{"op":"delete","id":"T9"}]""" to "change 2",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { apply(changes) }
            assertTrue(message in error.message!!, "'$message' not in '${error.message}'")
        }
    }
}
