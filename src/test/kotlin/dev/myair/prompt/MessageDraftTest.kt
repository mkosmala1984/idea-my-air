package dev.myair.prompt

import org.junit.jupiter.api.Test
import javax.swing.JTextArea
import kotlin.test.assertEquals

class MessageDraftTest {
    private val area = JTextArea()
    private val saved = mutableListOf<String>()

    @Test
    fun `text set from code leaves the draft of a panel opened from a configuration alone`() {
        val draft = MessageDraft(area, kept = true) { saved += it }
        draft.set("/code-review ")
        draft.set("/other ")
        draft.set("")
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `the user's edit saves, and every change after it`() {
        val draft = MessageDraft(area, kept = true) { saved += it }
        draft.set("/code-review ")
        area.document.insertString(area.document.length, "7506", null)
        draft.set("")
        assertEquals(listOf("/code-review 7506", ""), saved.distinct())
    }

    @Test
    fun `a plain panel saves every change`() {
        val draft = MessageDraft(area, kept = false) { saved += it }
        draft.set("fix the test")
        assertEquals("fix the test", saved.last())
    }
}
