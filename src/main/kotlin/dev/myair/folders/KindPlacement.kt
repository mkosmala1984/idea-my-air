package dev.myair.folders

/** Folder [folder] lies where the kind rules forbid, inside [holder] (which may be [folder] itself: a plan that is a review folder). */
private data class Misplaced(val folder: String, val holder: String)

/**
 * Every folder of [s] against the kind rules: a plan folder that is or lies in a review or canvas folder; a review folder
 * that lies in a review, plan or canvas folder; a canvas folder that lies in a review or canvas folder (a plan folder may
 * hold a canvas, so a plan task can have one). [reviews] and [canvases] are folder ids, which [SessionFolders] does not know.
 */
private fun misplaced(s: SessionFolders, reviews: Set<String>, canvases: Set<String>) = s.all().mapNotNull { f ->
    when {
        f.plan != null -> s.ancestry(f.id).firstOrNull { it.id in reviews || it.id in canvases }
        f.id in reviews -> s.ancestry(f.id).dropLast(1).lastOrNull { it.id in reviews || it.plan != null || it.id in canvases }
        f.id in canvases -> s.ancestry(f.id).dropLast(1).lastOrNull { it.id in reviews || it.id in canvases }
        else -> null
    }?.let { Misplaced(f.id, it.id) }
}

/**
 * Why [after] places a folder against the kind rules where [before] did not, else null: a review folder holds no plan,
 * whether a change adds one, creates one inside or moves one in, and lies in no review folder nor plan folder; a canvas
 * folder holds no plan, review or canvas folder and lies in no review folder (it may lie in a plan folder). Only
 * placements new in [after] count, so a structure made before a rule existed does not block unrelated writes.
 */
fun placementProblem(before: SessionFolders, after: SessionFolders, reviews: Set<String>, canvases: Set<String> = emptySet()): String? {
    val old = misplaced(before, reviews, canvases).toSet()
    val m = misplaced(after, reviews, canvases).firstOrNull { it !in old } ?: return null
    val (folder, holder) = after.pathOf(m.folder) to after.pathOf(m.holder)
    val holderKind = when {
        m.holder in reviews -> "review"
        m.holder in canvases -> "canvas"
        else -> "plan"
    }
    return when {
        checkNotNull(after.find(m.folder)).plan != null -> "'$folder' would be a plan in $holderKind folder '$holder', which holds no plan"
        m.folder in reviews -> "review folder '$folder' would lie in $holderKind folder '$holder'"
        else -> "canvas folder '$folder' would lie in $holderKind folder '$holder'"
    }
}

/** Throws with [placementProblem]'s message; every structure write runs it (`ClaudeSessionsService.updateFolders`). */
fun refuseKindPlacement(before: SessionFolders, after: SessionFolders, reviews: Set<String>, canvases: Set<String> = emptySet()) {
    placementProblem(before, after, reviews, canvases)?.let { throw IllegalArgumentException(it) }
}

/**
 * Whether folder [id] is or lies in a review folder of [reviews] or a canvas folder of [canvases]: no plan, review or
 * canvas folder may be made or moved there, so the tree's menus disable such items there. Only enables them: the write
 * refuses ([refuseKindPlacement]) whoever makes it.
 */
fun inReviewOrCanvas(structure: SessionFolders, reviews: Set<String>, canvases: Set<String>, id: String) =
    structure.ancestry(id).any { it.id in reviews || it.id in canvases }
