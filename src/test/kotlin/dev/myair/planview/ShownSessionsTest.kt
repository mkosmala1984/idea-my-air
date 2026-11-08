package dev.myair.planview

import dev.myair.folders.Plan
import dev.myair.folders.SessionFolders
import dev.myair.store.PlanManager
import dev.myair.store.PlanManagers
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShownSessionsTest {
    private val structure = SessionFolders().createFolder(null, "f", "Plan", plan = Plan()).createFolder(null, "g", "Plain")
    private val managers = PlanManagers().set("f", PlanManager("m", Path.of("/app"), Path.of("/app")))

    @Test
    fun `a folder has a plan only while it is there and holds one`() {
        assertTrue(hasPlan(structure, "f"))
        assertFalse(hasPlan(structure, "g"))
        assertFalse(hasPlan(structure, "nope"))
        assertFalse(hasPlan(structure.removePlan("f"), "f"))
    }

    @Test
    fun `a manager opens its plan while the plan is there`() {
        assertEquals("f", managedPlanFolder(managers, structure, "m"))
        assertNull(managedPlanFolder(managers, structure, "other"))
    }

    @Test
    fun `a manager whose plan was removed or whose folder was deleted opens as a session`() {
        assertNull(managedPlanFolder(managers, structure.removePlan("f"), "m"))
        assertNull(managedPlanFolder(managers, structure.delete("f"), "m"))
    }
}
