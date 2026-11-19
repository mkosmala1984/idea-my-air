package dev.myair.folders

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanChangesTest {
    private val today = LocalDate.of(2026, 11, 18)

    /** Plan folder p: M1 holds T2; plan folder q: M3 holds T4. */
    private val structure = SessionFolders()
        .createFolder(null, "p", "p", plan = Plan()).createFolder(null, "q", "q", plan = Plan())
        .addMilestone("p", "Export").addTask("M1", "Fields")
        .addMilestone("q", "Other").addTask("M3", "Theirs")

    private fun apply(changes: String) = PlanChanges.apply(structure, "p", changes, today)
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
        val kept = PlanChanges.apply(set, "p", """[{"op":"update","id":"T2","status":"done"},{"op":"update","id":"M1","title":"E"}]""", today).plan()
        assertEquals(listOf("x", "t", "m"), listOf(kept.tasks().single().next, kept.tasks().single().description, kept.milestones.single().description))
        val cleared = PlanChanges.apply(
            set, "p",
            """[{"op":"update","id":"T2","next":null,"description":null},{"op":"update","id":"M1","description":null},{"op":"set_description","description":null}]""", today,
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
            """[{"op":"update","id":"X1","text":"x"}]""" to "unknown id kind 'X'",
            """[{"op":"add_requirement","text":"a\nb","eval":"code"}]""" to "one line",
            """[{"op":"add_blocker","text":"a\rb"}]""" to "one line",
            """[{"op":"add_requirement","text":"r","eval":"code","tasks":["T2","T2"]}]""" to "names a task twice",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { apply(changes) }
            assertTrue(message in error.message!!, "'$message' not in '${error.message}'")
        }
    }

    @Test
    fun `the new sections are set in order, ids allocated per plan`() {
        val plan = apply(
            """[{"op":"set_constraints","constraints":"no fallbacks"},
                {"op":"add_requirement","text":"It exports","eval":"code","tasks":["T2"]},
                {"op":"add_evidence","id":"R1","evidence":"commit 4c91271d91"},
                {"op":"add_decision","text":"Use JSON","session":"s1"},
                {"op":"set_review","status":"in_progress","commit":"abc1234"},
                {"op":"add_blocker","text":"Missing test"},
                {"op":"update","id":"B1","resolved":true}]""",
        ).plan()
        val expected = Plan(
            milestones = structure.find("p")!!.plan!!.milestones, constraints = "no fallbacks",
            decisions = listOf(Decision("D1", "2026-11-18", "Use JSON", "s1")),
            requirements = listOf(Requirement("R1", "It exports", EvalKind.CODE, listOf("T2"), listOf("commit 4c91271d91"))),
            review = Review(ReviewStatus.IN_PROGRESS, "abc1234", "2026-11-18", listOf(Blocker("B1", "Missing test", true))),
            next = mapOf("R" to 2, "D" to 2, "B" to 2),
        )
        assertEquals(expected, plan)
        assertEquals(RequirementStatus.PROVEN, plan.requirements.single().status)
    }

    @Test
    fun `update and delete take only the fields of the id's kind`() {
        val base = apply("""[{"op":"add_decision","text":"d"},{"op":"add_blocker","text":"b"},{"op":"add_requirement","text":"r","eval":"judge"}]""")
        listOf(
            """[{"op":"update","id":"D1","eval":"code"}]""",
            """[{"op":"update","id":"B1","next":"x"}]""",
            """[{"op":"update","id":"R1","resolved":true}]""",
            """[{"op":"update","id":"M1","text":"x"}]""",
        ).forEach { changes ->
            val error = assertThrows<IllegalArgumentException>(changes) { PlanChanges.apply(base, "p", changes, today) }
            assertTrue("unknown fields" in error.message!!, error.message)
        }
        val edited = PlanChanges.apply(base, "p", """[{"op":"update","id":"D1","text":"e"},{"op":"update","id":"R1","text":"s","eval":"assert","evidence":["checked: x"]},{"op":"delete","id":"B1"}]""", today).plan()
        assertEquals(listOf("e"), edited.decisions.map { it.text })
        assertEquals(Requirement("R1", "s", EvalKind.ASSERT, evidence = listOf("checked: x")), edited.requirements.single())
        assertEquals(emptyList(), edited.review!!.blockers)
        val deleted = PlanChanges.apply(base, "p", """[{"op":"delete","id":"D1"},{"op":"delete","id":"R1"}]""", today).plan()
        assertEquals(emptyList(), deleted.decisions + deleted.requirements)
    }

    @Test
    fun `malformed evidence, eval and review status are refused naming the change`() {
        val base = structure.addRequirement("p", "r", EvalKind.CODE)
        listOf(
            """{"op":"add_evidence","id":"R1","evidence":"commit ABC1234"}""",
            """{"op":"add_evidence","id":"R1","evidence":"commit abc1234 "}""",
            """{"op":"add_requirement","text":"x","eval":"unit"}""",
            """{"op":"set_review","status":"finished"}""",
            """{"op":"set_review","status":"done","commit":"ABC1234"}""",
        ).forEach { change ->
            val error = assertThrows<IllegalArgumentException>(change) { PlanChanges.apply(base, "p", "[$change]", today) }
            assertTrue(error.message!!.startsWith("change 1 "), error.message)
        }
    }

    @Test
    fun `ids of another plan are refused`() {
        val both = structure.addRequirement("q", "theirs", EvalKind.CODE)
        listOf(
            """[{"op":"update","id":"R1","text":"x"}]""" to "no requirement 'R1' in this plan",
            """[{"op":"add_evidence","id":"R1","evidence":"checked: x"}]""" to "no requirement 'R1' in this plan",
            """[{"op":"delete","id":"D1"}]""" to "no decision 'D1' in this plan",
            """[{"op":"update","id":"B1","resolved":true}]""" to "no blocker 'B1' in this plan",
            """[{"op":"add_requirement","text":"x","eval":"code","tasks":["T4"]}]""" to "no task 'T4' in this plan",
        ).forEach { (changes, message) ->
            val error = assertThrows<IllegalArgumentException>(changes) { PlanChanges.apply(both, "p", changes, today) }
            assertTrue(message in error.message!!, "'$message' not in '${error.message}'")
        }
    }

    @Test
    fun `set_review keeps the commit unless given, null clearing it`() {
        val first = apply("""[{"op":"set_review","status":"in_progress","commit":"abc1234"}]""")
        assertEquals("abc1234", first.plan().review!!.commit)
        val kept = PlanChanges.apply(first, "p", """[{"op":"set_review","status":"done"}]""", today).plan().review!!
        assertEquals(ReviewStatus.DONE to "abc1234", kept.status to kept.commit)
        assertNull(PlanChanges.apply(first, "p", """[{"op":"set_review","status":"done","commit":null}]""", today).plan().review!!.commit)
    }
}
