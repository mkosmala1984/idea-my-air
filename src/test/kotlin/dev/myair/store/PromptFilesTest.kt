package dev.myair.store

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptFilesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `writing a prompt deletes those of IDEs that are gone and keeps those of running ones`() {
        val dead = ProcessBuilder("cmd", "/c", "exit").start().also { it.waitFor() }.pid()
        val gone = PromptFiles.write(dir, "old", pid = dead)
        val mine = PromptFiles.write(dir, "# Plan\n\n\"x\" <y>")
        assertFalse(gone.exists())
        assertTrue(mine.exists())
        assertEquals("# Plan\n\n\"x\" <y>", mine.readText())
        assertEquals(mine, PromptFiles.write(dir, "next").let { mine.takeIf { it.exists() } })
    }
}
