package dev.myair.canvasview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.canvas.CanvasService
import dev.myair.editor.CANVAS_ICON
import dev.myair.editor.projectBase
import dev.myair.folders.ChangeSource
import dev.myair.folders.SessionFolders
import dev.myair.review.ReviewService
import dev.myair.reviewview.chooseProfile
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.Canvas
import dev.myair.store.PlanManager
import dev.myair.store.Profile
import java.util.UUID

/**
 * The folder a canvas started from [place] goes in (null: the top level): [place] itself, a plain or a plan folder, or,
 * when it is or lies in a review folder of [reviews] or a canvas folder of [canvases], beside the outermost of those,
 * since a canvas lies in neither.
 */
fun canvasParent(structure: SessionFolders, reviews: Set<String>, canvases: Set<String>, place: String?): String? {
    if (place == null) return null
    require(structure.find(place) != null) { "folder $place is gone" }
    val holder = structure.ancestry(place).firstOrNull { it.id in reviews || it.id in canvases } ?: return place
    return structure.ancestry(holder.id).dropLast(1).lastOrNull()?.id
}

/** `Canvas: <name>`, trimmed, a `/` as `-` since folder paths are joined by `/`; a name taken under [parent] gets ` (2)`, ` (3)`... */
fun canvasFolderName(structure: SessionFolders, parent: String?, name: String): String {
    val base = "Canvas: ${name.trim().replace('/', '-')}"
    return (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base ($it)" }).first { !structure.nameTaken(parent, it) }
}

/**
 * Makes the canvas [name] where [canvasParent] puts one started from [place]: `canvas.json` with its manager stored, a
 * new session of [profile] in the parent's start directory ([canvasStartDir]), else in [project]'s, which is also its
 * project; then the folder `Canvas: <name>` with the manager filed in it, in one write that checks the place did not
 * change meanwhile. In that order, as `startReview` does, so a failed structure write leaves an ignored file rather than
 * a folder without a canvas. Then opens the tab, which starts the manager. Reads no git; the files touched are those of
 * the two writes, which run on the EDT as every structure and `review.json` write does. EDT.
 */
fun startCanvas(project: Project, name: String, place: String?, profile: Profile) {
    ThreadingAssertions.assertEventDispatchThread()
    val service = ClaudeSessionsService.getInstance()
    val reviews = ReviewService.getInstance().onDiskIds
    val canvases = CanvasService.getInstance().onDiskIds
    val parent = canvasParent(service.folders, reviews, canvases, place)
    val dir = parent?.let { canvasStartDir(service.folders, it) } ?: projectBase(project)
    val id = UUID.randomUUID().toString()
    val manager = PlanManager(UUID.randomUUID().toString(), dir, dir, profile.name)
    CanvasService.getInstance().create(id, Canvas(manager, 1, emptyList()))
    service.updateFolders(ChangeSource.UI) { structure ->
        check(canvasParent(structure, reviews, canvases, place) == parent) { "the place of the canvas $name changed meanwhile" }
        // Filed here: FolderTabs files a manager only when it makes a new one, and this one is stored already.
        structure.createFolder(parent, id, canvasFolderName(structure, parent, name)).assign(manager.session, id)
    }
    CanvasOpener.open(project, id)
}

/**
 * New Canvas... on the tree's menu: asks for a name and, when profiles besides `default` are listed, the manager's
 * profile, then [startCanvas] from the folder [place] gives (null: the top level). Shown while [visible], enabled while [enabled].
 */
class NewCanvasAction(private val place: () -> String?, private val visible: () -> Boolean, private val enabled: () -> Boolean) :
    DumbAwareAction("New Canvas...", "A canvas folder, where agents show results the user looks at or clicks", CANVAS_ICON) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = visible()
        e.presentation.isEnabled = visible() && enabled()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        // Before the dialogs, which may let the selection change.
        val from = place()
        val name = Messages.showInputDialog(project, "Name:", "New Canvas", null, "", NameValidator)?.trim() ?: return
        val profile = chooseProfile(project, "New Canvas", "Profile the canvas's manager runs in:") ?: return
        startCanvas(project, name, from, profile)
    }
}

private object NameValidator : InputValidatorEx {
    override fun getErrorText(input: String) = if (input.isBlank()) "Name cannot be empty" else null
}
