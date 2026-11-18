package dev.myair.folders

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import dev.myair.claude.ClaudeSession
import dev.myair.claude.isAtOrUnder
import java.lang.reflect.Type
import java.nio.file.Path
import java.time.LocalDate

/**
 * A user folder of Folders mode; toString() is the node id TreeState keeps expansion and selection by. A folder bound to
 * [dirs] also holds the sessions of those directories filed nowhere else; one with a [plan] is a plan folder. Sessions
 * started from it or a folder below it run in [workdir], which holds no session and may be any folder's too.
 */
data class Folder(
    val id: String, val name: String, val folders: List<Folder> = emptyList(), val items: List<Item> = emptyList(),
    val dirs: List<String> = emptyList(), val plan: Plan? = null, val workdir: String? = null,
) {
    override fun toString() = id

    /** Where a session started from this folder runs: its [workdir], else its first bound directory. */
    val startDir: String? get() = workdir ?: dirs.firstOrNull()

    /** This folder and every folder below it. */
    fun subtree(): Sequence<Folder> = sequenceOf(this) + folders.asSequence().flatMap { it.subtree() }
}

/** The folder of [ancestry] (top-down) that decides where a session started from its last one runs: the nearest with a [Folder.startDir]. */
fun startFolder(ancestry: List<Folder>): Folder? = ancestry.lastOrNull { it.startDir != null }

/** Something filed in a folder. Version 1 knows only sessions; a new type extends [SessionFolders.parse], it is never skipped. */
data class Item(val type: String, val id: String)

/**
 * The user's folder tree, in the order the user arranged it. A session is filed in at most one folder, or kept in the
 * fixed `sessions` folder by hand ([unfiled]); [placeOf] says where each one shows. [nextId] numbers the next milestone or task. Operations naming a folder, milestone or task that
 * no longer exists change nothing, because another IDE may have deleted it; every other broken invariant throws.
 */
data class SessionFolders(val folders: List<Folder> = emptyList(), val nextId: Int = 1, val unfiled: List<String> = emptyList()) {
    init {
        val all = all().toList()
        require(all.map { it.id }.duplicates().isEmpty()) { "duplicate folder ids ${all.map { it.id }.duplicates()}" }
        val items = all.flatMap { it.items }
        items.forEach { require(it.type == SESSION) { "unknown item type '${it.type}'" } }
        require(items.map { it.id }.duplicates().isEmpty()) { "sessions filed twice: ${items.map { it.id }.duplicates()}" }
        require(unfiled.duplicates().isEmpty()) { "sessions unfiled twice: ${unfiled.duplicates()}" }
        require(items.none { it.id in unfiled }) { "sessions both filed and unfiled: ${items.map { it.id }.filter { it in unfiled }}" }
        val dirs = all.flatMap { it.dirs }
        dirs.forEach { require(it.isNotBlank()) { "blank directory in ${all.filter { f -> it in f.dirs }.map { f -> f.id }}" } }
        require(dirs.duplicates().isEmpty()) { "directories bound twice: ${dirs.duplicates()}" }
        all.filter { it.workdir?.isBlank() == true }.let { require(it.isEmpty()) { "blank working directory in ${it.map { f -> f.id }}" } }
        (sequenceOf(folders) + all.asSequence().map { it.folders }).forEach { siblings ->
            siblings.forEach { require(it.name.isNotBlank() && it.name == it.name.trim() && '/' !in it.name && it.name.none(Char::isISOControl)) { "bad folder name '${it.name}'" } }
            require(siblings.map { it.name.lowercase() }.duplicates().isEmpty()) { "duplicate folder names among ${siblings.map { it.name }}" }
        }
        val plans = all.filter { it.plan != null }
        plans.forEach { f -> require(f.folders.none { sub -> sub.subtree().any { it.plan != null } }) { "plan folder '${f.name}' holds another plan folder" } }
        val milestones = plans.flatMap { it.plan!!.milestones }
        val tasks = milestones.flatMap { it.tasks }
        val ids = milestones.map { it.id } + tasks.map { it.id }
        require(ids.duplicates().isEmpty()) { "duplicate plan ids ${ids.duplicates()}" }
        milestones.forEach { require(it.id.isPlanId('M') && it.title.isNotBlank()) { "bad milestone $it" } }
        tasks.forEach { require(it.id.isPlanId('T') && it.title.isNotBlank() && it.next?.isBlank() != true) { "bad task $it" } }
    }

