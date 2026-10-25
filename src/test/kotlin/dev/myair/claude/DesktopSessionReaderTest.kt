package dev.myair.claude

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopSessionReaderTest {
    @TempDir
    lateinit var dataDir: Path

    private fun write(name: String, json: String, modified: Long = 1000): Path =
        dataDir.resolve("claude_profile/claude-code-sessions/account/org").createDirectories().resolve("local_$name.json").apply {
            writeText(json)
            Files.setLastModifiedTime(this, FileTime.fromMillis(modified))
        }

    @Test
    fun `sessions are keyed by their claude session id, with the folder they were started in`() {
        write("a", """{"sessionId":"local_a","cliSessionId":"c1","cwd":"E:\\p\\wt","originCwd":"E:\\p","toolSurfaceSnapshot":{"x":[1,{"y":"z"}]}}""")
        write("b", """{"sessionId":"local_b","cliSessionId":"c2","originCwd":"E:\\q"}""")
        write("c", """{"sessionId":"local_c","originCwd":"E:\\q","title":"never started"}""")
        dataDir.resolve("claude_profile/claude-code-sessions/account/org/backlog").createDirectories().resolve("tasks.json").writeText("{}")
        assertEquals(mapOf("c1" to Path.of("E:\\p"), "c2" to Path.of("E:\\q")), DesktopSessionReader(dataDir).read())
    }

    @Test
    fun `no data directory means no Desktop sessions`() {
        assertEquals(emptyMap(), DesktopSessionReader(dataDir.resolve("missing")).read())
    }

    @Test
    fun `a file is read again once its mtime changes, and a removed one is dropped`() {
        val reader = DesktopSessionReader(dataDir)
        val file = write("a", """{"cliSessionId":"c1","originCwd":"E:\\old"}""")
        assertEquals(Path.of("E:\\old"), reader.read().getValue("c1"))
        write("a", """{"cliSessionId":"c1","originCwd":"E:\\new"}""")
        assertEquals(Path.of("E:\\old"), reader.read().getValue("c1"))
        write("a", """{"cliSessionId":"c1","originCwd":"E:\\new"}""", modified = 2000)
        assertEquals(Path.of("E:\\new"), reader.read().getValue("c1"))
        Files.delete(file)
        assertEquals(emptyMap(), reader.read())
    }

    @Test
    fun `a session without originCwd, or one claude session in two Desktop sessions, fails loudly`() {
        write("a", """{"cliSessionId":"c1"}""")
        assertFailsWith<IllegalStateException> { DesktopSessionReader(dataDir).read() }
        write("a", """{"cliSessionId":"c1","originCwd":"E:\\p"}""")
        write("b", """{"cliSessionId":"c1","originCwd":"E:\\p"}""")
        assertFailsWith<IllegalStateException> { DesktopSessionReader(dataDir).read() }
    }
}
