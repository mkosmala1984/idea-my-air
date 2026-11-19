package dev.myair.reviewview

import com.intellij.openapi.project.Project
import dev.myair.review.ReviewService
import dev.myair.tabs.TabsSync

object ReviewOpener {
    /**
     * Focuses the review's tab where its manager already shows (another IDE), else opens it in [project]. A review that
     * cannot be read names no manager; its tab opens here and says why.
     */
    fun open(project: Project, folderId: String) {
        val manager = requireNotNull(ReviewService.getInstance().reviews[folderId]) { "folder $folderId has no review" }.getOrNull()?.manager?.session
        if (manager != null && TabsSync.getInstance().focusElsewhere(manager)) return
        ReviewTabs.getInstance().tabs.openHere<ReviewEditor>(project, folderId)
    }
}
