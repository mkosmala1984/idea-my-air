package dev.myair.dashboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.claude.ClaudeSession
import dev.myair.claude.ClaudeSessionScanner

/** What the human asked in a session, in order, and the texts of its agent's last [LAST_TEXTS] replies, oldest first. */
data class Excerpt(val prompts: List<String>, val lastTexts: List<String>)

private const val LAST_TEXTS = 5
private const val ITEM_LIMIT = 2000
private const val DIGEST_LIMIT = 40_000

/** Reads a transcript's lines; sidechain and `isMeta` lines are not the conversation, a reply of tool calls alone says nothing. */
fun excerptOf(lines: Sequence<String>): Excerpt {
    val prompts = ArrayList<String>()
    val texts = ArrayDeque<String>()
    for (line in lines) {
        if (line.isBlank()) continue
        val o = JsonParser.parseString(line).asJsonObject
        if (o["isSidechain"]?.asBoolean == true || o["isMeta"]?.asBoolean == true) continue
        when (o["type"]?.asString) {
            "user" -> ClaudeSessionScanner.promptText(o)?.let(prompts::add)
            "assistant" -> agentText(o)?.let {
                texts.addLast(it)
                if (texts.size > LAST_TEXTS) texts.removeFirst()
            }
        }
    }
    return Excerpt(prompts, texts.toList())
}

private fun agentText(o: JsonObject): String? = o.getAsJsonObject("message")["content"].asJsonArray.map { it.asJsonObject }
    .filter { it["type"].asString == "text" }.joinToString("\n") { it["text"].asString }.trim().takeIf { it.isNotEmpty() }

/** What Haiku reads about a session: its own record first, then the conversation, the oldest prompts dropped to fit [DIGEST_LIMIT]. */
fun digestOf(session: ClaudeSession, chips: List<Chip>, plan: String?, excerpt: Excerpt): String {
    val prompts = excerpt.prompts.map(::cut)
    val head = buildString {
        appendLine("Title: ${session.title}")
        session.brief?.let { brief ->
            appendLine("Goal: ${brief.goal ?: "(none)"}")
            appendLine("Brief tasks:")
            brief.tasks.forEach { appendLine("[${it.status.name.lowercase()}] ${it.content}") }
        }
        plan?.let { appendLine("Plan task: $it") }
        appendLine("Signals: ${chips.joinToString(", ", transform = ::chipText).ifEmpty { "none" }}")
        appendLine("Files written:")
        session.changedFiles.forEach { appendLine(it.toString()) }
    }
    val tail = "Last agent messages:\n" + excerpt.lastTexts.joinToString("\n\n", transform = ::cut).ifEmpty { "(none)" }
    fun digest(dropped: Int) = buildString {
        append(head)
        appendLine("Human prompts:")
        if (dropped > 0) appendLine("($dropped earlier prompts left out)")
        prompts.drop(dropped).forEachIndexed { i, p -> appendLine("${dropped + i + 1}. $p") }
        append(tail)
    }
    return (0..prompts.size).asSequence().map(::digest).firstOrNull { it.length <= DIGEST_LIMIT }
        ?: error("digest of session ${session.id} exceeds $DIGEST_LIMIT characters without any prompt")
}

private fun cut(text: String) = if (text.length <= ITEM_LIMIT) text else text.take(ITEM_LIMIT - 1) + "…"
