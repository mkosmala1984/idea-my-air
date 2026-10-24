package dev.myair

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Path

/** An editor tab showing a session, in any project window of this IDE. */
data class SessionTab(val project: Project, val file: VirtualFile)

/** A top-level window of another process, e.g. another IDE, Claude Desktop or a terminal. */
data class HostWindow(val pid: Long, val executable: String, val title: String, val hwnd: HWND)

object SessionHosts {
    /** Air's editor tab file; it exposes the Claude session id through getThreadId() or getSessionId(). */
    private const val AIR_TAB_CLASS = "com.intellij.air.thread.view.AgentThreadViewVirtualFile"

    fun findTab(sessionId: String): SessionTab? = ProjectManager.getInstance().openProjects.firstNotNullOfOrNull { project ->
        FileEditorManager.getInstance(project).openFiles.firstOrNull { sessionId in sessionIds(it).orEmpty() }?.let { SessionTab(project, it) }
    }

    /** Every session tab in this IDE, for the other IDEs to see. */
    fun openTabs(): List<OpenTab> = ProjectManager.getInstance().openProjects.flatMap { project ->
        FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            sessionIds(file)?.let { OpenTab(it, (file as? ClaudeSessionVirtualFile)?.title ?: file.presentableName, project.name) }
        }
    }

    fun focus(tab: SessionTab) {
        ProjectUtil.focusProjectWindow(tab.project, true)
        FileEditorManager.getInstance(tab.project).openFile(tab.file, true)
    }

    /** Null for a file that is not a session tab. */
    private fun sessionIds(file: VirtualFile): List<String>? = when {
        file is ClaudeSessionVirtualFile -> listOf(file.sessionId)
        file.javaClass.name == AIR_TAB_CLASS -> listOfNotNull(airId(file, "getThreadId"), airId(file, "getSessionId")).distinct()
        else -> null
    }

    private fun airId(file: VirtualFile, getter: String) = file.javaClass.getMethod(getter).invoke(file) as String?

    /**
     * Windows lets only the foreground process raise a window. The IDE the user clicked in passes that right to [pid],
     * so the IDE that owns the tab can bring its own frame to the front.
     */
    fun allowForeground(pid: Long) {
        check(Foreground.INSTANCE.AllowSetForegroundWindow(pid.toInt())) { "Windows refused to let pid $pid take the foreground" }
    }

    private interface Foreground : StdCallLibrary {
        fun AllowSetForegroundWindow(dwProcessId: Int): Boolean

        companion object {
            val INSTANCE: Foreground = Native.load("user32", Foreground::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * The window hosting the `claude` process [pid]: its nearest ancestor with a visible titled window. The current IDE
     * counts too, so a caller can tell "elsewhere in this IDE" from "another program". Among several windows of one
     * process (an IDE with several projects) the one titled with the session directory wins.
     */
    fun hostWindow(pid: Long, cwd: Path): HostWindow? {
        var process = ProcessHandle.of(pid).flatMap { it.parent() }.orElse(null)
        while (process != null) {
            val windows = visibleWindows(process.pid())
            if (windows.isNotEmpty()) {
                val (hwnd, title) = windows.firstOrNull { cwd.fileName.toString() in it.second } ?: windows.first()
                val executable = process.info().command().map { Path.of(it).fileName.toString() }.orElse("pid ${process.pid()}")
                return HostWindow(process.pid(), executable, title, hwnd)
            }
            process = process.parent().orElse(null)
        }
        return null
    }

    fun bringToFront(window: HostWindow) {
        // Restore only a minimized window: SW_RESTORE would also un-maximize a maximized one.
        if (User32.INSTANCE.GetWindowLong(window.hwnd, WinUser.GWL_STYLE) and WinUser.WS_MINIMIZE != 0) User32.INSTANCE.ShowWindow(window.hwnd, WinUser.SW_RESTORE)
        check(User32.INSTANCE.SetForegroundWindow(window.hwnd)) { "Windows refused to bring '${window.title}' to the front" }
    }

    private fun visibleWindows(pid: Long): List<Pair<HWND, String>> {
        val found = ArrayList<Pair<HWND, String>>()
        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            val owner = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner)
            if (owner.value.toLong() == pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
                val title = CharArray(512).let { String(it, 0, User32.INSTANCE.GetWindowText(hwnd, it, it.size)) }
                if (title.isNotEmpty()) found += hwnd to title
            }
            true
        }, null)
        return found
    }
}