    private fun String.isPlanId(prefix: Char) = first() == prefix && drop(1).toIntOrNull()?.let { it in 1 until nextId } == true

    fun all(): Sequence<Folder> = folders.asSequence().flatMap { it.subtree() }

    fun find(id: String) = all().firstOrNull { it.id == id }

    fun folderOf(sessionId: String) = all().firstOrNull { folder -> folder.items.any { it.id == sessionId } }?.id

    /**
     * The folder bound to [cwd] or to the nearest directory above it, so a project's folder also holds the sessions of its
     * worktrees (`.claude/worktrees/<name>`) and subdirectories. Paths compare as text: the project rows under `sessions`
     * are grouped by hash, and the IDE's paths hash with case though they equal without it, so two spellings of a
     * directory are two rows that bind separately.
     */
    fun projectFolder(cwd: Path) = all().flatMap { f -> f.dirs.asSequence().map { f to it } }.filter { cwd.isAtOrUnder(it.second) }.maxByOrNull { it.second.length }?.first

    /** The folder deciding where a session started from folder [id] runs ([startFolder]). */
    fun startFolder(id: String) = startFolder(ancestry(id))

    /** The folders from the top level down to [id], inclusive; empty when there is no such folder. */
    fun ancestry(id: String): List<Folder> {
        fun search(level: List<Folder>): List<Folder>? = level.firstNotNullOfOrNull { f -> if (f.id == id) listOf(f) else search(f.folders)?.let { listOf(f) + it } }
        return search(folders).orEmpty()
    }

    /** The plan folder that is [folderId] or holds it. */
    fun planFolderAt(folderId: String) = ancestry(folderId).lastOrNull { it.plan != null }

    /**
     * The plans of [dir]: the plan folder its bound folder is or lies in, or every plan folder below its bound folder (plan
     * folders do not nest, so these never overlap), in tree order; then every other plan folder whose own working directory
     * holds [dir], as a directory binds to one folder only while any number of plans may work in it.
     */
    fun planFoldersOf(dir: Path): List<Folder> {
        val bound = projectFolder(dir)?.let { (ancestry(it.id).dropLast(1).asSequence() + it.subtree()).filter { f -> f.plan != null }.toList() }.orEmpty()
        return bound + all().filter { f -> f.plan != null && f.workdir?.let(dir::isAtOrUnder) == true && f !in bound }
    }

    /**
     * Where a new plan for sessions of [dir] goes: the folder bound to it, or the parent of the plan folder that folder is or
     * lies in, as plans do not nest; null (the top level) when no folder is bound to it or the plan folder is at the top.
     */
    fun planParentFor(dir: Path): String? {
        val bound = projectFolder(dir) ?: return null
        val plan = planFolderAt(bound.id) ?: return bound.id
        return ancestry(plan.id).dropLast(1).lastOrNull()?.id
    }

    fun findTask(id: String) = all().firstNotNullOfOrNull { f -> f.plan?.tasks()?.firstOrNull { it.id == id }?.let { f to it } }

    fun findMilestone(id: String) = all().firstNotNullOfOrNull { f -> f.plan?.milestones?.firstOrNull { it.id == id }?.let { f to it } }

    /** The plan task [session]'s brief names, with its plan folder, while it exists. */
    fun taskOf(session: ClaudeSession) = session.brief?.planTask?.let(::findTask)

