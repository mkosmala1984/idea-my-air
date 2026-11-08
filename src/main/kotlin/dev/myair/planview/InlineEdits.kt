package dev.myair.planview

import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/**
 * A [PlanEdit] open in the plan view, its text component kept across rebuilds of the panel, so the text, caret and focus
 * survive a change the manager makes meanwhile.
 */
class OpenEdit(val edit: PlanEdit, val component: JTextComponent)

/** A title, next step or new item's title: Enter saves, Esc cancels. */
fun inlineField(text: String, save: () -> Unit, cancel: () -> Unit) = JBTextField(text).apply {
    on(CommonShortcuts.ENTER, save)
    on(CommonShortcuts.ESCAPE, cancel)
}

/** A Markdown description: Ctrl+Enter saves, Esc cancels; Save and Cancel buttons go beside it. */
fun inlineArea(text: String, save: () -> Unit, cancel: () -> Unit) = JBTextArea(text, 4, 0).apply {
    lineWrap = true
    wrapStyleWord = true
    on(CommonShortcuts.getCtrlEnter(), save)
    on(CommonShortcuts.ESCAPE, cancel)
}

// Through the keymap's dispatch, which runs before a component's own input map and would take Esc for the editor.
private fun JComponent.on(shortcuts: ShortcutSet, run: () -> Unit) = DumbAwareAction.create { run() }.registerCustomShortcutSet(shortcuts, this)
