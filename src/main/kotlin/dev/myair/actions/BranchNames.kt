package dev.myair.actions

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val TICKET_KEY = Regex("""\b[A-Z][A-Z0-9]+-\d+\b""")
private val SLUG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
private const val NAMING_MINUTES = 2L

/** The first Jira issue key in [text], such as `TR-1234`; null when it names none. */
fun ticketKey(text: String): String? = TICKET_KEY.find(text)?.value

/** `<key>-<slug>`, [slug] alone without a key; the model's reply must be lowercase words joined by hyphens, one line. */
fun branchName(key: String?, slug: String): String {
    val line = slug.trim()
    check(SLUG.matches(line)) { "Haiku answered with no branch slug:\n$slug" }
    return listOfNotNull(key, line).joinToString("-")
}

/** What Haiku is asked; the key is left out of its answer and put back by [branchName], so it is never misspelled. */
fun namingPrompt(text: String) = """
    You name git branches. Reply with one line only: an English slug of 2 to 6 lowercase words joined by hyphens,
    describing the task below. When the task names a Jira issue key, read that issue with the Jira tools first and
    describe the issue. Do not include the issue key.

    Task:
    $text
""".trimIndent()

/**
 * A flat branch name for the task [text] (`TR-1234-short-description`), named by Haiku through `claude -p` in [dir],
 * which may read the Jira issue [text] names with [jiraTools]. Without a saved transcript, so it never shows as a session.
 */
fun suggestBranchName(dir: Path, text: String, jiraTools: List<String>): String {
    require(text.isNotBlank()) { "no task to name a branch for" }
    val claude = checkNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("claude")) { "claude executable not found on PATH" }
    val args = listOf(claude.path, "-p", "--model", "haiku", "--no-session-persistence", "--output-format", "text") +
        (if (jiraTools.isEmpty()) emptyList() else listOf("--allowedTools") + jiraTools) + listOf("--", namingPrompt(text))
    val process = ProcessBuilder(args).directory(dir.toFile()).start()
    // claude -p reads a piped stdin to its end before it answers.
    process.outputStream.close()
    val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
    val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
    if (!process.waitFor(NAMING_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        error("claude did not name the branch within $NAMING_MINUTES minutes")
    }
    check(process.exitValue() == 0) { "claude -p failed (exit ${process.exitValue()}):\n${stderr.get().trim()}" }
    return branchName(ticketKey(text), stdout.get())
}
