package dev.myair.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.components.JBList
import dev.myair.claude.ClaudeSession
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Pins
import java.awt.Graphics
import javax.swing.Icon

/** A mark kept in [Pins], toggled from the row menus and the row buttons; [change] sets it on the ids or clears it. */
class Mark(val text: String, val description: String, val icon: Icon, val marked: (Pins) -> Set<String>, val change: (Pins, List<String>, Boolean) -> Pins)

/** A running session is listed anyway; a pin keeps it there after its process ends. */
val PIN = Mark("Pin Session", "Keep the session in the list above the tree after its process ends", AllIcons.General.Pin_tab, Pins::sessions) { pins, ids, on ->
    if (on) pins.pin(ids) else pins.unpin(ids)
}

val SETTLE = Mark("Settled", "Hide the session from the list and the tree; Show All Sessions shows it again", AllIcons.Actions.Checked, Pins::settled) { pins, ids, on ->
    if (on) pins.settle(ids) else pins.unsettle(ids)
}

fun pinAction(service: ClaudeSessionsService, selected: () -> List<ClaudeSession>) = PinsToggle(PIN, service, selected)

fun settleAction(service: ClaudeSessionsService, selected: () -> List<ClaudeSession>) =
    PinsToggle(SETTLE, service, selected).apply {
        // A toggle keeps its popup open by default; settling hides the row the menu was opened on.
        templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
    }

/** Checked when all [selected] sessions carry the [mark]; toggling sets or clears it on them. */
class PinsToggle(private val mark: Mark, private val service: ClaudeSessionsService, private val selected: () -> List<ClaudeSession>) :
    ToggleAction(mark.text, mark.description, mark.icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = selected().isNotEmpty()
    }
    override fun isSelected(e: AnActionEvent) = selected().let { it.isNotEmpty() && it.all { session -> session.id in mark.marked(service.pins) } }
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val ids = selected().map { it.id }
        service.updatePins { mark.change(it, ids, state) }
    }
}

class TrailerList : JBList<ClaudeSession>() {
    lateinit var trailer: SessionTrailer

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        trailer.paint(g)
    }
}
