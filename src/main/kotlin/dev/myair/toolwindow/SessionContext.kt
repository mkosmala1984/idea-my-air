package dev.myair.toolwindow

import dev.myair.actions.GitState
import dev.myair.claude.Artifact
import dev.myair.claude.BackgroundTask
import dev.myair.claude.Brief
import dev.myair.claude.ClaudeSession
import java.nio.file.Path
import kotlin.io.path.extension

/** The file types of the session's own writing, as Claude Desktop's Outputs lists them: plans, notes, pages. */
val OUTPUT_EXTENSIONS = setOf("md", "html")

/**
 * What the context pane shows of a session. [project] is the name of its project folder, [worktree] whether it runs in
 * a linked worktree of it; [files] all files it wrote, first touch first, [outputs] the [OUTPUT_EXTENSIONS] ones of them,
 * the newest first; [background] its tasks
 * still running, none without its process; [git] null outside a work tree and while it is being read.
 */
data class SessionContext(
    val project: String, val projectPath: Path, val cwd: Path, val worktree: Boolean, val desktop: Boolean, val addedDirs: List<Path>,
    val brief: Brief?, val files: List<Path>, val outputs: List<Path>, val artifacts: List<Artifact>, val background: List<BackgroundTask>, val cwdExists: Boolean,
    val git: GitState?,
)

/** [ClaudeSession.changedFiles] keeps the order files were first written, without repeats. */
fun contextOf(session: ClaudeSession, cwdExists: Boolean, live: Boolean, git: GitState?) = SessionContext(
    (session.project.fileName ?: session.project).toString(), session.project, session.cwd, session.cwd != session.project, session.desktop,
    session.addedDirs, session.brief, session.changedFiles, session.changedFiles.filter { it.extension.lowercase() in OUTPUT_EXTENSIONS }.reversed(), session.artifacts,
    if (live) session.background else emptyList(), cwdExists, git,
)

/** Files inside the session's folder read relative to it; anything else keeps its full path. */
fun fileLabel(path: Path, cwd: Path) = if (path.startsWith(cwd)) cwd.relativize(path).toString() else path.toString()
