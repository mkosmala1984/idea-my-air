package dev.myair.toolwindow

import dev.myair.actions.GitState
import dev.myair.claude.Artifact
import dev.myair.claude.ClaudeSession
import java.nio.file.Path
import kotlin.io.path.extension

/** The file types of the session's own writing, as Claude Desktop's Outputs lists them: plans, notes, pages. */
val OUTPUT_EXTENSIONS = setOf("md", "html")

/**
 * What the context pane shows of a session. [project] is the name of its project folder, [worktree] whether it runs in
 * a linked worktree of it; [outputs] the [OUTPUT_EXTENSIONS] files it wrote, the newest first; [git] null outside a
 * work tree and while it is being read.
 */
data class SessionContext(
    val project: String, val projectPath: Path, val worktree: Boolean, val desktop: Boolean, val addedDirs: List<Path>,
    val outputs: List<Path>, val artifacts: List<Artifact>, val cwdExists: Boolean, val git: GitState?,
)

/** [ClaudeSession.changedFiles] keeps the order files were first written, without repeats. */
fun contextOf(session: ClaudeSession, cwdExists: Boolean, git: GitState?) = SessionContext(
    (session.project.fileName ?: session.project).toString(), session.project, session.cwd != session.project, session.desktop,
    session.addedDirs, session.changedFiles.filter { it.extension.lowercase() in OUTPUT_EXTENSIONS }.reversed(), session.artifacts,
    cwdExists, git,
)
