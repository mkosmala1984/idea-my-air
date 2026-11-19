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
import dev.myair.claude.SessionStatus
import dev.myair.folders.ChangeSource
import dev.myair.folders.Plan
import dev.myair.folders.PlanChanges
import dev.myair.folders.SessionFolders
import dev.myair.service.ClaudeSessionsService
import dev.myair.store.BranchReview
import dev.myair.store.Homes
import dev.myair.store.ReviewFiles
import java.time.LocalDate
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Git facts of a review's `dir`: its [state], [head], the commits HEAD is past the reviewed commit ([sinceReviewed], null
 * without one or when git does not know it), and [changed]: by each finding's newest report commit, the old-side line
 * ranges changed since it ([Git.changedLines], null for a commit git does not know); [origin]: the URL of the remote
 * `origin`, null without one. [error]: why the read failed, the other fields then empty.
 */
data class ReviewGit(
    val state: GitState?, val head: String?, val sinceReviewed: Int?, val changed: Map<String, Map<String, List<IntRange>>?>, val origin: String?, val error: String?,
)

/** Job id to the session its live entry runs. */
fun jobSessions(live: Map<String, LiveSession>): Map<String, String> = live.entries.mapNotNull { (session, entry) -> entry.job?.let { it to session } }.toMap()

/** The review folder of [reviews] whose manager is session [sessionId]; a review that cannot be read names no manager. */
fun managedReviewFolder(reviews: Map<String, Result<BranchReview>>, sessionId: String): String? =
    reviews.entries.firstOrNull { it.value.getOrNull()?.manager?.session == sessionId }?.key

/** Gives every run with a job and no session the session of its job's entry in [jobs]; returns the sessions learned too. */
fun learnSessions(review: BranchReview, jobs: Map<String, String>): Pair<BranchReview, List<String>> =
    review.runs.filter { it.session == null && it.job in jobs }.fold(review to emptyList()) { (r, learned), run ->
        val session = jobs.getValue(checkNotNull(run.job))
        r.setSession(run.id, session) to learned + session
    }

/** Every review of [all] that could be read, its folder in the structure or not: what a reviewer's precision counts over. */
fun onDisk(all: Map<String, Result<BranchReview>>): List<BranchReview> = all.values.mapNotNull { it.getOrNull() }

/** The reviews whose folder is in [structure]; the others are kept on disk, never deleted, so a restored folder finds its review. */
fun inStructure(all: Map<String, Result<BranchReview>>, structure: SessionFolders): Map<String, Result<BranchReview>> =
    all.filterKeys { structure.find(it) != null }

/** Whether folder [id] is or lies in a review folder of [reviews], which [SessionFolders] does not know. */
fun inReview(structure: SessionFolders, reviews: Set<String>, id: String) = structure.ancestry(id).any { it.id in reviews }

/** Folder [folder] lies where the review rules forbid, inside [holder]. */
private data class Misplaced(val folder: String, val holder: String)

/** A plan folder that is or lies in a review folder, and a review folder that lies in a review or plan folder. */
private fun misplaced(s: SessionFolders, reviews: Set<String>) = s.all().mapNotNull { f ->
    when {
        f.plan != null -> s.ancestry(f.id).firstOrNull { it.id in reviews }
        f.id in reviews -> s.ancestry(f.id).dropLast(1).lastOrNull { it.id in reviews || it.plan != null }
        else -> null
    }?.let { Misplaced(f.id, it.id) }
}

/**
 * Why [after] places a folder against the review rules where [before] did not, else null: a review folder holds no plan,
 * whether a change adds one, creates one inside or moves one in, and lies in no review folder nor plan folder. Only
 * placements new in [after] count, so a structure made before a rule existed does not block unrelated writes.
 */
fun placementProblem(before: SessionFolders, after: SessionFolders, reviews: Set<String>): String? {
    val old = misplaced(before, reviews).toSet()
    val m = misplaced(after, reviews).firstOrNull { it !in old } ?: return null
    val (folder, holder) = after.pathOf(m.folder) to after.pathOf(m.holder)
    return if (checkNotNull(after.find(m.folder)).plan != null) "'$folder' would be a plan in review folder '$holder', which holds no plan"
    else "review folder '$folder' would lie in ${if (m.holder in reviews) "review" else "plan"} folder '$holder'"
}

/** Throws with [placementProblem]'s message; every structure write runs it ([ClaudeSessionsService.updateFolders]). */
fun refuseReviewPlacement(before: SessionFolders, after: SessionFolders, reviews: Set<String>) {
    placementProblem(before, after, reviews)?.let { throw IllegalArgumentException(it) }
}

