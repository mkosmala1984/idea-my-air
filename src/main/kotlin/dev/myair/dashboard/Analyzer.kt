package dev.myair.dashboard

import com.google.gson.JsonParser
import dev.myair.claude.ClaudeSession
import dev.myair.editor.claudeExecutable
import dev.myair.store.Homes
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.useLines

/** What `--json-schema` makes Haiku answer. */
const val ANALYSIS_SCHEMA = """{"type":"object","properties":{"verdict":{"type":"string","enum":["done","open","unclear"]},"left":{"type":"string"}},"required":["verdict","left"],"additionalProperties":false}"""

private const val PARALLEL = 3

fun analysisPrompt(digest: String) = """
    Below is a digest of a Claude Code session: its record, what the human asked and the agent's last messages.
    Judge whether the work the human asked for is finished. Weigh the last messages over the first ones: later requests
    replace earlier ones. Answer with verdict "done" when nothing is left, "open" when something is, "unclear" when the
    digest cannot tell. In "left", say in one or two sentences what remains to be done (empty when done), written in the
    language of the human prompts.

    $digest
""".trimIndent()

/**
 * Haiku without tools, MCP servers or user settings (so no hooks or plugins), leaving no transcript. The prompt goes
 * through stdin: a digest may exceed Windows' 32767-character command line, and CreateProcess keeps a double quote of
 * an argument only escaped, which Java does not do, so the schema is escaped here.
 */
fun analysisArgs(claude: Path): List<String> = listOf(
    claude.toString(), "-p", "--model", "haiku", "--no-session-persistence", "--output-format", "json",
    "--json-schema", ANALYSIS_SCHEMA.replace("\"", "\\\""), "--tools", "", "--strict-mcp-config", "--setting-sources", "project",
)

/** Reads `claude -p --output-format json`'s envelope, whose `structured_output` holds the reply matching [ANALYSIS_SCHEMA]. */
fun parseAnalysis(stdout: String, lastMessage: Instant, now: Instant): Analysis {
    val envelope = JsonParser.parseString(stdout).asJsonObject
    check(envelope["is_error"]?.asBoolean != true) { "claude failed: ${envelope["result"]?.asString}" }
    val reply = envelope["structured_output"]?.takeIf { it.isJsonObject }?.asJsonObject
    checkNotNull(reply) { "claude gave no structured output: $stdout" }
    val verdict = Verdict.entries.firstOrNull { it.label == reply["verdict"]?.asString }
    val left = reply["left"]?.takeIf { it.isJsonPrimitive }?.asString
    check(verdict != null && left != null && (verdict == Verdict.DONE || left.isNotBlank())) { "claude answered off the schema: $stdout" }
    return Analysis(lastMessage, verdict, left.trim(), now)
}

/** Runs [claude] in `~/.my-air/analysis`; a timeout, an interrupt or a non-zero exit kills it with its descendants. */
fun analyze(claude: Path, digest: String, lastMessage: Instant, timeout: Duration = Duration.ofMinutes(2)): Analysis {
    val dir = Homes.myAir.resolve("analysis").createDirectories()
    val process = ProcessBuilder(analysisArgs(claude)).directory(dir.toFile()).start()
    try {
        // Written apart from reading, so neither pipe fills while the other waits; claude -p answers once stdin ends.
        val stdin = CompletableFuture.runAsync { process.outputStream.use { it.write(analysisPrompt(digest).toByteArray()) } }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        check(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "claude did not answer within $timeout" }
        check(process.exitValue() == 0) { "claude -p failed (exit ${process.exitValue()}): ${stderr.get().trim()}" }
        stdin.get()
        return parseAnalysis(stdout.get(), lastMessage, Instant.now())
    } finally {
        if (process.isAlive) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}

/** Reads the session's transcript, builds its digest and asks Haiku. */
fun analyzeSession(session: ClaudeSession, chips: List<Chip>, plan: String?): Analysis {
    val transcript = checkNotNull(session.transcript) { "session ${session.id} has no transcript" }
    val excerpt = transcript.useLines { excerptOf(it) }
    return analyze(claudeExecutable().toPath(), digestOf(session, chips, plan, excerpt), session.lastMessage)
}

/**
 * Analyses [sessions] with [analyzeOne], [PARALLEL] at a time on [executor]. [onResult] gets each result on the thread
 * that made it; [cancel] stops starting more and interrupts those running, whose results are dropped; [onEnd] runs once.
 */
class AnalysisRun(
    sessions: List<ClaudeSession>,
    private val analyzeOne: (ClaudeSession) -> Analysis,
    private val executor: Executor,
    private val onResult: (ClaudeSession, Result<Analysis>) -> Unit,
    private val onEnd: () -> Unit,
) {
    val total = sessions.size
    private val queue = ConcurrentLinkedQueue(sessions)
    private val finished = AtomicInteger()
    val done: Int get() = finished.get()
    private val cancelled = AtomicBoolean()
    private val started = AtomicBoolean()
    private val workers = AtomicInteger(minOf(PARALLEL, total))
    private val running = HashSet<Thread>()

    fun start() {
        check(started.compareAndSet(false, true)) { "analysis run started twice" }
        if (total == 0) onEnd() else repeat(workers.get()) { executor.execute(::work) }
    }

    fun cancel() {
        cancelled.set(true)
        synchronized(running) { running.forEach(Thread::interrupt) }
    }

    private fun work() {
        try {
            while (!cancelled.get()) {
                val session = queue.poll() ?: break
                synchronized(running) { running += Thread.currentThread() }
                val result = runCatching { analyzeOne(session) }
                synchronized(running) {
                    running -= Thread.currentThread()
                    // An interrupt from cancel must not reach the pool's next task.
                    Thread.interrupted()
                }
                if (cancelled.get()) break
                finished.incrementAndGet()
                onResult(session, result)
            }
        } finally {
            if (workers.decrementAndGet() == 0) onEnd()
        }
    }
}