    /**
     * Where the session shows: the plan folder of the task its brief names, else the folder the user filed it in, else
     * `sessions` (null) when the user put it there, else the folder its brief names while that lies in no plan folder,
     * else the folder bound to its [ClaudeSession.project], else `sessions`.
     */
    fun placeOf(session: ClaudeSession): String? = taskOf(session)?.first?.id ?: folderOf(session.id)
        ?: if (session.id in unfiled) null
        else session.brief?.folder?.takeIf { find(it) != null && planFolderAt(it) == null } ?: projectFolder(session.project)?.id

    /**
     * The folder at [path] a brief files its session in, with the structure holding it: an existing one, else a new one
     * named by the path's last segment under an existing folder, [newId] its id. A top-level folder, a missing parent and a
     * folder at or in a plan folder are refused, since sessions in a plan are placed by their plan task.
     */
    fun briefFolder(path: String, newId: () -> String): Pair<SessionFolders, Folder> {
        val (structure, id) = findByPath(path)?.let { this to it.id } ?: run {
            val parentPath = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            require(parentPath.isNotEmpty()) { "'$path' would be a new top-level folder; new folders go below an existing one" }
            require(name.isNotBlank() && name == name.trim()) { "bad folder name '$name'" }
            val parent = requireNotNull(findByPath(parentPath)) { "no folder '$parentPath' to create '$name' in" }
            newId().let { createFolder(parent.id, it, name) to it }
        }
        require(structure.planFolderAt(id) == null) { "'$path' is or lies in a plan folder; name the plan task in plan_task instead" }
        return structure to checkNotNull(structure.find(id))
    }

    /** The folder at [path], names joined by `/` and matched ignoring case like sibling names are, or null. */
    fun findByPath(path: String): Folder? {
        var level = folders
        var found: Folder? = null
        for (name in path.split('/')) {
            found = level.firstOrNull { it.name.equals(name, true) } ?: return null
            level = found.folders
        }
        return found
    }

    /** The folder's names from the top level down, joined by `/`. */
    fun pathOf(id: String): String = ancestry(id).also { check(it.isNotEmpty()) { "no folder $id" } }.joinToString("/") { it.name }

    /** The folders directly under [parentId], or at the top level for null. */
    fun children(parentId: String?) = if (parentId == null) folders else checkNotNull(find(parentId)) { "no folder $parentId" }.folders

    /** Whether [name] is taken among the children of [parentId] by a folder other than [except]. */
    fun nameTaken(parentId: String?, name: String, except: String? = null) =
        children(parentId).any { it.id != except && it.name.equals(name.trim(), true) }

    /** Whether the folder [id] may get a plan: it has none, and no plan folder holds it or lies below it. */
    fun canAddPlan(id: String) = find(id)?.let { f -> f.subtree().none { it.plan != null } && planFolderAt(id) == null } == true

    /** Whether [moved] may go under [parentId] without a plan folder ending up in another. */
    fun canHold(parentId: String?, moved: Folder) = parentId == null || moved.subtree().none { it.plan != null } || planFolderAt(parentId) == null

    fun createFolder(parentId: String?, id: String, name: String, dirs: List<String> = emptyList(), plan: Plan? = null): SessionFolders {
        if (parentId != null && find(parentId) == null) return this
        return withChildren(parentId) { it + Folder(id, name, dirs = dirs, plan = plan) }
    }

    fun rename(id: String, name: String) = if (find(id) == null) this else edit(id) { it.copy(name = name) }

    /** Removes the folder and its subtree, plans included; the sessions filed there, and the directories bound there, go back to `sessions`. */
    fun delete(id: String) = if (find(id) == null) this else copy(folders = folders.without(id))

