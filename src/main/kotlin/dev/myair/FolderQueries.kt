package dev.myair

import com.google.gson.GsonBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** One page of [findSessions]: [total] counts every match, so a caller knows whether to ask for the next page. */
data class SessionPage(val total: Int, val offset: Int, val sessions: List<ClaudeSession>)

const val DEFAULT_LIMIT = 50
const val MAX_LIMIT = 100

/** Characters of each prompt an agent gets: enough to judge a topic, few enough for a page to fit its output limit. */
const val PROMPT_CHARS = 300

/**
 * The sessions matching every filter given, newest first, one page at a time. [since] and [until] bound the last
 * modification inclusively (see [timeBound]); [query] is the tree's filter; [folder] keeps sessions filed in that folder
 * or below it; [unfiled] true keeps the ones under `sessions`, false the filed ones. Bad input throws
 * [IllegalArgumentException] naming it.
 */
fun findSessions(
    sessions: List<ClaudeSession>, structure: SessionFolders, now: ZonedDateTime,
    since: String?, until: String?, query: String?, folder: String?, unfiled: Boolean?, offset: Int?, limit: Int?,
): SessionPage {
    val from = since?.let { timeBound(it, now, end = false) }
    val to = until?.let { timeBound(it, now, end = true) }
    require(folder == null || unfiled != true) { "folder and unfiled=true exclude each other" }
    val inFolder = folder?.let { path ->
        requireNotNull(structure.findByPath(path)) { "no folder '$path'" }.subtree().flatMap { it.items }.map { it.id }.toSet()
    }
    val filed = structure.all().flatMap { it.items }.map { it.id }.toSet()
    val size = limit ?: DEFAULT_LIMIT
    require(size in 1..MAX_LIMIT) { "limit $size outside 1..$MAX_LIMIT" }
    val skip = offset ?: 0
    require(skip >= 0) { "negative offset $skip" }
    val matches = sessions.sortedByDescending { it.lastModified }.filter { session ->
        val modified = session.lastModified.toInstant()
        (from == null || modified >= from) && (to == null || modified <= to) && (query == null || session.matches(query)) &&
            (inFolder == null || session.id in inFolder) && (unfiled == null || unfiled == (session.id !in filed))
    }
    return SessionPage(matches.size, skip, matches.drop(skip).take(size))
}

private val RELATIVE = Regex("(\\d+)([dh])")

/**
 * A time given as `<n>d` or `<n>h` before [now], an ISO local date-time, or an ISO date: its start, or its last instant
 * when it is the [end] of a range, so `until=2026-10-24` covers that whole day. Dates are in [now]'s zone.
 */
fun timeBound(text: String, now: ZonedDateTime, end: Boolean): Instant {
    RELATIVE.matchEntire(text)?.let { match ->
        val n = match.groupValues[1].toLong()
        return (if (match.groupValues[2] == "d") now.minusDays(n) else now.minusHours(n)).toInstant()
    }
    try {
        if ('T' in text) return LocalDateTime.parse(text).atZone(now.zone).toInstant()
        val date = LocalDate.parse(text)
        return if (end) date.plusDays(1).atStartOfDay(now.zone).toInstant().minusNanos(1) else date.atStartOfDay(now.zone).toInstant()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("'$text' is not <n>d, <n>h, a date (2026-10-17) or a date-time (2026-10-24T09:00)", e)
    }
}

private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

fun SessionPage.toJson(structure: SessionFolders, zone: ZoneId): String = gson.toJson(
    linkedMapOf(
        "total" to total,
        "offset" to offset,
        "sessions" to sessions.map { session ->
            linkedMapOf(
                "id" to session.id,
                "title" to session.title,
                "project" to session.cwd.toString(),
                "modified" to session.lastModified.toInstant().atZone(zone).toOffsetDateTime().toString(),
                "folder" to structure.folderOf(session.id)?.let(structure::pathOf),
                "prompts" to session.prompts.map { it.take(PROMPT_CHARS) },
                "changedFiles" to session.changedFiles.size,
            )
        },
    ),
)

/** The folder tree in the user's order, each folder by path with the ids of the sessions filed directly in it. */
fun SessionFolders.treeJson(): String {
    fun node(folder: Folder, prefix: String): Map<String, Any> = linkedMapOf(
        "path" to prefix + folder.name,
        "sessions" to folder.items.map { it.id },
        "folders" to folder.folders.map { node(it, "$prefix${folder.name}/") },
    )
    return gson.toJson(folders.map { node(it, "") })
}

/** Snapshots newest first with their counts; one that does not parse shows its error instead, so it hides no other. */
fun StructureHistory.toJson(): String = gson.toJson(
    list().map { snapshot ->
        val entry = linkedMapOf<String, Any>("name" to snapshot.name, "taken" to snapshot.taken.toString(), "source" to snapshot.source.label)
        try {
            val folders = SessionFolders.parse(read(snapshot.name))
            entry["folders"] = folders.all().count()
            entry["sessions"] = folders.all().sumOf { it.items.size }
        } catch (e: Exception) {
            entry["error"] = "${e.javaClass.simpleName}: ${e.message}"
        }
        entry
    },
)