/** The plan [review] is linked to, while its folder still has one. */
fun linkedPlan(review: BranchReview, structure: SessionFolders): Plan? = review.plan?.let(structure::find)?.plan

/** The plan changes [review] still needs in [structure]; null without a linked plan or when it already follows. */
fun mirrorDue(review: BranchReview, structure: SessionFolders, liveJobs: Map<String, SessionStatus>): Mirror? =
    linkedPlan(review, structure)?.let { mirrorToPlan(review, it, liveJobs) }

/**
 * Reads [review]'s git facts, its work tree's changes counted against the review's base; any failure goes into
 * [ReviewGit.error], also a work tree that has another branch checked out than the review's.
 */
fun readGit(review: BranchReview): ReviewGit = try {
    check(review.dir.isDirectory()) { "${review.dir} does not exist" }
    val state = checkNotNull(Git.state(review.dir, review.base)) { "${review.dir} is not in a git repository" }
    check(state.branch == review.branch) { "${review.dir} has ${state.branch} checked out, not ${review.branch}" }
    val head = Git.run(state.root, "rev-parse", "HEAD").trim()
    val commits = review.findings.map { it.reports.last().commit }.toSet()
    ReviewGit(state, head, reviewedCommit(review)?.let { Git.commitsBetween(state.root, it) }, commits.associateWith { Git.changedLines(state.root, it) },
        // `git config --get` exits 1 for a key not set: a repository without `origin`.
        Git.run(state.root, "config", "--get", "remote.origin.url", ok = setOf(0, 1)).trim().ifEmpty { null }, null)
} catch (e: Exception) {
    ReviewGit(null, null, null, emptyMap(), null, "${e.javaClass.simpleName}: ${e.message}")
}

/**
 * What [readGit] depends on, read from files without running git: the work tree's HEAD file, the times of the ref it
 * names (loose, else in `packed-refs`; a commit changes the ref, not HEAD), of `packed-refs` and of the index, and of
 * `review.json`; a missing file or work tree reads as null.
 */
data class GitStamp(val head: String?, val ref: Long?, val packed: Long?, val index: Long?, val review: Long?)

fun gitStamp(dir: Path, reviewFile: Path): GitStamp {
    fun time(p: Path) = if (p.exists()) p.getLastModifiedTime().toMillis() else null
    val (gitDir, common) = Git.gitDirs(dir) ?: return GitStamp(null, null, null, null, time(reviewFile))
    val head = gitDir.resolve("HEAD").readText().trim()
    return GitStamp(head, head.removePrefix("ref: ").takeIf { it != head }?.let { time(common.resolve(it)) }, time(common.resolve("packed-refs")), time(gitDir.resolve("index")), time(reviewFile))
}

/** The reviews of [stamps] whose git is read again: not read yet, or read at another stamp ([read]). */
fun gitDue(stamps: Map<String, GitStamp>, read: Map<String, GitStamp>): Set<String> = stamps.filter { (id, stamp) -> read[id] != stamp }.keys

/**
 * Every review of `~/.my-air/reviews`, read and written only here, so the review tabs and the MCP tools see one state.
 * [reviews], [facts] and the listeners are touched on the EDT only. Reviews are read off the EDT on every
 * [ClaudeSessionsService] change and on file events under the reviews directory, and with them the git facts of the
 * reviews an open tab watches ([watchGit]) whose [GitStamp] changed; only the latest read lands.
 */
@Service(Service.Level.APP)
class ReviewService : Disposable {
    private val files = ReviewFiles(Homes.myAir.resolve("reviews"))
    private val sessions = ClaudeSessionsService.getInstance()
    private var all: Map<String, Result<BranchReview>> = emptyMap()

    /** By folder id, only folders in the structure; a `review.json` that cannot be read is a failure. */
    val reviews get() = inStructure(all, sessions.folders)

    /** Every review on disk that could be read, folders gone from the structure included ([onDisk]). */
    val readable get() = onDisk(all)

    /** The folder ids of every `review.json` on disk, readable or not: what [refuseReviewPlacement] knows as review folders. */
    val onDiskIds: Set<String> get() = all.keys

    /** The job of each registry entry that runs one, to its status: what [runStatus] and the mirror read. */
    val liveJobs: Map<String, SessionStatus> get() = sessions.live.values.mapNotNull { live -> live.job?.let { it to live.status } }.toMap()

    /** By folder id, of the watched reviews read without failure; [stamps] says what each was read at, kept in step. */
    var facts: Map<String, ReviewGit> = emptyMap()
        private set
    private var stamps: Map<String, GitStamp> = emptyMap()

