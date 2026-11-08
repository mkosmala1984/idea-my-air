package dev.myair.store

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class PlanManagersTest {
    private val m = PlanManager("s1", Path.of("E:/dev/a"), Path.of("E:/dev/a"))

    @Test fun `managers read back as written`() =
        assertEquals(PlanManagers(mapOf("f1" to m)), PlanManagers.parse(PlanManagers(mapOf("f1" to m)).toJson()))

    @Test fun `a clear moves the manager to its new session`() =
        assertEquals("s2", PlanManagers(mapOf("f1" to m)).moveSession("s1", "s2").byFolder.getValue("f1").session)

    @Test fun `moving a session that manages nothing changes nothing`() =
        assertEquals(PlanManagers(mapOf("f1" to m)), PlanManagers(mapOf("f1" to m)).moveSession("x", "y"))

    @Test fun `managers of deleted folders are pruned`() =
        assertEquals(setOf("f1"), PlanManagers(mapOf("f1" to m, "f2" to m.copy(session = "s9"))).prune(setOf("f1")).byFolder.keys)

    @Test fun `a manager's folder is found by its session`() = assertEquals("f1", PlanManagers(mapOf("f1" to m)).folderOf("s1"))

    @Test fun `unknown keys throw`() {
        assertThrows<IllegalStateException> { PlanManagers.parse("""{"f1":{"session":"s","cwd":"E:/a","project":"E:/a","x":1}}""") }
    }

    @Test fun `a store without a file reads empty and writes on update`(@TempDir dir: Path) {
        val store = PlanManagerStore(dir.resolve("plan-managers.json"))
        assertEquals(PlanManagers(), store.read())
        store.update { it.set("f1", m) }
        assertEquals(m, PlanManagerStore(dir.resolve("plan-managers.json")).read().byFolder["f1"])
    }
}
