package dev.myair.review

import dev.myair.actions.runClaude
import dev.myair.editor.claudeExecutable
import dev.myair.store.Profile
import dev.myair.store.profileEnv
import java.nio.file.Path
import java.time.Duration

object Reviewers {
    /** Starts `claude` with [args] in [dir] as [profile] (off the EDT) and returns the job id it printed. */
    fun start(dir: Path, args: List<String>, profile: Profile): String =
        parseJobId(runClaude(listOf(claudeExecutable().path) + args, dir, "", Duration.ofSeconds(30), profileEnv(profile)))
}
