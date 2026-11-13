package dev.myair.prompt

import com.intellij.ui.DocumentAdapter
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent

/**
 * Ties [area]'s message to the stored draft: every change goes to [save] once the message counts as edited. A panel
 * opened from a configuration ([kept]) counts as edited only after the user changes the message; text put in by [set]
 * (the configuration's prompt, a switch, clearing at start) is no such change, so the stored draft stays until then.
 */
class MessageDraft(private val area: JTextArea, kept: Boolean, private val save: (String) -> Unit) {
    private var edited = !kept
    private var setting = false

    init {
        area.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!setting) edited = true
                flush()
            }
        })
    }

    /** Puts [text] in from code: no edit by the user. */
    fun set(text: String) {
        setting = true
        try {
            area.text = text
        } finally {
            setting = false
        }
    }

    /** Saves the message, once it counts as edited. */
    fun flush() {
        if (edited) save(area.text)
    }
}
