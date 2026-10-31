package dev.myair.actions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlin.io.path.createDirectories

const val MAX_RECENT_BASES = 5

/** Popup content: [items] in display order, separators above [recentStart] and [restStart], [selected] preselected. */
data class BaseChoices(val items: List<String>, val recentStart: Int, val restStart: Int, val selected: Int)

/** The default branch first, then the recent bases that still exist, then every other branch. */
fun baseChoices(branches: List<String>, default: String, recent: List<String>): BaseChoices {
    check(default in branches) { "default branch $default is not among the branches $branches" }
    val shownRecent = recent.filter { it != default && it in branches }
    val rest = branches - (shownRecent + default).toSet()
    val selected = if (recent.firstOrNull { it in branches }.let { it == null || it == default }) 0 else 1
    return BaseChoices(listOf(default) + shownRecent + rest, 1, 1 + shownRecent.size, selected)
}

/** [chosen] becomes the newest recent base; bases no longer among [branches] are dropped. */
fun pushRecent(recent: List<String>, chosen: String, branches: List<String>): List<String> =
    (listOf(chosen) + recent.filter { it != chosen && it in branches }).take(MAX_RECENT_BASES)

/**
 * The branch `origin/HEAD` points at ([originHead], e.g. `origin/main`); a repository without `origin` has no such
 * pointer, so its default is whichever one of `main` and `master` exists locally.
 */
fun defaultBranch(remotes: List<String>, originHead: String?, local: List<String>): String {
    if ("origin" in remotes) return checkNotNull(originHead) { "origin/HEAD is not set; run: git remote set-head origin -a" }
    val candidates = local.filter { it == "main" || it == "master" }
    check(candidates.size == 1) { "no origin remote and the local branches main/master found are $candidates: cannot tell the default branch" }
    return candidates.single()
}

/** `<base>-<date>-<first 4 of session [id]>`, the base reduced to letters and digits: it names both the worktree directory and its branch. */
fun worktreeName(base: String, date: LocalDate, id: String): String {
    val slug = base.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
    check(slug.isNotEmpty()) { "base branch '$base' has no letters or digits" }
    return "$slug-$date-${id.take(4)}"
}

/** A relative [setting] resolves against [mainRoot]; [Path.resolve] returns an absolute one unchanged. */
fun worktreePath(mainRoot: Path, setting: String, name: String): Path = mainRoot.resolve(setting).resolve(name)

/** `for-each-ref --format=%(refname)` output to short names, without the `<remote>/HEAD` pointers. */
fun parseBranches(refs: String): List<String> = refs.lines().filter { it.isNotEmpty() && !(it.startsWith("refs/remotes/") && it.endsWith("/HEAD")) }
    .map { it.removePrefix("refs/heads/").removePrefix("refs/remotes/") }

const val MAX_CONTAINED_BASES = 10

/** [parseBranches] of [refs] without [branch] and its copies on remotes, the first [MAX_CONTAINED_BASES] of them. */
fun containedBases(refs: String, branch: String): List<String> = parseBranches(
    refs.lines().filterNot { it == "refs/heads/$branch" || it.startsWith("refs/remotes/") && it.removePrefix("refs/remotes/").substringAfter('/') == branch }
        .joinToString("\n"),
).take(MAX_CONTAINED_BASES)

/** Copies [files], relative to [mainRoot], to the same relative paths in [worktree]. */
fun copyIncluded(mainRoot: Path, worktree: Path, files: List<String>) {
    for (file in files) {
        val target = worktree.resolve(file)
        target.parent.createDirectories()
        Files.copy(mainRoot.resolve(file), target, StandardCopyOption.COPY_ATTRIBUTES)
    }
}
