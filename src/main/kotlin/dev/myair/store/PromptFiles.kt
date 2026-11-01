package dev.myair.store

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * System prompt files of running session tabs, `<IDE pid>-<uuid>.md` in [dir]: `claude` reads one at its start and again
 * after a compaction, so a file lives as long as its tab. The tab deletes its own; one whose IDE is gone is deleted when
 * the next is written.
 */
object PromptFiles {
    fun write(dir: Path, text: String, pid: Long = ProcessHandle.current().pid()): Path {
        prune(dir)
        dir.createDirectories()
        return dir.resolve("$pid-${UUID.randomUUID()}.md").also { writeAtomically(it, text) }
    }

    fun prune(dir: Path) {
        if (!dir.exists()) return
        dir.listDirectoryEntries("*.md").filterNot { ProcessHandle.of(it.name.substringBefore('-').toLong()).map(ProcessHandle::isAlive).orElse(false) }.forEach { it.deleteIfExists() }
    }
}
