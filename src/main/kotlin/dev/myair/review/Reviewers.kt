package dev.myair.review

import dev.myair.actions.runClaude
import dev.myair.editor.claudeExecutable
import java.nio.file.Path
import java.time.Duration

object Reviewers {
    /** Starts `claude` with [args] in [dir] (off the EDT) and returns the job id it printed. */
    fun start(dir: Path, args: List<String>): String =
        parseJobId(runClaude(listOf(claudeExecutable().path) + args, dir, "", Duration.ofSeconds(30)))
}
