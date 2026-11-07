package dev.myair.editor

import dev.myair.claude.BackgroundTask
import dev.myair.claude.SessionStatus
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a session runs without a tab before it may end, as Air's `DETACHED_TERMINAL_RETIREMENT_DELAY`. */
val DETACHED_GRACE = 60.seconds

/**
 * Whether a session whose tab closed [detachedFor] ago ends now: only when it is idle and none of its own background tasks
 * runs. One working or waiting for the user stays; [status] is null until the process registers, which also keeps it.
 */
fun retires(detachedFor: Duration, status: SessionStatus?, runningTasks: Int): Boolean =
    detachedFor >= DETACHED_GRACE && status == SessionStatus.IDLE && runningTasks == 0

/**
 * The background tasks the process started at [processStart] still runs. The transcript never records that the tasks of an
 * earlier process died with it, so only those started since count; a process whose start is unknown is gone and runs none.
 */
fun runningTasks(tasks: List<BackgroundTask>, processStart: Instant?): Int =
    if (processStart == null) 0 else tasks.count { it.end == null && it.started >= processStart }

/** What closing a session tab does to its process. */
enum class Release { IGNORE, CLOSE, DETACH }

/**
 * A tab that does not show the process ([owns]) leaves it alone. One that does ends it when it never started (it starts only
 * once a view shows, so it never would) or already ended, and when sessions are not kept, unless the platform closes the
 * tab to reopen it ([reopening], a move to another window); otherwise it runs on, detached.
 */
fun releaseOf(owns: Boolean, started: Boolean, ended: Boolean, keep: Boolean, reopening: Boolean): Release = when {
    !owns -> Release.IGNORE
    !started || ended -> Release.CLOSE
    keep || reopening -> Release.DETACH
    else -> Release.CLOSE
}
