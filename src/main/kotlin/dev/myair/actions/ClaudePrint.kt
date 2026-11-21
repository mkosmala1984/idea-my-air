package dev.myair.actions

import dev.myair.store.ProfileStore
import dev.myair.store.profileEnv
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Writing stdin blocks for as long as claude does not read it: on the shared common pool it would starve the IDE, and
// with that pool full a stdin never written leaves claude waiting for its prompt.
private val PIPES = Executors.newCachedThreadPool { Thread(it, "claude stdin").apply { isDaemon = true } }

/**
 * Runs a `claude` command line in [dir], writing [input] to its stdin (claude -p answers once stdin ends), and returns
 * its stdout. Its output goes to temp files, read once it exits: a process it leaves running (the daemon `claude --bg`
 * starts) may inherit them, which would keep a pipe open and its read blocked past [timeout]. A timeout, an interrupt or
 * a non-zero exit throws with what it printed; a timeout or an interrupt kills it with its descendants, never a
 * process left once it exited, as the daemon runs every background job.
 */
fun runClaude(
    args: List<String>,
    dir: Path,
    input: String,
    timeout: Duration,
    env: Map<String, String?> = profileEnv(ProfileStore.getInstance().default),
): String {
    val out = File.createTempFile("claude", ".out")
    val err = File.createTempFile("claude", ".err")
    var process: Process? = null
    try {
        process = processBuilder(args, dir, env).redirectOutput(out).redirectError(err).start()
        val stdin = CompletableFuture.runAsync({ process.outputStream.use { it.write(input.toByteArray()) } }, PIPES)
        check(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "claude did not answer within $timeout" }
        check(process.exitValue() == 0) { "claude failed (exit ${process.exitValue()}): ${err.readText().trim()} ${out.readText().trim()}".trim() }
        stdin.get()
        return out.readText()
    } finally {
        if (process?.isAlive == true) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        // Windows keeps a file a running process holds: the daemon's copy goes when the IDE exits.
        listOf(out, err).filterNot { it.delete() }.forEach { it.deleteOnExit() }
    }
}

/** A process of [args] in [dir] with the IDE's environment changed by [env]: a null value removes the variable. */
fun processBuilder(args: List<String>, dir: Path, env: Map<String, String?>): ProcessBuilder = ProcessBuilder(args).directory(dir.toFile()).apply {
    env.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
}
