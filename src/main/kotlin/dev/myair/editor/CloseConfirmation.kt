package dev.myair.editor

import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import com.intellij.openapi.ui.Messages
import dev.myair.claude.SessionStatus

/**
 * Whether ending a session's process now cuts its work short: `claude` works or waits for the user, or a background task it
 * started still runs. An idle process is not; nor one still starting or already ended, which have no [status].
 */
fun interrupts(status: SessionStatus?, runningTasks: Int): Boolean =
    status == SessionStatus.WORKING || status == SessionStatus.NEEDS_INPUT || runningTasks > 0

/** What ends the sessions: their project closing, the IDE exiting or restarting, or the plan a manager manages going. */
enum class Closing(val title: String, val ends: String, val ok: String, val cancel: String) {
    PROJECT("Close Project", "Closing the project", "Close Project", "Keep Open"),
    EXIT("Exit IDE", "Exiting", "End Sessions and Exit", "Keep IDE Open"),
    RESTART("Restart IDE", "Restarting", "End Sessions and Restart", "Keep IDE Open"),
    REMOVE_PLAN("Remove Plan", "Removing the plan", "Remove Plan", "Keep Plan"),
    DELETE_FOLDER("Delete Folder", "Deleting the folder", "Delete Folder", "Keep Folder"),
}

/** The question asked before [Closing] ends sessions; [ok] goes on. */
data class Confirmation(val title: String, val message: String, val ok: String, val cancel: String)

/** The question about the sessions titled [titles] that [closing] would interrupt ([interrupts]). */
fun confirmation(closing: Closing, titles: List<String>): Confirmation {
    require(titles.isNotEmpty()) { "no session to ask about" }
    val one = titles.size == 1
    val message =
        if (one) "'${titles.single()}' is still running. ${closing.ends} ends it."
        else "${titles.size} Claude sessions are still running:\n\n${titles.joinToString("\n")}\n\n${closing.ends} ends them."
    return Confirmation("${closing.title} with Running Claude Session${if (one) "" else "s"}?", message, closing.ok, closing.cancel)
}

/** Asks before [project] closes with sessions it would interrupt; during an exit [ExitConfirmation] already asked about all. */
class ProjectCloseConfirmation : ProjectCloseHandler {
    override fun canClose(project: Project): Boolean =
        ApplicationManager.getApplication().isExitInProgress || confirmed(project, Closing.PROJECT)
}

/** Asks before the IDE exits or restarts with sessions it would interrupt; registered by [SessionTerminals]. */
class ExitConfirmation : ApplicationListener {
    override fun canExitApplication() = confirmed(null, Closing.EXIT)
    override fun canRestartApplication() = confirmed(null, Closing.RESTART)
}

/** Whether [closing] may go on: nothing of [project] (of every project when null) would be interrupted, or the user says so. */
private fun confirmed(project: Project?, closing: Closing) = confirmed(project, closing, SessionTerminals.getInstance().interrupted(project))

/** Whether [closing] may end the sessions titled [titles] that it would interrupt: there are none, or the user says so. */
fun confirmed(project: Project?, closing: Closing, titles: List<String>): Boolean {
    if (titles.isEmpty()) return true
    val (title, message, ok, cancel) = confirmation(closing, titles)
    return Messages.showOkCancelDialog(project, message, title, ok, cancel, Messages.getWarningIcon()) == Messages.OK
}
