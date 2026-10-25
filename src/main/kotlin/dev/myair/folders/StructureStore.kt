package dev.myair.folders

import dev.myair.store.writeAtomically
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** What made a change to `structure.json`; snapshot names carry it, so an undo can find the change it is after. */
enum class ChangeSource {
    /** A tree action or drop. */
    UI,

    /** A new session filed in the target folder, or a `/clear` carrying its folder over. */
    AUTO,

    /** An agent's change list. */
    MCP,

    /** A snapshot restored. */
    RESTORE;

    val label = name.lowercase()
}

/**
 * `structure.json`, shared by every IDE. Each change re-reads the file before applying, so two IDEs editing at once lose
 * at most a change made in the same few milliseconds, and the replaced version goes to [history] first. A missing file
 * is an empty structure.
 */
class StructureStore(val file: Path, val history: StructureHistory = StructureHistory(file.resolveSibling("history"))) {
    init {
        file.parent.createDirectories()
    }

    fun read() = if (file.exists()) SessionFolders.parse(file.readText()) else SessionFolders()

    @Synchronized
    fun update(source: ChangeSource, op: (SessionFolders) -> SessionFolders): SessionFolders {
        val text = if (file.exists()) file.readText() else null
        val before = text?.let(SessionFolders::parse) ?: SessionFolders()
        val after = op(before)
        if (after == before) return after
        if (text != null) history.snapshot(text, source)
        writeAtomically(file, after.toJson())
        return after
    }
}
