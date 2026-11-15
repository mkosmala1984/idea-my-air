package dev.myair.search

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.myair.claude.ClaudeSessionScanner
import dev.myair.claude.queryWords
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.createDirectories

/**
 * Full-text index of what the human and the agent said, one row per message, in an SQLite FTS5 table [file] shared by every IDE
 * running this plugin. Transcripts are append-only, so an update reads only the complete lines after the offset stored for
 * each file; a file shorter than that offset has been rewritten and is read again. Tool calls, tool results and thinking are
 * not indexed: they are most of a transcript and hold no conversation.
 *
 * The trigram tokenizer finds any fragment of three or more characters (identifiers, paths) ignoring case and accents. It
 * does not fold `ł`, and a word shorter than three characters matches nothing.
 */
class SessionIndex(private val file: Path) {
    init {
        file.parent.createDirectories()
        transaction { c ->
            // Another extraction of text than the one that wrote the rows makes them wrong: start over, as the scanner's cache does.
            if (c.scalar("PRAGMA user_version") != FORMAT) {
                c.execute("DROP TABLE IF EXISTS msg")
                c.execute("DROP TABLE IF EXISTS progress")
                c.execute("CREATE TABLE progress(file TEXT PRIMARY KEY, offset INTEGER NOT NULL)")
                c.execute("CREATE VIRTUAL TABLE msg USING fts5(text, file UNINDEXED, tokenize='trigram remove_diacritics 1')")
                c.execute("PRAGMA user_version = $FORMAT")
            }
        }
    }

    /**
     * Brings the index to the end of every transcript of [files] and forgets the files not among them; returns whether
     * anything changed.
     */
    fun update(files: Collection<Path>): Boolean {
        val wanted = files.associateBy { it.toString() }
        val done = connect { it.progress() }
        // Looked at without a write lock, so an update with nothing to do never blocks another IDE's.
        val todo = (done.keys - wanted.keys) + wanted.filter { (path, transcript) -> done[path] != Files.size(transcript) }.keys
        var changed = false
        for (path in todo) transaction { c -> changed = bring(c, path, wanted[path]) || changed }
        return changed
    }

    /**
     * The transcripts holding a message that contains every word of [query] (any fragment, ignoring case and accents), each
     * with the best matching message's text around the match, best match first. Nothing when a word is shorter than three
     * characters, which the tokenizer cannot match.
     */
    fun search(query: String): Map<Path, String> {
        val words = queryWords(query)
        require(words.isNotEmpty()) { "empty query" }
        if (words.any { it.codePointCount(0, it.length) < 3 }) return emptyMap()
        val match = words.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }
        val hits = LinkedHashMap<Path, String>()
        connect { c ->
            c.prepareStatement("SELECT file, snippet(msg, 0, '', '', '…', 64) FROM msg WHERE msg MATCH ? ORDER BY rank").use { statement ->
                statement.setString(1, match)
                statement.executeQuery().use { rows -> while (rows.next()) hits.putIfAbsent(Path.of(rows.getString(1)), rows.getString(2).replace(WHITESPACE, " ").trim()) }
            }
        }
        return hits
    }

    /**
     * Brings [path] to the end of [transcript], or forgets it when [transcript] is null; returns whether rows changed. The
     * offset is read again here, inside the transaction: another IDE may have indexed the file since [update] looked.
     */
    private fun bring(c: Connection, path: String, transcript: Path?): Boolean {
        val done = c.offsetOf(path)
        if (transcript == null) {
            c.forget(path)
            return true
        }
        val size = Files.size(transcript)
        if (done == size) return false
        // Shorter than what was read: the file was rewritten.
        val rewritten = done != null && size < done
        if (rewritten) c.forget(path)
        var rows = 0
        val end = c.prepareStatement("INSERT INTO msg(text, file) VALUES (?, ?)").use { insert ->
            ClaudeSessionScanner.forEachCompleteLine(transcript, if (rewritten || done == null) 0L else done) { line ->
                for (text in texts(line)) {
                    insert.setString(1, text)
                    insert.setString(2, path)
                    insert.addBatch()
                    rows++
                }
            }.also { insert.executeBatch() }
        }
        c.prepareStatement("INSERT INTO progress(file, offset) VALUES (?, ?) ON CONFLICT(file) DO UPDATE SET offset = excluded.offset").use {
            it.setString(1, path)
            it.setLong(2, end)
            it.executeUpdate()
        }
        return rows > 0 || rewritten
    }

    // Not DriverManager: it finds drivers through the system class loader, which does not see this plugin's jar.
    private fun <T> connect(block: (Connection) -> T): T = JDBC.createConnection("jdbc:sqlite:$file", CONFIG).use(block)

    /** One write transaction that takes the lock at its start, so IDEs updating at once queue up instead of failing. */
    private fun <T> transaction(block: (Connection) -> T): T = connect { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    companion object {
        /** Bumped whenever [texts] or the table changes. */
        private const val FORMAT = 1
        private val WHITESPACE = Regex("\\s+")

        private val CONFIG = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            // Another IDE may be writing; its transaction is a single file's worth of lines.
            setBusyTimeout(60_000)
        }.toProperties()

        private const val USER = "\"type\":\"user\""
        private const val ASSISTANT = "\"type\":\"assistant\""
        private const val SYNTHETIC = "<synthetic>"

        /**
         * The messages a transcript [line] holds, as the conversation says them: what the human typed (or the skill they ran)
         * and the text blocks of the agent's reply, outside sidechains and `isMeta` lines, and not `claude`'s own error replies.
         */
        internal fun texts(line: String): List<String> {
            if (USER !in line && ASSISTANT !in line) return emptyList()
            val o = JsonParser.parseString(line).asJsonObject
            if (o["isSidechain"]?.asBoolean == true || o["isMeta"]?.asBoolean == true) return emptyList()
            return when (o["type"]?.asString) {
                "user" -> listOfNotNull(ClaudeSessionScanner.promptText(o))
                "assistant" -> assistantTexts(o)
                else -> emptyList()
            }
        }

        private fun assistantTexts(o: JsonObject): List<String> {
            val message = o.getAsJsonObject("message")
            if (message["model"]?.asString == SYNTHETIC) return emptyList()
            return message.getAsJsonArray("content").map { it.asJsonObject }
                .filter { it["type"].asString == "text" }.map { it["text"].asString }.filter { it.isNotBlank() }
        }

        private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

        private fun Connection.scalar(sql: String): Int = createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }

        private fun Connection.progress(): Map<String, Long> = createStatement().use { s ->
            s.executeQuery("SELECT file, offset FROM progress").use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) } }
        }

        private fun Connection.offsetOf(path: String): Long? = prepareStatement("SELECT offset FROM progress WHERE file = ?").use { s ->
            s.setString(1, path)
            s.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

        private fun Connection.forget(path: String) {
            prepareStatement("DELETE FROM msg WHERE file = ?").use { it.setString(1, path); it.executeUpdate() }
            prepareStatement("DELETE FROM progress WHERE file = ?").use { it.setString(1, path); it.executeUpdate() }
        }
    }
}
