package dev.myair.editor

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.NEW_SESSION_TITLE
import dev.myair.store.Profile
import dev.myair.store.ProfileStore
import java.nio.file.Path

/**
 * Gives session tabs a URL, `myair-session://<sessionTabPath>`, which the platform saves with the open tabs and resolves
 * on startup, so the tabs come back after a restart in their order, split and pin state, as Air's thread tabs do.
 */
class ClaudeSessionFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol() = PROTOCOL

    /**
     * Always a new tab to restore, even when the session is open in another tab: an editor per tab runs its own process,
     * so sharing the file would not prevent a second one, while the restored tab's check against the registry does.
     */
    override fun findFileByPath(path: String): VirtualFile {
        val ref = parseSessionTabPath(path)
        ClaudeSessionsService.getInstance() // starts tracking which session each tab shows
        val profile = ProfileStore.getInstance().find(ref.profile)
            // Removed since the tab was saved: its directory is unknown, and the pane says so instead of starting ([SessionPane]).
            ?: return ClaudeSessionVirtualFile(ref.sessionId, ref.cwd, ref.project, Profile(ref.profile, Path.of("")), NEW_SESSION_TITLE, emptyList(), restored = true)
        return ClaudeSessionVirtualFile(ref.sessionId, ref.cwd, ref.project, profile, NEW_SESSION_TITLE, restartArgs(ref.sessionId, profile.dir), restored = true)
    }

    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    companion object {
        const val PROTOCOL = "myair-session"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as ClaudeSessionFileSystem
    }
}
