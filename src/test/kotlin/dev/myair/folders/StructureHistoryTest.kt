package dev.myair.folders

import com.google.gson.JsonParser
import dev.myair.toJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureHistoryTest {
    @TempDir
    lateinit var root: Path

    /** A clock one millisecond further on every read, so snapshot names never collide. */
    private val clock = object : Clock() {
        private var millis = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis++)
    }

    private fun store(keep: Int = 20) = StructureStore(root.resolve("structure.json"), StructureHistory(root.resolve("history"), keep, clock))

    @Test
    fun `each changing write first snapshots the replaced file, tagged with its source, and an unchanged one does not`() {
        val store = store()
        store.update(ChangeSource.UI) { it.createFolder(null, "a", "a") }
        assertEquals(emptyList(), store.history.list())
        val before = store.file.readText()
        store.update(ChangeSource.MCP) { it.createFolder(null, "b", "b") }
        store.update(ChangeSource.AUTO) { it }
        val snapshots = store.history.list()
        assertEquals(listOf(ChangeSource.MCP), snapshots.map { it.source })
        assertEquals("structure-20261024-120000-000-mcp.json", snapshots.single().name)
        assertEquals(before, store.history.read(snapshots.single().name))
    }

    @Test
    fun `only the newest snapshots are kept, newest first`() {
        val store = store(keep = 3)
        (1..6).forEach { n -> store.update(ChangeSource.UI) { it.createFolder(null, "f$n", "f$n") } }
        val names = store.history.list().map { it.name }
        assertEquals(3, names.size)
        assertEquals(names.sortedDescending(), names)
        assertEquals(3, root.resolve("history").listDirectoryEntries().size)
        assertEquals(listOf("f1", "f2", "f3", "f4", "f5"), SessionFolders.parse(store.history.read(names[0])).folders.map { it.name })
    }

    @Test
    fun `a restore goes through update, so the replaced version is snapshotted too`() {
        val store = store()
        store.update(ChangeSource.UI) { it.createFolder(null, "a", "a") }
        store.update(ChangeSource.MCP) { it.delete("a") }
        val beforeDelete = SessionFolders.parse(store.history.read(store.history.list().first().name))
        store.update(ChangeSource.RESTORE) { beforeDelete }
        assertEquals(listOf("a"), store.read().folders.map { it.name })
        assertEquals(listOf(ChangeSource.RESTORE, ChangeSource.MCP), store.history.list().map { it.source })
    }

    @Test
    fun `only listed snapshots can be read, and a broken one shows its error in the list`() {
        val store = store()
        store.update(ChangeSource.UI) { it.createFolder(null, "a", "a") }
        store.update(ChangeSource.UI) { it.createFolder(null, "b", "b") }
        assertThrows<IllegalArgumentException> { store.history.read("../structure.json") }
        root.resolve("history/structure-20261024-130000-000-ui.json").writeText("broken")
        val listed = JsonParser.parseString(store.history.toJson()).asJsonArray.map { it.asJsonObject }
        assertTrue("error" in listed[0].keySet())
        assertEquals(1, listed[1]["folders"].asInt)
    }

    @Test
    fun `a stranger file in the history fails loudly`() {
        store().update(ChangeSource.UI) { it.createFolder(null, "a", "a") }
        root.resolve("history").toFile().mkdirs()
        root.resolve("history/structure-copy.json").writeText("{}")
        assertThrows<IllegalStateException> { store().history.list() }
    }
}
