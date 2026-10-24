package dev.myair

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabStoreTest {
    @TempDir
    lateinit var root: Path

    private val tab = OpenTab(listOf("s1"), "Title", "my-air")

    @Test
    fun `an IDE sees what another IDE published, and its own tabs`() {
        val self = TabStore(root)
        self.publish(listOf(tab))
        val pid = ProcessHandle.current().pid()
        assertEquals(listOf(IdeTabs(pid, TabStore.startMillis(ProcessHandle.current())!!, listOf(tab))), TabStore(root).all())
    }

    @Test
    fun `a list left by a dead IDE is ignored and removed`() {
        val dead = root.resolve("ides/999999.json")
        TabStore(root).publish(listOf(tab))
        dead.writeText("""{"pid":999999,"started":1,"tabs":[]}""")
        val all = TabStore(root) { pid, _ -> pid != 999999L }.all()
        assertEquals(listOf(ProcessHandle.current().pid()), all.map { it.pid })
        assertFalse(dead.exists())
    }

    @Test
    fun `a withdrawn IDE is no longer listed`() {
        val store = TabStore(root)
        store.publish(listOf(tab))
        store.withdraw()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `focus requests reach the addressed IDE, waiting ones included, and are consumed`() {
        val store = TabStore(root)
        val pid = ProcessHandle.current().pid()
        store.requestFocus(pid, "before-start")
        val received = LinkedBlockingQueue<String>()
        store.receiveRequests { received.put(it) }.use {
            assertEquals("before-start", received.poll(5, TimeUnit.SECONDS))
            store.requestFocus(pid, "s1")
            assertEquals("s1", received.poll(5, TimeUnit.SECONDS))
            store.requestFocus(pid + 1, "for another IDE")
            assertNull(received.poll(1500, TimeUnit.MILLISECONDS))
        }
        assertTrue(root.resolve("requests/$pid").listDirectoryEntries().isEmpty())
    }
}
