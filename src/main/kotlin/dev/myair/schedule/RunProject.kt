package dev.myair.schedule

import java.nio.file.Path

/** The index in [bases] of the open project a run in [dir] belongs to: the deepest holding it, else the first; null when none is open. */
fun pickProject(dir: Path, bases: List<Path>): Int? {
    if (bases.isEmpty()) return null
    return bases.withIndex().filter { dir.startsWith(it.value) }.maxByOrNull { it.value.nameCount }?.index ?: 0
}

/**
 * Why a run of [task] in [dir] (null: no IDE project is open to start it in) cannot start, or null when it can.
 * [folderExists]: the task's folder is still in the folder tree. [profileListed]: its profile is still in My Air's settings.
 */
fun preStartError(task: ScheduledTask, dir: Path?, folderExists: Boolean, claudeFound: Boolean, profileListed: Boolean): String? = when {
    dir == null -> "no IDE project open"
    task.preset.missingDirs(dir).isNotEmpty() -> "directory missing: ${task.preset.missingDirs(dir).joinToString()}"
    task.folder != null && !folderExists -> "folder deleted"
    !claudeFound -> "claude not found on PATH"
    !profileListed -> "profile ${task.preset.profile} is no longer listed"
    else -> null
}
