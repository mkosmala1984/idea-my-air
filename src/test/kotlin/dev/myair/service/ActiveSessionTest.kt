package dev.myair.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveSessionTest {

    private val open = setOf("a", "b")

    @Test
    fun `a selected session tab wins`() {
        assertEquals("b", activeTab("b", "a") { it in open })
    }

    @Test
    fun `another file selected keeps the last session tab while it is open`() {
        assertEquals("a", activeTab(null, "a") { it in open })
    }

    @Test
    fun `the last session tab closed leaves none`() {
        assertNull(activeTab(null, "c") { it in open })
        assertNull(activeTab<String>(null, null) { it in open })
    }
}
