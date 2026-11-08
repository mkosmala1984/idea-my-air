package dev.myair.service

import dev.myair.claude.ClaudeSession
import dev.myair.claude.LiveSession
import dev.myair.claude.SessionStatus
import dev.myair.folders.SessionFolders
import dev.myair.toolwindow.foldersTree
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.assertEquals

class UnscannedSessionsTest {
    private fun live(cwd: String, started: Long) = LiveSession(1, "cli", SessionStatus.IDLE, null, Instant.EPOCH, Path.of(cwd), Instant.ofEpochMilli(started))

    private val scanned = listOf(ClaudeSession("old", Path.of("/rtm"), "old", Instant.ofEpochMilli(5), emptyList(), emptyList()))

    @Test
    fun `a running session without a transcript becomes a new session in its cwd from its start, newest first`() {
        val unscanned = unscannedSessions(scanned, mapOf("old" to live("/rtm", 1), "a" to live("/a", 2), "b" to live("/b", 3)))
        assertEquals(
            listOf(
                ClaudeSession("b", Path.of("/b"), NEW_SESSION_TITLE, Instant.ofEpochMilli(3), emptyList(), emptyList()),
                ClaudeSession("a", Path.of("/a"), NEW_SESSION_TITLE, Instant.ofEpochMilli(2), emptyList(), emptyList()),
            ),
            unscanned,
        )
    }

    @Test
    fun `none once every running session has a transcript`() {
        assertEquals(emptyList(), unscannedSessions(scanned, mapOf("old" to live("/rtm", 1))))
    }

    @Test
    fun `a new session filed on start shows in its folder before its transcript exists`() {
        val folders = SessionFolders().createFolder(null, "tm", "tenant-migration").createFolder("tm", "v", "verifier").assign("a", "v")
        val sessions = withUnscanned(scanned, unscannedSessions(scanned, mapOf("a" to live("/rtm", 9))))
        assertEquals(listOf("a", "old"), sessions.map { it.id })
        val verifier = foldersTree(folders, sessions, { null }, { emptyList() }, null).getChildAt(0).getChildAt(0) as DefaultMutableTreeNode
        assertEquals("v", verifier.userObject.toString())
        assertEquals("a", ((verifier.getChildAt(0) as DefaultMutableTreeNode).userObject as ClaudeSession).id)
    }
}
