package dev.myair.reviewview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import dev.myair.editor.SessionTerminals
import dev.myair.planview.ManagerTabEditor
import dev.myair.review.ReviewService
import javax.swing.ScrollPaneConstants

class ReviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ReviewVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewEditor(project, file as ReviewVirtualFile)
    override fun getEditorTypeId() = "myair-review"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * A review tab: the review ([ReviewPanel]) beside the terminal of its manager session ([ManagerTabEditor]). A review
 * folder gone shows a notice and ends the manager. A `review.json` that cannot be read before the review first showed
 * shows why, starting no manager, until it can; once the review shows, the panel shows the error instead of the review,
 * the manager left running.
 */
class ReviewEditor(project: Project, private val file: ReviewVirtualFile) : ManagerTabEditor(project, file, "Review", "dev.myair.reviewView.split") {
    private val reviews = ReviewService.getInstance()
    private var panel: ReviewPanel? = null
    override val goneNotice = "This review no longer exists."

    init {
        service.subscribe(this, ::update)
        reviews.subscribe(this, ::update)
        reviews.watchGit(file.folderId, this)
        update()
    }

    override fun nameNow() = service.folders.find(file.folderId)?.name?.takeIf { file.folderId in reviews.reviews }

    override fun readError() = reviews.reviews.getValue(file.folderId).exceptionOrNull()?.let { "Cannot read the review: $it" }

    override fun content() = JBScrollPane(
        ReviewPanel(project, file.folderId, this, { pane }, { manager?.let(SessionTerminals.getInstance()::runs) == true }).also { panel = it },
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    )

    override fun render() = checkNotNull(panel).show(reviews.reviews.getValue(file.folderId))

    override fun startManager() = ReviewTabs.getInstance().startManager(file.folderId)

    override fun forgetManager() {
        ReviewTabs.getInstance().tabs.forgetManager(file.folderId)
    }
}