    /** Moves the folder under [parentId] (top level for null) to position [index] among that parent's children as they are before the move. */
    fun moveFolder(id: String, parentId: String?, index: Int): SessionFolders {
        val moved = find(id) ?: return this
        if (parentId != null && find(parentId) == null) return this
        require(moved.subtree().none { it.id == parentId }) { "folder $id cannot move into itself" }
        val siblings = children(parentId)
        require(index in 0..siblings.size) { "index $index outside 0..${siblings.size}" }
        val current = siblings.indexOfFirst { it.id == id }
        val at = if (current in 0 until index) index - 1 else index
        return copy(folders = folders.without(id)).withChildren(parentId) { it.toMutableList().apply { add(at, moved) } }
    }

    /**
     * Files the session in [folderId], taking it out of any other folder and out of [unfiled]; null takes it out of its
     * folder only, so a session of a bound project or with a brief's folder shows there again.
     */
    fun assign(sessionId: String, folderId: String?): SessionFolders {
        if (folderId != null && find(folderId) == null) return this
        val out = copy(folders = folders.map { it.mapSubtree { f -> f.copy(items = f.items.filter { item -> item.id != sessionId }) } })
        return if (folderId == null) out else out.copy(unfiled = unfiled - sessionId).edit(folderId) { it.copy(items = it.items + Item(SESSION, sessionId)) }
    }

    /** Takes the session out of its folder and keeps it in `sessions`, whatever its project or brief say. */
    fun unfile(sessionId: String): SessionFolders = assign(sessionId, null).let { if (sessionId in it.unfiled) it else it.copy(unfiled = it.unfiled + sessionId) }

    /**
     * A tab switched from session [from] to [to] in the same process. `/clear` starts a session no scan has seen yet, which
     * continues the same work and takes [from]'s folder, or its place in `sessions` kept by hand; `/resume` lands on a
     * session in [scanned], which stays where it is. A brief's folder is not carried: the new session's agent names its own.
     */
    fun carryOver(from: String, to: String, scanned: Set<String>): SessionFolders {
        if (to in scanned || folderOf(to) != null || to in unfiled) return this
        if (from in unfiled) return unfile(to)
        return folderOf(from)?.let { assign(to, it) } ?: this
    }

    /** Sessions filed in the folder or anywhere below it. */
    fun sessionCount(id: String) = checkNotNull(find(id)) { "no folder $id" }.subtree().sumOf { it.items.size }

    /** Binds [dir] to the folder; its sessions filed nowhere else show there. */
    fun bind(folderId: String, dir: String) = if (find(folderId) == null) this else edit(folderId) { it.copy(dirs = it.dirs + dir) }

    /** Sets the folder's working directory, or clears it with null. */
    fun setWorkdir(folderId: String, dir: String?) = if (find(folderId) == null) this else edit(folderId) { it.copy(workdir = dir) }

    /** Unbinds [dir] from whichever folder it is bound to. */
    fun unbind(dir: String) = copy(folders = folders.map { it.mapSubtree { f -> f.copy(dirs = f.dirs - dir) } })

    fun addPlan(folderId: String): SessionFolders {
        if (find(folderId) == null) return this
        require(canAddPlan(folderId)) { "folder $folderId cannot get a plan: it has one, or lies in or holds a plan folder" }
        return edit(folderId) { it.copy(plan = Plan()) }
    }

    /** Takes the plan off its folder; only an empty one, since removing it loses no milestone that way. */
    fun removePlan(folderId: String) = editPlan(folderId) { plan ->
        require(plan.milestones.isEmpty()) { "the plan still has milestones" }
        null
    }

    fun setDescription(folderId: String, description: String?) = editPlan(folderId) { it.copy(description = description) }

    /** Adds milestone `M<nextId>` at [index] (last for null) of the plan of [folderId]. */
    fun addMilestone(folderId: String, title: String, index: Int? = null, description: String? = null): SessionFolders {
        if (find(folderId) == null) return this
        val id = "M$nextId"
        return copy(nextId = nextId + 1).editPlan(folderId) { it.copy(milestones = it.milestones.inserted(Milestone(id, title, description = description), index)) }
    }

