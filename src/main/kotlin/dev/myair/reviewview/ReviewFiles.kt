package dev.myair.reviewview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.actions.Git
import dev.myair.editor.ClaudeSessionVirtualFile
import dev.myair.editor.hasTranscript
import dev.myair.planview.FolderTabFile
import dev.myair.planview.FolderTabFileSystem
import dev.myair.planview.FolderTabs
import dev.myair.planview.managerStart
import dev.myair.review.ReviewService
import dev.myair.review.reviewManagerPrompt
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.BranchReview
import dev.myair.store.Homes
import dev.myair.store.PlanManager
import java.nio.file.Path
import java.util.UUID

/** The tab of the review of folder [folderId], `myair-review://<folderId>`. */
class ReviewVirtualFile(folderId: String) : FolderTabFile(folderId, "Review") {
    override fun getFileSystem() = ReviewFileSystem.instance
}

class ReviewFileSystem : FolderTabFileSystem(PROTOCOL, { ReviewTabs.getInstance().tabs.file(it) }) {
    companion object {
        const val PROTOCOL = "myair-review"

        val instance get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as ReviewFileSystem
    }
}

/**
 * How [review]'s manager starts ([managerStart]): a new one in the review's work tree, its project the main checkout
 * ([mainCheckout] of the work tree, asked only then); a stored one where it was stored.
 */
fun reviewManagerStart(review: BranchReview, newId: String, mainCheckout: (Path) -> Path, hasTranscript: (String) -> Boolean): Pair<PlanManager, List<String>> =
    managerStart(review.manager, newId, review.dir, review.manager?.project ?: mainCheckout(review.dir), hasTranscript)

/** The tab file of each review folder and the file of its manager session ([FolderTabs]); the manager is stored in `review.json`. */
@Service(Service.Level.APP)
class ReviewTabs {
    val tabs = FolderTabs(::ReviewVirtualFile)

    /**
     * The manager session's file of the review of [folderId], kept while `SessionTerminals` has its process, else built
     * again from the review's `manager` (a new session in the review's work tree when there is none, stored in `review.json`
     * and filed in the review folder).
     */
    fun startManager(folderId: String): ClaudeSessionVirtualFile {
        ThreadingAssertions.assertEventDispatchThread()
        tabs.running(folderId)?.let { return it }
        val newId = UUID.randomUUID().toString()
        lateinit var args: List<String>
        val manager = checkNotNull(ReviewService.getInstance().update(folderId) { review ->
            val (manager, start) = reviewManagerStart(review, newId, Git::mainCheckout) { hasTranscript(it, Homes.claude) }
            args = start
            review.copy(manager = manager)
        }.manager)
        return tabs.managerFile(folderId, manager, newId, args)
    }

    /** The review whose manager [file] is; null for any other session's file. */
    fun reviewOf(file: ClaudeSessionVirtualFile): ReviewVirtualFile? = tabs.tabOf(file)

    /** The system prompt of [file] when it is a review's manager, written to a file at every start of its process; else null. */
    fun managerPrompt(file: ClaudeSessionVirtualFile): String? = reviewOf(file)?.folderId?.let {
        reviewManagerPrompt(ClaudeSessionsService.getInstance().folders.pathOf(it), ReviewService.getInstance().review(it))
    }

    companion object {
        fun getInstance(): ReviewTabs = service()
    }
}
