package dev.myair.editor

import dev.myair.claude.SessionStatus

/**
 * Whether ending a session's process now cuts its work short: `claude` works or waits for the user, or a background task it
 * started still runs. An idle process is not; nor one still starting or already ended, which have no [status].
 */
fun interrupts(status: SessionStatus?, runningTasks: Int): Boolean =
    status == SessionStatus.WORKING || status == SessionStatus.NEEDS_INPUT || runningTasks > 0

/** What ends the sessions: their project closing, or the IDE exiting or restarting. */
enum class Closing(val title: String, val ends: String, val ok: String, val cancel: String) {
    PROJECT("Close Project", "Closing the project", "Close Project", "Keep Open"),
    EXIT("Exit IDE", "Exiting", "End Sessions and Exit", "Keep IDE Open"),
    RESTART("Restart IDE", "Restarting", "End Sessions and Restart", "Keep IDE Open"),
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
