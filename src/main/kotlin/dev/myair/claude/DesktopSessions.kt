package dev.myair.claude

import com.google.gson.stream.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Reads Claude Desktop's `<dataDir>/<profile>/claude-code-sessions/<account>/<org>/local_<id>.json` into the folder the
 * user started each session in (`originCwd`), while `claude` may run in a worktree of it, maybe removed since. Keyed by
 * the `claude` session id (`cliSessionId`); a Desktop session without one never started `claude` and has no transcript.
 * The files carry large prompt and tool snapshots and are rewritten in place, so each is parsed again only when its mtime changes.
 */
class DesktopSessionReader(private val dataDir: Path) {
    private val cache = HashMap<Path, Pair<FileTime, Pair<String, Path>?>>()

    @Synchronized
    fun read(): Map<String, Path> {
        // No data directory: Claude Desktop is not installed.
        val files = if (!dataDir.isDirectory()) emptyList() else dataDir.subdirectories()
            .map { it.resolve("claude-code-sessions") }.filter { it.isDirectory() }
            .flatMap { it.subdirectories() }.flatMap { it.subdirectories() }.flatMap { it.listDirectoryEntries("local_*.json") }
        cache.keys.retainAll(files.toSet())
        val sessions = files.mapNotNull { file ->
            val modified = Files.getLastModifiedTime(file)
            val known = cache[file]
            if (known != null && known.first == modified) known.second else parse(file).also { cache[file] = modified to it }
        }
        return sessions.toMap().also { check(it.size == sessions.size) { "one claude session in several Desktop sessions: $sessions" } }
    }

    private fun Path.subdirectories() = listDirectoryEntries().filter { it.isDirectory() }

    companion object {
        /** Reads only the top-level fields it needs, skipping the snapshots without building them. */
        fun parse(file: Path): Pair<String, Path>? {
            var id: String? = null
            var origin: String? = null
            JsonReader(Files.newBufferedReader(file)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "cliSessionId" -> id = reader.nextString()
                    "originCwd" -> origin = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            val project = Path.of(checkNotNull(origin) { "Desktop session without originCwd: $file" })
            return id?.let { it to project }
        }
    }
}
