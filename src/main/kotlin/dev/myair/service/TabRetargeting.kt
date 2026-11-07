package dev.myair.service

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.ProjectManager
import dev.myair.claude.LiveSession
import dev.myair.actions.Git
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.SessionTerminals
import dev.myair.editor.TabMark
import dev.myair.editor.projectBase
import dev.myair.editor.tabMark
import dev.myair.tabs.TabsSync
import java.nio.file.Path

const val NEW_SESSION_TITLE = "New session"

/**
 * The session and title a tab running process [pid] shows now. `/clear` and `/resume` keep the process but switch its
 * session, which the registry reports under the same pid. A process not (or no longer) registered keeps its session.
 */
fun retarget(sessionId: String, title: String, pid: Long?, live: Map<String, LiveSession>, titles: Map<String, String>): Pair<String, String> {
    val current = live.entries.firstOrNull { it.value.pid == pid }?.key ?: sessionId
    return current to (titles[current] ?: if (current == sessionId) title else NEW_SESSION_TITLE)
}

/**
 * Points every open session tab, and every detached session ([SessionTerminals.detached]), at the session its process shows now, with that session's title, status, [unread] and
 * [TabMark] (from where it works, [workDirs], else where the tab started, and the work trees [heads] holds), and publishes
 * the tabs to the other IDEs when a session or title changed. Returns the session switches, from to to.
 */
fun retargetOpenTabs(
    live: Map<String, LiveSession>, titles: Map<String, String>, unread: Set<String>, workDirs: Map<String, Path>, heads: Map<Path, Git.Head?>,
): List<Pair<String, String>> {
    var changed = false
    val switched = ArrayList<Pair<String, String>>()
    for (project in ProjectManager.getInstance().openProjects) {
        val editors = FileEditorManagerEx.getInstanceEx(project)
        val base = projectBase(project)
        val open = editors.openFiles.filterIsInstance<ClaudeSessionVirtualFile>()
        for (file in open + SessionTerminals.getInstance().detached().filter { it.first == project }.map { it.second }) {
            val (sessionId, title) = retarget(file.sessionId, file.title, file.pid, live, titles)
            // By pid rather than session id: another process may run the same session ("Open Anyway").
            val status = live.values.firstOrNull { it.pid == file.pid }?.status
            val isUnread = sessionId in unread
            val mark = tabMark(file.project, heads[workDirs[sessionId] ?: file.cwd], heads[file.project], base, heads[base])
            if (sessionId == file.sessionId && title == file.title && status == file.status && isUnread == file.unread && mark == file.mark) continue
            if (sessionId != file.sessionId) switched += file.sessionId to sessionId
            // Other IDEs list tabs by session and title only, so a status change alone is not published.
            if (sessionId != file.sessionId || title != file.title) changed = true
            file.sessionId = sessionId
            file.title = title
            file.status = status
            file.unread = isUnread
            file.mark = mark
            if (file in open) editors.updateFilePresentation(file)
        }
    }
    if (changed) TabsSync.getInstance().publish()
    return switched
}
