package dev.myair.canvasview

import dev.myair.canvas.CANVAS_MANAGER_FIRST_MESSAGE
import dev.myair.folders.SessionFolders
import dev.myair.planview.parseFolderTabPath
import dev.myair.store.Canvas
import dev.myair.store.PlanManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CanvasTabsTest {
    private val dir = Path.of("E:/dev/r")
    private fun canvas(manager: PlanManager?) = Canvas(manager, 1, emptyList())

    @Test fun `a canvas tab path round-trips its folder id`() {
        val id = "3f2a9c1e-0000-4000-8000-000000000002"
        assertEquals(id, parseFolderTabPath(CanvasVirtualFile(id).path))
    }

    @Test fun `a canvas without a manager starts a new session in the given directory, also its project, with the first message`() =
        assertEquals(
            PlanManager("n", dir, dir) to listOf("--session-id", "n", "--", CANVAS_MANAGER_FIRST_MESSAGE),
            canvasManagerStart(canvas(null), "n", { dir }) { _, _ -> error("a new manager has no transcript") },
        )

    @Test fun `a manager entry resumes in its stored directories and profile without a message`() {
        val m = PlanManager("s", Path.of("E:/dev/elsewhere"), Path.of("E:/dev/main"), "work")
        assertEquals(m to listOf("--resume", "s"), canvasManagerStart(canvas(m), "n", { error("a stored manager keeps its directory") }) { id, profile ->
            id == "s" && profile == "work"
        })
    }

    @Test fun `a manager closed before its first message gets the message again`() {
        val m = PlanManager("s", dir, dir)
        assertEquals(m to listOf("--session-id", "s", "--", CANVAS_MANAGER_FIRST_MESSAGE), canvasManagerStart(canvas(m), "n", { error("unused") }) { _, _ -> false })
    }

    @Test fun `a manager handed its first arguments starts with them instead of the canvas's own first message`() {
        val m = PlanManager("s", dir, dir)
        val first = listOf("--model", "opus", "--", "show the findings")
        assertEquals(m to listOf("--session-id", "s") + first, canvasManagerStart(canvas(m), "n", { error("unused") }, first) { _, _ -> false })
        assertEquals(m to listOf("--resume", "s", "--", "you manage it now"), canvasManagerStart(canvas(m), "n", { error("unused") }, listOf("--", "you manage it now")) { _, _ -> true })
    }

    @Test fun `a canvas starts where its nearest folder with a directory says, else nowhere`() {
        val structure = SessionFolders().createFolder(null, "f1", "rtm", dirs = listOf("E:/dev/rtm")).createFolder("f1", "f2", "Canvas: x")
        assertEquals(Path.of("E:/dev/rtm"), canvasStartDir(structure, "f2"))
        assertEquals(Path.of("E:/dev/wt"), canvasStartDir(structure.setWorkdir("f2", "E:/dev/wt"), "f2"))
        assertNull(canvasStartDir(SessionFolders().createFolder(null, "f1", "Canvas: y"), "f1"))
    }
}
