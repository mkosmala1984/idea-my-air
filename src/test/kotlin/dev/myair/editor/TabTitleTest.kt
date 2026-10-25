package dev.myair.editor

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class TabTitleTest {
    private val ide = Path.of("E:/dev/projects/my-air")

    @Test
    fun `a session of the open project or a folder below it keeps its title`() {
        assertEquals("Fix", tabTitle("Fix", ide, ide))
        assertEquals("Fix", tabTitle("Fix", ide.resolve("taskbar"), ide))
    }

    @Test
    fun `a session of another project names that project, even one whose path starts with the same text`() {
        assertEquals("[rtm-cloud] Fix", tabTitle("Fix", Path.of("E:/dev/projects/rtm-cloud"), ide))
        assertEquals("[my-air-2] Fix", tabTitle("Fix", Path.of("E:/dev/projects/my-air-2"), ide))
    }
}
