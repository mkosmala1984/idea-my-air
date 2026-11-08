package dev.myair.planview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import dev.myair.editor.ClaudeSessionVirtualFile

/** The session [file] shows: a session tab's own file, a plan tab's manager (none before it first starts), else null. */
fun sessionFileOf(file: VirtualFile?): ClaudeSessionVirtualFile? = when (file) {
    is ClaudeSessionVirtualFile -> file
    is PlanVirtualFile -> PlanFiles.getInstance().manager(file.folderId)
    else -> null
}

/** The session files the open tabs of [editors] show: session tabs and the managers of plan tabs. */
fun shownSessionFiles(editors: FileEditorManager): List<ClaudeSessionVirtualFile> = editors.openFiles.mapNotNull(::sessionFileOf)

/** The tab that shows [file]: its plan's tab for a manager, which is never opened itself, else the file. */
fun tabFileOf(file: ClaudeSessionVirtualFile): VirtualFile = PlanFiles.getInstance().planOf(file) ?: file