    /** Adds task `T<nextId>` at [index] (last for null) of [milestoneId]. */
    fun addTask(milestoneId: String, title: String, index: Int? = null, description: String? = null): SessionFolders {
        if (findMilestone(milestoneId) == null) return this
        val id = "T$nextId"
        return copy(nextId = nextId + 1).editMilestone(milestoneId) { it.copy(tasks = it.tasks.inserted(PlanTask(id, title, description = description), index)) }
    }

    fun editMilestone(id: String, change: (Milestone) -> Milestone): SessionFolders {
        val (folder, _) = findMilestone(id) ?: return this
        return editPlan(folder.id) { plan -> plan.copy(milestones = plan.milestones.map { if (it.id == id) change(it) else it }) }
    }

    fun editTask(id: String, change: (PlanTask) -> PlanTask): SessionFolders {
        val (folder, _) = findTask(id) ?: return this
        return editPlan(folder.id) { plan -> plan.copy(milestones = plan.milestones.map { m -> m.copy(tasks = m.tasks.map { if (it.id == id) change(it) else it }) }) }
    }

    /** Moves the milestone to position [index] among its plan's milestones after the move. */
    fun moveMilestone(id: String, index: Int): SessionFolders {
        val (folder, moved) = findMilestone(id) ?: return this
        return editPlan(folder.id) { plan -> plan.copy(milestones = (plan.milestones - moved).inserted(moved, index)) }
    }

    /** Moves the task into [milestoneId] of the same plan, to position [index] among its tasks after the move. */
    fun moveTask(id: String, milestoneId: String, index: Int): SessionFolders {
        val (folder, moved) = findTask(id) ?: return this
        val (target, _) = findMilestone(milestoneId) ?: return this
        require(target.id == folder.id) { "task $id cannot move to milestone $milestoneId of another plan" }
        return editPlan(folder.id) { plan ->
            val without = plan.milestones.map { m -> m.copy(tasks = m.tasks - moved) }
            plan.copy(milestones = without.map { m -> if (m.id == milestoneId) m.copy(tasks = m.tasks.inserted(moved, index)) else m })
        }
    }

    /** Deletes a task, or a milestone whose tasks are all done or canceled, taking the deleted tasks out of every requirement. */
    fun deletePlanItem(id: String): SessionFolders {
        findTask(id)?.let { (folder, task) ->
            return editPlan(folder.id) { it.withoutTasks(setOf(task.id)) }
        }
        val (folder, milestone) = findMilestone(id) ?: return this
        require(milestone.tasks.all { it.status.closed }) { "milestone $id has open tasks ${milestone.tasks.filterNot { it.status.closed }.map { it.id }}" }
        return editPlan(folder.id) { plan -> plan.withoutTasks(milestone.tasks.map { it.id }.toSet()).let { it.copy(milestones = it.milestones.filter { m -> m.id != id }) } }
    }

    fun setConstraints(folderId: String, constraints: String?) = editPlan(folderId) { it.copy(constraints = constraints) }

    /** Adds decision `D<n>` dated [today], taken in [session] when one is named. */
    fun addDecision(folderId: String, text: String, today: LocalDate, session: String? = null) = editPlan(folderId) { plan ->
        plan.allocate('D').let { (id, p) -> p.copy(decisions = p.decisions + Decision(id, today.toString(), text, session)) }
    }

    /** Adds requirement `R<n>` at [index] (last for null), delivered by [tasks] of the same plan. */
    fun addRequirement(folderId: String, text: String, eval: EvalKind, tasks: List<String> = emptyList(), index: Int? = null) = editPlan(folderId) { plan ->
        plan.allocate('R').let { (id, p) -> p.copy(requirements = p.requirements.inserted(Requirement(id, text, eval, tasks), index)) }
    }

