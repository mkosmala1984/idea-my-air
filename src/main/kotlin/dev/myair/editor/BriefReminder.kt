package dev.myair.editor

import com.google.gson.Gson
import dev.myair.store.writeAtomically
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** A my_air_set_brief call in a transcript: a `tool_use` item, not the tool lists claude also records there. */
const val BRIEF_CALL = "\"type\":\"tool_use\",\"id\":\"[^\"]*\",\"name\":\"mcp__[^\"]*__my_air_set_brief\""
const val BRIEF_REMINDER = "My Air: this session has no brief yet. Call my_air_set_brief as soon as you understand the request."

/**
 * A `UserPromptSubmit` hook that adds [BRIEF_REMINDER] to every prompt until the session's transcript holds a brief: the
 * system prompt alone gets the tool called in about a quarter of sessions. PowerShell, as the plugin runs on Windows only;
 * the script travels encoded, so no shell claude runs hooks through can mangle its quotes.
 */
fun briefReminderCommand(): String {
    check('\'' !in BRIEF_CALL && '\'' !in BRIEF_REMINDER) { "single-quoted in the script" }
    val script = """
        ${'$'}ProgressPreference = 'SilentlyContinue'
        [Console]::InputEncoding = [Text.Encoding]::UTF8
        ${'$'}t = ([Console]::In.ReadToEnd() | ConvertFrom-Json).transcript_path
        if (-not ((Test-Path -LiteralPath ${'$'}t) -and (Select-String -LiteralPath ${'$'}t -Pattern '$BRIEF_CALL' -Quiet))) { '$BRIEF_REMINDER' }
    """.trimIndent()
    val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return "powershell -NoProfile -NonInteractive -EncodedCommand $encoded"
}

/** Writes the settings file a session tab passes to claude (`--settings`) to install [briefReminderCommand] in [dir]. */
fun writeBriefReminderSettings(dir: Path): Path {
    val hook = mapOf("type" to "command", "command" to briefReminderCommand())
    dir.createDirectories()
    return dir.resolve("brief-reminder.json").also {
        writeAtomically(it, Gson().toJson(mapOf("hooks" to mapOf("UserPromptSubmit" to listOf(mapOf("hooks" to listOf(hook)))))))
    }
}
