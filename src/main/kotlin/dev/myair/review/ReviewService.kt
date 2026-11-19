package dev.myair.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import dev.myair.actions.Git
import dev.myair.actions.GitState
import dev.myair.claude.ClaudeHomeWatcher
import dev.myair.claude.LiveSession
import dev.myair.folders.ChangeSource
import dev.myair.folders.Plan
import dev.myair.folders.PlanChanges
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.BranchReview
import dev.myair.store.Homes
import dev.myair.store.ReviewFiles
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

/**
 * Git facts of a review's `dir`: its [state], [head], the commits HEAD is past the reviewed commit ([sinceReviewed], null
 * without one or when git does not know it), and [changed]: by each finding's newest report commit, the old-side line
 * ranges changed since it ([Git.changedLines], null for a commit git does not know). [error]: why the read failed, the
 * other fields then empty.
 */
data class ReviewGit(
    val state: GitState?, val head: String?, val sinceReviewed: Int?, val changed: Map<String, Map<String, List<IntRange>>?>, val error: String?,
)

/** Job id to the session its live entry runs. */
fun jobSessions(live: Map<String, LiveSession>): Map<String, String> = live.entries.mapNotNull { (session, entry) -> entry.job?.let { it to session } }.toMap()

/** Gives every run with a job and no session the session of its job's entry in [jobs]; returns the sessions learned too. */
fun learnSessions(review: BranchReview, jobs: Map<String, String>): Pair<BranchReview, List<String>> =
    review.runs.filter { it.session == null && it.job in jobs }.fold(review to emptyList()) { (r, learned), run ->
        val session = jobs.getValue(checkNotNull(run.job))
        r.setSession(run.id, session) to learned + session
    }

/** The reviews whose folder is in [structure]; the others are kept on disk, never deleted, so a restored folder finds its review. */
fun inStructure(all: Map<String, Result<BranchReview>>, structure: SessionFolders): Map<String, Result<BranchReview>> =
    all.filterKeys { structure.find(it) != null }

/** The plan [review] is linked to, while its folder still has one. */
fun linkedPlan(review: BranchReview, structure: SessionFolders): Plan? = review.plan?.let(structure::find)?.plan

/** The plan changes [review] still needs in [structure]; null without a linked plan or when it already follows. */
fun mirrorDue(review: BranchReview, structure: SessionFolders, liveJobs: Set<String>): Mirror? =
    linkedPlan(review, structure)?.let { mirrorToPlan(review, it, liveJobs) }

/** Reads [review]'s git facts; any failure goes into [ReviewGit.error]. */
fun readGit(review: BranchReview): ReviewGit = try {
    check(review.dir.isDirectory()) { "${review.dir} does not exist" }
    val state = checkNotNull(Git.state(review.dir)) { "${review.dir} is not in a git repository" }
    val head = Git.run(state.root, "rev-parse", "HEAD").trim()
    val commits = review.findings.map { it.reports.last().commit }.toSet()
    ReviewGit(state, head, reviewedCommit(review)?.let { Git.commitsBetween(state.root, it) }, commits.associateWith { Git.changedLines(state.root, it) }, null)
} catch (e: Exception) {
    ReviewGit(null, null, null, emptyMap(), "${e.javaClass.simpleName}: ${e.message}")
}

/**
 * Every review of `~/.my-air/reviews`, read and written only here, so the review tabs and the MCP tools see one state.
 * [reviews], [facts] and the listeners are touched on the EDT only. Reviews and their git facts are read off the EDT on
 * every [ClaudeSessionsService] change and on file events under the reviews directory; only the latest read lands.
 */
@Service(Service.Level.APP)
class ReviewService : Disposable {
    private val files = ReviewFiles(Homes.myAir.resolve("reviews"))
    private val sessions = ClaudeSessionsService.getInstance()
    private var all: Map<String, Result<BranchReview>> = emptyMap()

    /** By folder id, only folders in the structure; a `review.json` that cannot be read is a failure. */
    val reviews get() = inStructure(all, sessions.folders)

    /** By folder id, of the reviews read without failure. */
    var facts: Map<String, ReviewGit> = emptyMap()
        private set

    private val listeners = ArrayList<() -> Unit>()

    /** One read at a time; [requested] counts the reads asked for, so an older one is dropped. Changed on the EDT only. */
    private val reader = AppExecutorUtil.createBoundedApplicationPoolExecutor("My Air reviews refresh", 1)
    @Volatile private var requested = 0

    private val watcher: ClaudeHomeWatcher