    fun editRequirement(folderId: String, id: String, change: (Requirement) -> Requirement) = if (!hasEntry(folderId, id)) this else editPlan(folderId) { plan ->
        plan.copy(requirements = plan.requirements.map { if (it.id == id) change(it) else it })
    }

    fun editDecision(folderId: String, id: String, change: (Decision) -> Decision) = if (!hasEntry(folderId, id)) this else editPlan(folderId) { plan ->
        plan.copy(decisions = plan.decisions.map { if (it.id == id) change(it) else it })
    }

    /** Sets the review's status at [commit] as of [today], keeping its blockers. */
    fun setReview(folderId: String, status: ReviewStatus, commit: String?, today: LocalDate) = editPlan(folderId) { plan ->
        plan.copy(review = Review(status, commit, today.toString(), plan.review?.blockers.orEmpty()))
    }

    /** Adds blocker `B<n>` to the review, starting one not done as of [today] when the plan has none. */
    fun addBlocker(folderId: String, text: String, today: LocalDate) = editPlan(folderId) { plan ->
        plan.allocate('B').let { (id, p) ->
            val review = p.review ?: Review(ReviewStatus.NOT_DONE, null, today.toString())
            p.copy(review = review.copy(blockers = review.blockers + Blocker(id, text)))
        }
    }

    fun editBlocker(folderId: String, id: String, change: (Blocker) -> Blocker) = if (!hasEntry(folderId, id)) this else editPlan(folderId) { plan ->
        plan.copy(review = plan.review!!.let { r -> r.copy(blockers = r.blockers.map { if (it.id == id) change(it) else it }) })
    }

    /** Deletes the requirement, decision or blocker [id]; its number is not handed out again. */
    fun deleteEntry(folderId: String, id: String): SessionFolders {
        require(id.take(1) in Plan.ENTRY_PREFIXES) { "'$id' is no requirement, decision or blocker id" }
        if (!hasEntry(folderId, id)) return this
        return editPlan(folderId) { plan ->
            plan.copy(
                requirements = plan.requirements.filter { it.id != id }, decisions = plan.decisions.filter { it.id != id },
                review = plan.review?.let { r -> r.copy(blockers = r.blockers.filter { it.id != id }) },
            )
        }
    }

    /** Whether the plan of [folderId] holds the requirement, decision or blocker [id]. */
    private fun hasEntry(folderId: String, id: String) = find(folderId)?.plan?.let { p ->
        p.requirements.any { it.id == id } || p.decisions.any { it.id == id } || p.review?.blockers.orEmpty().any { it.id == id }
    } == true

    /**
     * [snapshot] in place of this structure, keeping the higher [nextId] and, per plan and prefix, the higher [Plan.next],
     * so ids handed out since it was taken are not handed out again.
     */
    fun restore(snapshot: SessionFolders) = snapshot.copy(nextId = maxOf(nextId, snapshot.nextId), folders = snapshot.folders.map {
        it.mapSubtree { f ->
            val now = find(f.id)?.plan
            if (f.plan == null || now == null) f
            else f.copy(plan = f.plan.copy(next = (f.plan.next.keys + now.next.keys).associateWith { k -> maxOf(f.plan.next[k] ?: 1, now.next[k] ?: 1) }))
        }
    })

    /** Version 4 unless a plan holds something version 5 brought, so a structure without it stays readable by builds before it. */
    fun toJson(): String = GsonBuilder().setPrettyPrinting().registerTypeAdapter(Plan::class.java, PlanSerializer).create()
        .toJson(linkedMapOf("version" to if (all().any { it.plan?.usesVersion5 == true }) VERSION else 4, "nextId" to nextId, "folders" to folders, "unfiled" to unfiled))

    private fun editPlan(folderId: String, change: (Plan) -> Plan?): SessionFolders {
        val folder = find(folderId) ?: return this
        val plan = requireNotNull(folder.plan) { "folder '${folder.name}' has no plan" }
        return edit(folderId) { it.copy(plan = change(plan)) }
    }

