package dev.myair.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SectionStatesTest {
    @Test
    fun `a section never toggled keeps its default`() {
        assertNull(sectionExpanded(listOf("+Files", "-Outputs"), "Skills"))
    }

    @Test
    fun `a toggle replaces the earlier state of that section only`() {
        val states = withSection(withSection(withSection(emptyList(), "Files", false), "Skills/pstack", true), "Files", true)
        assertEquals(listOf("+Skills/pstack", "+Files"), states)
        assertEquals(true, sectionExpanded(states, "Files"))
        assertEquals(false, sectionExpanded(withSection(states, "Skills/pstack", false), "Skills/pstack"))
    }

    @Test
    fun `a key that is a prefix of another is a section of its own`() {
        val states = withSection(listOf("-Skills/pstack"), "Skills", true)
        assertEquals(false, sectionExpanded(states, "Skills/pstack"))
        assertEquals(true, sectionExpanded(states, "Skills"))
    }
}
