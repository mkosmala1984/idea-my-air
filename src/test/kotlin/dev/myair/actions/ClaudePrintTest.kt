package dev.myair.actions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs real Windows processes in place of `claude`. */
class ClaudePrintTest {
    // Not a @TempDir: the process left running keeps its working directory from being deleted.
    private val dir = Path.of(System.getProperty("java.io.tmpdir"))

    @Test fun `a process left running that inherits the output does not hold the call past its exit`() {
        // `start /b` leaves a sleeping PowerShell holding cmd's stdout, as the daemon `claude --bg` starts may.
        val started = System.nanoTime()
        val out = runClaude(listOf("cmd", "/c", "start /b powershell -NoProfile -Command Start-Sleep 20 & echo backgrounded"), dir, "", Duration.ofSeconds(10))
        assertEquals("backgrounded", out.trim())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10), "returned only after the left process ended")
    }

    @Test fun `a non-zero exit throws with what it printed`() {
        val e = assertThrows<IllegalStateException> { runClaude(listOf("cmd", "/c", "echo oops 1>&2 & exit 3"), dir, "", Duration.ofSeconds(10)) }
        assertContains(e.message!!, "claude failed (exit 3): oops")
    }

    @Test fun `a process past the timeout is killed and throws`() {
        val e = assertThrows<IllegalStateException> { runClaude(listOf("powershell", "-NoProfile", "-Command", "Start-Sleep 20"), dir, "", Duration.ofSeconds(2)) }
        assertContains(e.message!!, "did not answer within")
    }
}
