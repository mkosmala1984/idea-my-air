package dev.myair.toolwindow

import dev.myair.actions.FileChange
import dev.myair.actions.FileChange.Stage

/** A row of [changeTree]: a folder or a changed file. */
sealed interface ChangeNode {
    val name: String
}

/** A folder; [name] joins a chain of folders holding nothing but the next one, as Branch Diff compacts them. */
data class ChangeDir(override val name: String, val children: List<ChangeNode>) : ChangeNode {
    val files: Int get() = children.sumOf { if (it is ChangeDir) it.files else 1 }
    val added: Int get() = children.sumOf { if (it is ChangeDir) it.added else (it as ChangeFile).change.added }
    val removed: Int get() = children.sumOf { if (it is ChangeDir) it.removed else (it as ChangeFile).change.removed }
}

/** A changed file; [index] is its place in the list the tree was built from, files filtered out included, which [dev.myair.actions.showBaseDiff] takes. */
data class ChangeFile(override val name: String, val index: Int, val change: FileChange) : ChangeNode

/** Which of the changes against the base Changes vs base shows, by how far each has gone. */
enum class ChangesFilter(val label: String, private val stages: Set<Stage>) {
    ALL("All", Stage.entries.toSet()),
    NOT_PUSHED("Not pushed", setOf(Stage.UNCOMMITTED, Stage.COMMITTED)),
    UNCOMMITTED("Uncommitted", setOf(Stage.UNCOMMITTED));

    fun shows(change: FileChange) = change.stage in stages
}

/**
 * The changes of [changes] that [filter] shows as a tree of folders, as Branch Diff shows them: folders first, then files, each by name, a folder holding
 * only one folder merged into it (`java/com/deviniti`). The root, unnamed, is never merged.
 */
fun changeTree(changes: List<FileChange>, filter: ChangesFilter): ChangeDir {
    class Building(val name: String) {
        val dirs = HashMap<String, Building>()
        val files = ArrayList<ChangeFile>()
        fun build(): ChangeDir = ChangeDir(name, dirs.values.sortedBy { it.name }.map { compacted(it.build()) } + files.sortedBy { it.name })
    }
    val root = Building("")
    for ((index, change) in changes.withIndex()) {
        if (!filter.shows(change)) continue
        val parts = change.path.split('/')
        check(parts.none { it.isEmpty() }) { "a changed path with an empty segment: ${change.path}" }
        val dir = parts.dropLast(1).fold(root) { dir, part -> dir.dirs.getOrPut(part) { Building(part) } }
        dir.files += ChangeFile(parts.last(), index, change)
    }
    return root.build()
}

/** [dir] with its only child folder merged into it; that child, built first, already holds its own chain. */
private fun compacted(dir: ChangeDir): ChangeDir =
    (dir.children.singleOrNull() as? ChangeDir)?.let { ChangeDir("${dir.name}/${it.name}", it.children) } ?: dir

/** The folder names that hold tests, by convention of Maven/Gradle (`src/test`), JavaScript (`__tests__`) and others (`tests`). */
private val TEST_FOLDERS = setOf("test", "tests", "__tests__")

/** Whether [dir] shows open until opened or closed by hand: every folder but one of tests, any of its merged names counting. */
fun openByDefault(dir: ChangeDir) = dir.name.split('/').none { it in TEST_FOLDERS }