    init {
        ThreadingAssertions.assertEventDispatchThread()
        files.root.createDirectories()
        // At once, as structure.json is read on the EDT, so the first tool call after a start finds the reviews; git follows.
        all = files.readAll()
        watcher = ClaudeHomeWatcher(emptyList(), files.root) { refresh() }
        sessions.subscribe(this) {
            // Later, not inside the notification: change() itself writes the folders, which notifies again.
            ApplicationManager.getApplication().invokeLater(::sync)
            refresh()
        }
        refresh()
    }

    /** The review of [folderId]; throws when it has none or it cannot be read. */
    fun review(folderId: String): BranchReview = requireNotNull(reviews[folderId]) { "folder $folderId has no review" }.getOrThrow()

    /** Calls [listener] on the EDT after every change, until [parent] is disposed. */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Applies [op] to the review of [folderId] as it is on disk now, writes it, mirrors it into its linked plan and
     * notifies; returns the review as stored (with the mirror's blocker ids).
     */
    fun update(folderId: String, op: (BranchReview) -> BranchReview): BranchReview {
        require(folderId in reviews) { "folder $folderId has no review" }
        val stored = change(folderId) { op(it) to emptyList() }
        refresh()
        return stored
    }

    /** Reads every review and its git facts off the EDT; callable from any thread. */
    fun refresh() = ApplicationManager.getApplication().invokeLater {
        val generation = ++requested
        val ids = sessions.folders.all().map { it.id }.toSet()
        reader.execute {
            if (generation != requested) return@execute
            // Nobody reads the Future, so an exception would vanish with it; log it as an IDE error instead.
            val (read, git) = try {
                val read = files.readAll()
                read to read.filterKeys { it in ids }.mapNotNull { (id, r) -> r.getOrNull()?.let { id to readGit(it) } }.toMap()
            } catch (e: Throwable) {
                thisLogger().error("Review refresh failed", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != requested) return@invokeLater
                all = read
                facts = git
                sync()
                changed()
            }
        }
    }

    /**
     * Gives reviewer runs their sessions from the registry, filing those in the review folder, and mirrors reviews into
     * their plans where the plan does not follow yet (a run's job ending changes its status without a write).
     */
    private fun sync() {
        val jobs = jobSessions(sessions.live)
        val due = reviews.filter { (_, result) ->
            val review = result.getOrNull() ?: return@filter false
            review.runs.any { it.session == null && it.job in jobs } || mirrorDue(review, sessions.folders, jobs.keys) != null
        }
        due.keys.forEach { id -> change(id) { learnSessions(it, jobs) } }
    }

    /**
     * Applies [op] to the review on disk; when the review changed, sessions were learned or its plan does not follow,
     * files the sessions in the review folder and applies the mirror (computed against the plan on disk, so the blocker
     * ids it predicts are the ones given) in one folders write, then writes the review with the mirror's blocker ids.
     */
    private fun change(folderId: String, op: (BranchReview) -> Pair<BranchReview, List<String>>): BranchReview {
        ThreadingAssertions.assertEventDispatchThread()
        val before = files.read(folderId)
        val (review, filed) = op(before)
        val liveJobs = jobSessions(sessions.live).keys
        val foldersDue = filed.isNotEmpty() || mirrorDue(review, sessions.folders, liveJobs) != null
        if (review == before && !foldersDue) return before
        var stored = review
        if (foldersDue) {
            sessions.updateFolders(ChangeSource.AUTO) { structure ->
                val assigned = filed.fold(structure) { s, session -> s.assign(session, folderId) }
                val mirror = mirrorDue(review, assigned, liveJobs) ?: return@updateFolders assigned
                stored = review.copy(blockers = mirror.blockers)
                PlanChanges.apply(assigned, checkNotNull(review.plan), mirror.changes, LocalDate.now()).also { after ->
                    // A mirror that does not converge would rewrite the plan on every notification.
                    check(mirrorDue(stored, after, liveJobs) == null) { "the mirror of review $folderId does not converge: ${mirror.changes}" }
                }
            }
        }
        if (stored != before) files.write(folderId, stored)
        // Drops a read started before this write, which would land the review as it was.
        requested++
        all = all + (folderId to Result.success(stored))
        changed()
        return stored
    }

    // A copy: a listener may unsubscribe while being called.
    private fun changed() = listeners.toList().forEach { it() }

    override fun dispose() {
        watcher.close()
    }

    companion object {
        fun getInstance(): ReviewService = ApplicationManager.getApplication().getService(ReviewService::class.java)
    }
}

/** Creates the service with the first project, so reviewer sessions are learned and plans mirrored before any review is opened. */
class ReviewServiceStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { ReviewService.getInstance() }
    }
}
