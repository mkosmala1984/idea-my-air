package dev.myair.reviewview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import dev.myair.planview.ManagerTabEditor
import dev.myair.review.ReviewService
import java.awt.GridBagLayout
import javax.swing.JPanel

class ReviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ReviewVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewEditor(project, file as ReviewVirtualFile)
    override fun getEditorTypeId() = "myair-review"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * A review tab: the review beside the terminal of its manager session ([ManagerTabEditor]). A review folder gone shows a
 * notice and ends the manager; a `review.json` that cannot be read shows why until it can, the manager left running.
 */
class ReviewEditor(project: Project, private val file: ReviewVirtualFile) : ManagerTabEditor(project, file, "Review", "dev.myair.reviewView.split") {
    private val reviews = ReviewService.getInstance()
    /** The review panel's slot: a label naming the branch until the panel fills it. */
    val panel = JBLabel()
    override val goneNotice = "This review no longer exists."

    init {
        service.subscribe(this, ::update)
        reviews.subscribe(this, ::update)
        update()
    }

    override fun nameNow() = service.folders.find(file.folderId)?.name?.takeIf { file.folderId in reviews.reviews }

    override fun readError() = reviews.reviews.getValue(file.folderId).exceptionOrNull()?.let { "Cannot read the review: $it" }

    override fun content() = JPanel(GridBagLayout()).apply { add(panel) }

    override fun render() {
        val review = reviews.review(file.folderId)
        panel.text = "Review of ${review.branch} against ${review.base}"
    }

    override fun startManager() = ReviewTabs.getInstance().startManager(file.folderId)

    override fun forgetManager() = ReviewTabs.getInstance().forgetManager(file.folderId)
}
