package dev.myair.folders

import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanTest {
    /** Plan folder rtm (bound to /rtm, /rtm-front) with a subfolder; M1 holds T2 and T3, M4 is empty. */
    private val structure = SessionFolders()
        .createFolder(null, "rtm", "rtm", dirs = listOf(Path.of("/rtm").toString(), Path.of("/rtm-front").toString()))
        .createFolder("rtm", "sub", "sub")
        .createFolder(null, "other", "other")
        .addPlan("rtm")
        .addMilestone("rtm", "Export")
        .addTask("M1", "Fields")
        .addTask("M1", "Checks")
        .addMilestone("rtm", "Import")

    private fun SessionFolders.plan() = find("rtm")!!.plan!!
    private fun SessionFolders.tasksOf(milestone: String) = findMilestone(milestone)!!.second.tasks.map { it.id }

    private fun session(id: String, cwd: String, planTask: String? = null) =
        ClaudeSession(id, Path.of(cwd), id, Instant.EPOCH, emptyList(), emptyList(), Brief("g", emptyList(), planTask))

    @Test
    fun `milestones and tasks take ids from one counter that never goes back`() {
        assertEquals(listOf("M1", "M4"), structure.plan().milestones.map { it.id })
        assertEquals(listOf("T2", "T3"), structure.tasksOf("M1"))
        val again = structure.deletePlanItem("T3").addTask("M1", "New")
        assertEquals(listOf("T2", "T5"), again.tasksOf("M1"))
        assertEquals(6, again.nextId)
    }

    @Test
    fun `restoring an older snapshot keeps the counter, so no id comes back`() {
        val before = SessionFolders().createFolder(null, "rtm", "rtm")
        val restored = structure.restore(before)
        assertEquals(before.folders, restored.folders)
        assertEquals("M5", restored.addPlan("rtm").addMilestone("rtm", "x").find("rtm")!!.plan!!.milestones.single().id)
    }

    @Test
    fun `tasks and milestones move to their final index, tasks across milestones of one plan`() {
        assertEquals(listOf("T3", "T2"), structure.moveTask("T2", "M1", 1).tasksOf("M1"))
        val moved = structure.moveTask("T3", "M4", 0)
        assertEquals(listOf("T2"), moved.tasksOf("M1"))
        assertEquals(listOf("T3"), moved.tasksOf("M4"))
        assertEquals(listOf("M4", "M1"), structure.moveMilestone("M1", 1).plan().milestones.map { it.id })
        val elsewhere = structure.addPlan("other").addMilestone("other", "x")
        assertThrows<IllegalArgumentException> { elsewhere.moveTask("T2", "M5", 0) }
    }

    @Test
    fun `a milestone is deleted only once its tasks are closed, and a plan removed only when empty`() {
        assertThrows<IllegalArgumentException> { structure.deletePlanItem("M1") }
        val closed = structure.editTask("T2") { it.copy(status = PlanStatus.DONE) }.editTask("T3") { it.copy(status = PlanStatus.CANCELED) }
        assertEquals(listOf("M4"), closed.deletePlanItem("M1").plan().milestones.map { it.id })
        assertThrows<IllegalArgumentException> { structure.removePlan("rtm") }
        assertNull(structure.deletePlanItem("T2").deletePlanItem("T3").deletePlanItem("M1").deletePlanItem("M4").removePlan("rtm").find("rtm")!!.plan)
    }

    @Test
    fun `a plan folder cannot lie in another`() {
        assertFalse(structure.canAddPlan("sub"))
        assertThrows<IllegalArgumentException> { structure.addPlan("sub") }
        val planned = structure.addPlan("other")
        assertFalse(planned.canHold("rtm", planned.find("other")!!))
        assertThrows<IllegalArgumentException> { planned.moveFolder("other", "sub", 0) }
        assertTrue(planned.canHold("rtm", planned.find("sub")!!))
        assertFalse(structure.createFolder("sub", "inner", "inner").let { it.canAddPlan("rtm") })
    }

    @Test
    fun `a session shows under the task its brief names, in that plan folder, whatever folder it is filed in`() {
        val filed = structure.assign("s1", "other")
        assertEquals("rtm" to "T2", filed.taskOf(session("s1", "/x", "T2"))?.let { it.first.id to it.second.id })
        assertEquals("rtm", filed.placeOf(session("s1", "/x", "T2")))
        assertEquals("other", filed.placeOf(session("s1", "/x", "T99")))
        assertEquals("rtm", filed.placeOf(session("s2", "/rtm-front/wt")))
        assertEquals(listOf("rtm"), filed.planFoldersOf(Path.of("/rtm-front")).map { it.id })
        assertEquals(listOf("rtm"), filed.bind("sub", Path.of("/rtm/sub").toString()).planFoldersOf(Path.of("/rtm/sub")).map { it.id })
        assertEquals(emptyList(), filed.planFoldersOf(Path.of("/elsewhere")))
    }

    @Test
    fun `a directory has every plan folder inside its bound folder, in tree order`() {
        val air = SessionFolders()
            .createFolder(null, "air", "air", dirs = listOf(Path.of("/air").toString()))
            .createFolder("air", "a", "a", plan = Plan(null, emptyList()))
            .createFolder("air", "fixes", "fixes")
            .createFolder("fixes", "b", "b", plan = Plan(null, emptyList()))
            .createFolder(null, "c", "c", plan = Plan(null, emptyList()))
        assertEquals(listOf("a", "b"), air.planFoldersOf(Path.of("/air/.claude/worktrees/x")).map { it.id })
        assertEquals(emptyList(), air.unbind(Path.of("/air").toString()).planFoldersOf(Path.of("/air")))
    }

    @Test
    fun `operations on what another IDE removed change nothing`() {
        assertSame(structure, structure.addTask("M99", "x"))
        assertSame(structure, structure.addMilestone("gone", "x"))
        assertSame(structure, structure.editTask("T99") { it })
        assertSame(structure, structure.moveTask("T99", "M1", 0))
        assertSame(structure, structure.deletePlanItem("M99"))
        assertSame(structure, structure.bind("gone", "/x"))
    }

    @Test
    fun `a directory binds to one folder and unbinds from it`() {
        assertThrows<IllegalArgumentException> { structure.bind("other", Path.of("/rtm").toString()) }
        val moved = structure.unbind(Path.of("/rtm").toString()).bind("other", Path.of("/rtm").toString())
        assertEquals("other", moved.projectFolder(Path.of("/rtm"))?.id)
        assertEquals("rtm", moved.projectFolder(Path.of("/rtm-front"))?.id)
    }

    @Test
    fun `json round-trips plans, and a version 2 file reads its project as one directory`() {
        val edited = structure.editTask("T2") { it.copy(status = PlanStatus.IN_PROGRESS, next = "map the rest") }.setGoal("rtm", "migrate")
        assertEquals(edited, SessionFolders.parse(edited.toJson()))
        assertTrue("\"in_progress\"" in edited.toJson())
        assertEquals(
            SessionFolders().createFolder(null, "a", "a", dirs = listOf("/app")),
            SessionFolders.parse("""{"version":2,"folders":[{"id":"a","name":"a","folders":[],"items":[],"project":"/app"}]}"""),
        )
    }

    @Test
    fun `a malformed plan throws`() {
        fun file(plan: String, nextId: Int = 9) = """{"version":3,"nextId":$nextId,"folders":[{"id":"a","name":"a","folders":[],"items":[],"dirs":[],"plan":$plan}]}"""
        val task = """{"id":"T2","title":"t","status":"pending"}"""
        SessionFolders.parse(file("""{"milestones":[{"id":"M1","title":"m","done":false,"tasks":[$task]}]}"""))
        listOf(
            file("""{"milestones":[{"id":"M1","title":"m","done":false,"tasks":[$task]}]}""", nextId = 2),
            file("""{"milestones":[{"id":"T1","title":"m","done":false,"tasks":[]}]}"""),
            file("""{"milestones":[{"id":"M1","title":"m","done":false,"tasks":[{"id":"T2","title":"t","status":"started"}]}]}"""),
            file("""{"milestones":[{"id":"M1","title":"m","done":false,"tasks":[$task,$task]}]}"""),
            file("""{"milestones":[],"owner":"x"}"""),
            """{"version":3,"folders":[]}""",
            """{"version":2,"nextId":1,"folders":[]}""",
        ).forEach { text -> assertThrows<Exception>(text) { SessionFolders.parse(text) } }
    }
}
