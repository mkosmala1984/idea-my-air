package dev.myair.editor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals

class SessionTabUrlTest {
    private val id = "3f2a9c1e-0000-4000-8000-000000000001"

    @Test
    fun `a path names the session, its cwd and its project, and reads back as them`() {
        val cwd = Path.of("E:/dev/ząb #1/a&b=c+d%20/.claude/worktrees/3f2a9c1e")
        val project = Path.of("E:/dev/ząb #1/a&b=c+d%20")
        assertEquals(SessionTabRef(id, cwd, project), parseSessionTabPath(sessionTabPath(id, cwd, project)))
        assertEquals(SessionTabRef(id, project, project), parseSessionTabPath(sessionTabPath(id, project, project)))
    }

    @Test
    fun `a path that is not one this plugin wrote throws`() {
        val ok = sessionTabPath(id, Path.of("E:/a"), Path.of("E:/a"))
        for (bad in listOf("", id, "?cwd=E%3A%2Fa&project=E%3A%2Fa", "$id?cwd=E%3A%2Fa", "$ok&extra=1", "$id?project=E%3A%2Fa&cwd=E%3A%2Fa&cwd=x"))
            assertThrows<IllegalArgumentException>(bad) { parseSessionTabPath(bad) }
    }

    @Test
    fun `a session with a transcript resumes, one closed before its first message starts again under its id`(@TempDir home: Path) {
        assertEquals(listOf("--session-id", id), restartArgs(id, home))
        home.resolve("projects/E--dev-a").createDirectories().resolve("$id.jsonl").createFile()
        assertEquals(listOf("--resume", id), restartArgs(id, home))
    }
}
