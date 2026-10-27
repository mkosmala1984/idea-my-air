package dev.myair.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.myair.service.ClaudeSessionsService
import dev.myair.service.NEW_SESSION_TITLE
import java.nio.file.Path

/**
 * Gives session tabs a URL, `myair-session://<sessionTabPath>`, which the platform saves with the open tabs and resolves
 * on startup, so the tabs come back after a restart in their order, split and pin state, as Air's thread tabs do.
 */
class ClaudeSessionFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    private val claudeHome = Path.of(System.getProperty("user.home"), ".claude")

    override fun getProtocol() = PROTOCOL

    /** The open tab of that session if there is one, so a second tab never runs a second process; else a tab to restore. */
    override fun findFileByPath(path: String): VirtualFile {
        val ref = parseSessionTabPath(path)
        ProjectManager.getInstance().openProjects.asSequence()
            .flatMap { FileEditorManager.getInstance(it).openFiles.asSequence() }
            .filterIsInstance<ClaudeSessionVirtualFile>()
            .firstOrNull { it.sessionId == ref.sessionId }
            ?.let { return it }
        ClaudeSessionsService.getInstance() // starts tracking which session each tab shows
        return ClaudeSessionVirtualFile(ref.sessionId, ref.cwd, ref.project, NEW_SESSION_TITLE, restartArgs(ref.sessionId, claudeHome), restored = true)
    }

    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    companion object {
        const val PROTOCOL = "myair-session"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as ClaudeSessionFileSystem
    }
}
