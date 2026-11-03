package dev.myair.editor

import dev.myair.actions.Git
import java.nio.file.Path

/**
 * How a session tab stands out in a window: [otherProject], the session's project is not the window's; [worktree], the
 * work tree the session works in now when that is another one than its home, the window's for a session of the window's
 * project, its project's otherwise.
 */
data class TabMark(val otherProject: Boolean, val worktree: Git.Head?)

/**
 * The mark of a tab whose session of [sessionProject] (in work tree [projectHead]) works in [sessionHead], shown in the
 * window of the project at [ideBase] (in work tree [ideHead]); a head is null outside a repository.
 */
fun tabMark(sessionProject: Path, sessionHead: Git.Head?, projectHead: Git.Head?, ideBase: Path, ideHead: Git.Head?): TabMark {
    val other = isOtherProject(sessionProject, ideBase)
    val home = (if (other) projectHead else ideHead)?.root
    return TabMark(other, sessionHead?.takeIf { home != null && it.root != home })
}

/** The tooltip's lines: [title], the project, and for a [worktree] where it lies and its branch. */
fun tabTooltip(title: String, sessionProject: Path, worktree: Git.Head?) =
    listOf(title, "Project: $sessionProject") +
        listOfNotNull(worktree?.let { "Worktree: ${it.root}" }, worktree?.let { "Branch: ${it.branch ?: "detached"}" })
