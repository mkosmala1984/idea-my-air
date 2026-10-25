package dev.myair.service

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.ProjectManager
import dev.myair.claude.LiveSession
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.tabs.TabsSync

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
 * Points every open session tab at the session its process shows now, with that session's title, status and [unread]
 * mark, and publishes the tabs to the other IDEs when a session or title changed. Returns the session switches, from to to.
 */
fun retargetOpenTabs(live: Map<String, LiveSession>, titles: Map<String, String>, unread: Set<String>): List<Pair<String, String>> {
    var changed = false
    val switched = ArrayList<Pair<String, String>>()
    for (project in ProjectManager.getInstance().openProjects) {
        val editors = FileEditorManagerEx.getInstanceEx(project)
        for (file in editors.openFiles.filterIsInstance<ClaudeSessionVirtualFile>()) {
            val (sessionId, title) = retarget(file.sessionId, file.title, file.pid, live, titles)
            // By pid rather than session id: another process may run the same session ("Open Anyway").
            val status = live.values.firstOrNull { it.pid == file.pid }?.status
            val isUnread = sessionId in unread
            if (sessionId == file.sessionId && title == file.title && status == file.status && isUnread == file.unread) continue
            if (sessionId != file.sessionId) switched += file.sessionId to sessionId
            // Other IDEs list tabs by session and title only, so a status change alone is not published.
            if (sessionId != file.sessionId || title != file.title) changed = true
            file.sessionId = sessionId
            file.title = title
            file.status = status
            file.unread = isUnread
            editors.updateFilePresentation(file)
        }
    }
    if (changed) TabsSync.getInstance().publish()
    return switched
}