    /** The reviews an open review tab shows, once per tab. */
    private val watched = ArrayList<String>()

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

    /** Reads the git facts of [folderId] too until [parent] is disposed: a review tab shows them, nothing else does. */
    fun watchGit(folderId: String, parent: Disposable) {
        ThreadingAssertions.assertEventDispatchThread()
        watched += folderId
        Disposer.register(parent) { watched -= folderId }
        refresh()
    }

    /** The `review.json` of [folderId]. */
    fun file(folderId: String) = files.file(folderId)

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

    /**
     * `/clear` and `/resume` give a process a new session id: a review managed by the first of a pair of [switched] now is
     * by the second. A plain write, without the plan mirror, which the next [sync] applies.
     */
    fun moveManagers(switched: List<Pair<String, String>>) = switched.forEach { (from, to) ->
        managedReviewFolder(reviews, from)?.let { id ->
            val review = files.read(id)
            store(id, review.copy(manager = checkNotNull(review.manager).copy(session = to)))
            changed()
        }
    }

    /** Writes the new [review] of [folderId] and notifies; refuses a folder with a `review.json` on disk, in the structure or not. */
    fun create(folderId: String, review: BranchReview) {
        ThreadingAssertions.assertEventDispatchThread()
        require(!files.exists(folderId)) { "folder $folderId has a review already" }
        store(folderId, review)
        changed()
        refresh()
    }

    /** Reads every review, and the git facts of the watched ones whose stamp changed, off the EDT; callable from any thread. */
    fun refresh() = ApplicationManager.getApplication().invokeLater {
        val generation = ++requested
        val watching = watched.toSet() intersect sessions.folders.all().map { it.id }.toSet()
        val (lastStamps, lastFacts) = stamps to facts
        reader.execute {
            if (generation != requested) return@execute
            // Nobody reads the Future, so an exception would vanish with it; log it as an IDE error instead.
            val (read, git) = try {
                val read = files.readAll()
                val now = watching.mapNotNull { id -> read[id]?.getOrNull()?.let { id to gitStamp(it.dir, files.file(id)) } }.toMap()
                val due = gitDue(now, lastStamps)
                read to now.mapValues { (id, stamp) -> stamp to if (id in due) readGit(read.getValue(id).getOrThrow()) else lastFacts.getValue(id) }
            } catch (e: Throwable) {
                thisLogger().error("Review refresh failed", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != requested) return@invokeLater
                all = read
                facts = git.mapValues { it.value.second }
                stamps = git.mapValues { it.value.first }
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
            review.runs.any { it.session == null && it.job in jobs } || mirrorDue(review, sessions.folders, liveJobs) != null
        }
        due.keys.forEach { id -> change(id) { learnSessions(it, jobs) } }
    }

    /**
     * Applies [op] to the review on disk. When sessions were learned or the plan does not follow as this IDE last read the
     * structure, the mirror is computed in the folders write against the structure read from disk then (another IDE may
     * have mirrored meanwhile), and the review is written there with the mirror's blocker ids before the structure, so the
     * plan never holds a blocker the review does not map (a blocker id the plan lacks is added again by the next mirror);
     * the sessions are filed in the review folder and the mirror applied in that one write.
     */
    private fun change(folderId: String, op: (BranchReview) -> Pair<BranchReview, List<String>>): BranchReview {
        ThreadingAssertions.assertEventDispatchThread()
        val before = files.read(folderId)
        val (review, filed) = op(before)
        val jobs = liveJobs
        var stored = review
        if (filed.isEmpty() && mirrorDue(review, sessions.folders, jobs) == null) {
            if (review == before) return before
            store(folderId, review)
        } else sessions.updateFolders(ChangeSource.AUTO) { structure ->
            val mirror = mirrorDue(review, structure, jobs)
            stored = mirror?.let { review.copy(blockers = it.blockers) } ?: review
            if (stored != before) store(folderId, stored)
            val assigned = filed.fold(structure) { s, session -> s.assign(session, folderId) }
            if (mirror == null) assigned
            else PlanChanges.apply(assigned, checkNotNull(review.plan), mirror.changes, LocalDate.now()).also { after ->
                // A mirror that does not converge would rewrite the plan on every notification.
                check(mirrorDue(stored, after, jobs) == null) { "the mirror of review $folderId does not converge: ${mirror.changes}" }
            }
        }
        changed()
        return stored
    }

    /** Writes [review] as the review of [folderId] and holds it; drops a read started before, which would land it as it was. */
    private fun store(folderId: String, review: BranchReview) {
        files.write(folderId, review)
        requested++
        all = all + (folderId to Result.success(review))
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
