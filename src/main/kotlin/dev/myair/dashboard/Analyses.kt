package dev.myair.dashboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.myair.store.writeAtomically
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** Haiku's judgement of whether a session's work is finished. */
enum class Verdict(val label: String) { DONE("done"), OPEN("open"), UNCLEAR("unclear") }

/** What Haiku said about a session whose last message was [lastMessage]: current only while the session has no newer one. */
data class Analysis(val lastMessage: Instant, val verdict: Verdict, val left: String, val analyzedAt: Instant) {
    init {
        require(verdict == Verdict.DONE || left.isNotBlank()) { "a $verdict analysis must say what is left" }
    }
}

/** One `<sessionId>.json` per analysed session in [dir], shared by every IDE. */
class AnalysisStore(val dir: Path) {
    fun readAll(): Map<String, Analysis> {
        if (!dir.exists()) return emptyMap()
        return dir.listDirectoryEntries().filter { it.extension == "json" }.associate { file ->
            val analysis = try {
                parse(file.readText())
            } catch (e: RuntimeException) {
                throw IllegalStateException("malformed analysis $file: ${e.message}", e)
            }
            file.nameWithoutExtension to analysis
        }
    }

    fun write(sessionId: String, analysis: Analysis) {
        dir.createDirectories()
        val json = mapOf(
            "lastMessage" to analysis.lastMessage.toString(), "verdict" to analysis.verdict.label,
            "left" to analysis.left, "analyzedAt" to analysis.analyzedAt.toString(),
        )
        writeAtomically(dir.resolve("$sessionId.json"), GsonBuilder().setPrettyPrinting().create().toJson(json))
    }

    private fun parse(text: String): Analysis {
        val o = JsonParser.parseString(text).asJsonObject
        check(o.keySet() == KEYS) { "expected keys $KEYS, found ${o.keySet()}" }
        val verdict = o["verdict"].asString
        return Analysis(
            Instant.parse(o["lastMessage"].asString),
            checkNotNull(Verdict.entries.firstOrNull { it.label == verdict }) { "unknown verdict $verdict" },
            o["left"].asString, Instant.parse(o["analyzedAt"].asString),
        )
    }

    private companion object {
        val KEYS = setOf("lastMessage", "verdict", "left", "analyzedAt")
    }
}
