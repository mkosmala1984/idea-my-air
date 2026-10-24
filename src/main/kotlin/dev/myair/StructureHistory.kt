package dev.myair

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** One copy of `structure.json` as it was before a change made by [source]. */
data class Snapshot(val name: String, val taken: LocalDateTime, val source: ChangeSource)

/**
 * Copies of `structure.json` taken before each change, named `structure-<yyyyMMdd-HHmmss-SSS>-<source>.json` so that
 * name order is time order; only the newest [keep] stay. A name taken twice in one millisecond fails rather than
 * overwriting a copy.
 */
class StructureHistory(private val dir: Path, private val keep: Int = 20, private val clock: Clock = Clock.systemDefaultZone()) {
    fun snapshot(text: String, source: ChangeSource) {
        dir.createDirectories()
        Files.writeString(dir.resolve("structure-${LocalDateTime.now(clock).format(STAMP)}-${source.label}.json"), text, CREATE_NEW, WRITE)
        list().drop(keep).forEach { dir.resolve(it.name).deleteExisting() }
    }

    /** Newest first. */
    fun list(): List<Snapshot> {
        if (!Files.isDirectory(dir)) return emptyList()
        return dir.listDirectoryEntries("structure-*.json").map { file ->
            val match = checkNotNull(NAME.matchEntire(file.name)) { "unexpected file in history: $file" }
            Snapshot(file.name, LocalDateTime.parse(match.groupValues[1], STAMP), ChangeSource.valueOf(match.groupValues[2].uppercase()))
        }.sortedByDescending { it.name }
    }

    /** The text of a snapshot [list] returns; any other name is refused, so no path outside the history is read. */
    fun read(name: String): String {
        require(list().any { it.name == name }) { "no snapshot named '$name'" }
        return dir.resolve(name).readText()
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        val NAME = Regex("structure-(\\d{8}-\\d{6}-\\d{3})-(${ChangeSource.entries.joinToString("|") { it.label }})\\.json")
    }
}