    private fun edit(id: String, change: (Folder) -> Folder) = copy(folders = folders.map { it.mapSubtree { f -> if (f.id == id) change(f) else f } })

    private fun withChildren(parentId: String?, change: (List<Folder>) -> List<Folder>) =
        if (parentId == null) copy(folders = change(folders)) else edit(parentId) { it.copy(folders = change(it.folders)) }

    companion object {
        const val SESSION = "session"
        /**
         * Version 2 added a folder's bound `project`; version 3 made it a list, [Folder.dirs], and added [Folder.plan] and
         * [nextId]; version 4 added [unfiled] and later an optional [Folder.workdir], written only when set; version 5 added
         * a plan's constraints, decisions, requirements, review and next, and is written only while a plan holds one of them.
         * Older files read as the same structure without what came later.
         */
        private const val VERSION = 5
        private val FOLDER_FIELDS = mapOf(1 to setOf("id", "name", "folders", "items"), 2 to setOf("id", "name", "folders", "items", "project"), 3 to setOf("id", "name", "folders", "items", "dirs", "plan"), 4 to setOf("id", "name", "folders", "items", "dirs", "plan", "workdir"))
            .let { it + (5 to it.getValue(4)) }

        /** Reads `structure.json`, throwing on anything it does not know rather than skipping it. */
        fun parse(text: String): SessionFolders {
            val root = JsonParser.parseString(text).asJsonObject
            val version = root.field("version").asInt
            require(version in 1..VERSION) { "unsupported structure version $version" }
            root.only(when (version) { 4, 5 -> setOf("version", "nextId", "folders", "unfiled"); 3 -> setOf("version", "nextId", "folders"); else -> setOf("version", "folders") })
            return SessionFolders(
                root.field("folders").asJsonArray.map { folder(it.asJsonObject, version) },
                if (version >= 3) root.field("nextId").asInt else 1,
                if (version >= 4) root.field("unfiled").asJsonArray.map { it.asString } else emptyList(),
            )
        }

        private fun folder(json: JsonObject, version: Int): Folder {
            json.only(FOLDER_FIELDS.getValue(version))
            return Folder(
                json.field("id").asString,
                json.field("name").asString,
                json.field("folders").asJsonArray.map { folder(it.asJsonObject, version) },
                json.field("items").asJsonArray.map { it.asJsonObject.let { item -> Item(item.field("type").asString, item.field("id").asString) } },
                // Gson leaves out nulls: version 2 wrote `project` only on bound folders, version 3 `plan` only on plan folders.
                when (version) {
                    3, 4, 5 -> json.field("dirs").asJsonArray.map { it.asString }
                    else -> listOfNotNull(json.get("project")?.asString)
                },
                json.get("plan")?.asJsonObject?.let { plan(it, version) },
                json.get("workdir")?.asString,
            )
        }

        // Descriptions, like a task's `next`, are written only when set, so a file without any stays readable by builds before them.
        private fun plan(json: JsonObject, version: Int): Plan {
            json.only(if (version == 5) setOf("description", "milestones", "constraints", "decisions", "requirements", "review", "next") else setOf("description", "milestones"))
            return Plan(
                json.get("description")?.asString, json.field("milestones").asJsonArray.map { milestone(it.asJsonObject) },
                json.get("constraints")?.asString, json.get("decisions")?.asJsonArray?.map { decision(it.asJsonObject) }.orEmpty(),
                json.get("requirements")?.asJsonArray?.map { requirement(it.asJsonObject) }.orEmpty(), json.get("review")?.asJsonObject?.let(::review),
                json.get("next")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asInt }.orEmpty(),
            )
        }

        private fun requirement(json: JsonObject) = json.only(setOf("id", "text", "eval", "tasks", "evidence")).let {
            Requirement(
                json.field("id").asString, json.field("text").asString, EvalKind.of(json.field("eval").asString),
                json.field("tasks").asJsonArray.map { it.asString }, json.field("evidence").asJsonArray.map { it.asString },
            )
        }

