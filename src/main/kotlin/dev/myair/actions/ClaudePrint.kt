package dev.myair.actions

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Pipe I/O blocks for as long as claude runs: on the shared common pool it would starve the IDE, and with that pool
// full a stdin never written leaves claude waiting for its prompt.
private val PIPES = Executors.newCachedThreadPool { Thread(it, "claude -p pipes").apply { isDaemon = true } }

/**
 * Runs a `claude -p` command line in [dir], writing [input] to its stdin (claude -p answers once stdin ends), and
 * returns its stdout. A timeout, an interrupt or a non-zero exit kills it with its descendants and throws with what it printed.
 */
fun runClaude(args: List<String>, dir: Path, input: String, timeout: Duration): String {
    val process = ProcessBuilder(args).directory(dir.toFile()).start()
    try {
        val stdin = CompletableFuture.runAsync({ process.outputStream.use { it.write(input.toByteArray()) } }, PIPES)
        val stderr = CompletableFuture.supplyAsync({ process.errorStream.readAllBytes().toString(Charsets.UTF_8) }, PIPES)
        val stdout = CompletableFuture.supplyAsync({ process.inputStream.readAllBytes().toString(Charsets.UTF_8) }, PIPES)
        check(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "claude did not answer within $timeout" }
        check(process.exitValue() == 0) { "claude -p failed (exit ${process.exitValue()}): ${stderr.get().trim()} ${stdout.get().trim()}".trim() }
        stdin.get()
        return stdout.get()
    } finally {
        if (process.isAlive) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