        private fun decision(json: JsonObject) = json.only(setOf("id", "date", "text", "session")).let {
            Decision(json.field("id").asString, json.field("date").asString, json.field("text").asString, json.get("session")?.asString)
        }

        private fun review(json: JsonObject) = json.only(setOf("status", "commit", "date", "blockers")).let {
            Review(
                ReviewStatus.of(json.field("status").asString), json.get("commit")?.asString, json.field("date").asString,
                json.field("blockers").asJsonArray.map { b -> b.asJsonObject.let { o -> o.only(setOf("id", "text", "resolved")); Blocker(o.field("id").asString, o.field("text").asString, o.field("resolved").asBoolean) } },
            )
        }

        private fun milestone(json: JsonObject) = json.only(setOf("id", "title", "done", "tasks", "description")).let {
            Milestone(
                json.field("id").asString, json.field("title").asString, json.field("done").asBoolean,
                json.field("tasks").asJsonArray.map { t -> task(t.asJsonObject) }, json.get("description")?.asString,
            )
        }

        private fun task(json: JsonObject) = json.only(setOf("id", "title", "status", "next", "description")).let {
            PlanTask(json.field("id").asString, json.field("title").asString, PlanStatus.of(json.field("status").asString), json.get("next")?.asString, json.get("description")?.asString)
        }

        private fun JsonObject.field(name: String): JsonElement = checkNotNull(get(name)) { "missing '$name' in $this" }

        private fun JsonObject.only(fields: Set<String>) = require(keySet().all { it in fields }) { "unknown fields ${keySet() - fields} in $this" }
    }
}

/** A plan as Gson writes it, leaving out the lists and map of version 5 while empty, as it leaves out nulls. */
private object PlanSerializer : JsonSerializer<Plan> {
    override fun serialize(plan: Plan, type: Type, context: JsonSerializationContext) = JsonObject().apply {
        plan.description?.let { addProperty("description", it) }
        add("milestones", context.serialize(plan.milestones))
        plan.constraints?.let { addProperty("constraints", it) }
        if (plan.decisions.isNotEmpty()) add("decisions", context.serialize(plan.decisions))
        if (plan.requirements.isNotEmpty()) add("requirements", context.serialize(plan.requirements))
        plan.review?.let { add("review", context.serialize(it)) }
        if (plan.next.isNotEmpty()) add("next", context.serialize(plan.next))
    }
}

/** A new id of entry kind [prefix] (`R`, `D`, `B`), with the plan numbering the next one after it. */
private fun Plan.allocate(prefix: Char): Pair<String, Plan> {
    val n = next[prefix.toString()] ?: 1
    return "$prefix$n" to copy(next = next + (prefix.toString() to n + 1))
}

/** The plan without the [gone] tasks, taken out of their milestones and every requirement at once. */
private fun Plan.withoutTasks(gone: Set<String>) =
    copy(milestones = milestones.map { m -> m.copy(tasks = m.tasks.filter { it.id !in gone }) }, requirements = requirements.map { it.copy(tasks = it.tasks - gone) })

private fun Folder.mapSubtree(change: (Folder) -> Folder): Folder = change(copy(folders = folders.map { it.mapSubtree(change) }))

private fun List<Folder>.without(id: String): List<Folder> = filter { it.id != id }.map { it.copy(folders = it.folders.without(id)) }

/** [item] at [index], last for null; an index past the end throws. */
private fun <T> List<T>.inserted(item: T, index: Int?): List<T> {
    require(index == null || index in 0..size) { "index $index outside 0..$size" }
    return toMutableList().apply { add(index ?: size, item) }
}

private fun <T> List<T>.duplicates() = groupingBy { it }.eachCount().filterValues { it > 1 }.keys
