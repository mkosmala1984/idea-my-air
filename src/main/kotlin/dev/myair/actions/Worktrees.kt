package dev.myair.actions

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
fun worktreeName(base: String, date: LocalDate, id: String): String = "${dirName(base)}-$date-${id.take(4)}"

/** [branch] reduced to letters, digits and hyphens: the directory of a worktree on it. */
fun dirName(branch: String): String {
    val slug = branch.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
    check(slug.isNotEmpty()) { "branch '$branch' has no letters or digits" }
    return slug
}

/** A relative [setting] resolves against [mainRoot]; [Path.resolve] returns an absolute one unchanged. */
fun worktreePath(mainRoot: Path, setting: String, name: String): Path = mainRoot.resolve(setting).resolve(name)

/** `for-each-ref --format=%(refname)` output to short names, without the `<remote>/HEAD` pointers. */
fun parseBranches(refs: String): List<String> = refs.lines().filter { it.isNotEmpty() && !(it.startsWith("refs/remotes/") && it.endsWith("/HEAD")) }
    .map { it.removePrefix("refs/heads/").removePrefix("refs/remotes/") }

/**
 * The branches of [refs], lines `<refname> <ahead> <behind>` of `%(ahead-behind:HEAD)` newest first, without [branch]
 * and its copies on remotes, nearest first: fewest commits of HEAD they lack (a fork point closer to HEAD), then fewest
 * of their own. A base that moved on since the fork lacks no more than it did, so it stays near the top.
 */
fun nearestBases(refs: String, branch: String): List<String> = parseBranches(
    refs.lines().filter { it.isNotEmpty() }.map { line ->
        val fields = line.split(' ')
        check(fields.size == 3) { "unexpected git for-each-ref line: $line" }
        Triple(fields[0], fields[2].toInt(), fields[1].toInt())
    }.filterNot { (ref) -> ref == "refs/heads/$branch" || ref.startsWith("refs/remotes/") && ref.removePrefix("refs/remotes/").substringAfter('/') == branch }
        .sortedWith(compareBy({ it.second }, { it.third }))
        .joinToString("\n") { it.first },
)

/** An entry of `git worktree list --porcelain`; [branch] is null on a detached HEAD. */
data class Worktree(val path: Path, val branch: String?, val locked: Boolean)

/** `git worktree list --porcelain` output: blocks separated by an empty line, each opening with `worktree <path>`; the main checkout first. */
fun parseWorktrees(porcelain: String): List<Worktree> = porcelain.trim().split(Regex("\n\n+")).filter { it.isNotEmpty() }.map { block ->
    val lines = block.lines()
    check(lines.first().startsWith("worktree ")) { "unexpected git worktree list entry: $block" }
    Worktree(
        Path.of(lines.first().removePrefix("worktree ")),
        lines.firstOrNull { it.startsWith("branch refs/heads/") }?.removePrefix("branch refs/heads/"),
        lines.any { it == "locked" || it.startsWith("locked ") },
    )
}

/**
 * Answers "Continue without using this MCP server" for [worktree] in advance: `claude` reads `.mcp.json` from its cwd
 * and every directory above, and asks about each server not yet in `enabledMcpjsonServers` or `disabledMcpjsonServers`
 * of the cwd's `.claude/settings.local.json`, which a new worktree lacks (gitignored) or has as copied by
 * `.worktreeinclude`. Every such undecided server is added to `disabledMcpjsonServers`; decided ones stay as they are.
 */
fun declineProjectMcpServers(worktree: Path) {
    val servers = generateSequence(worktree.toAbsolutePath()) { it.parent }.map { it.resolve(".mcp.json") }.filter { it.exists() }
        .flatMap { JsonParser.parseString(it.readText()).asJsonObject.getAsJsonObject("mcpServers")?.keySet().orEmpty() }.toSet()
    val file = worktree.resolve(".claude/settings.local.json")
    val settings = if (file.exists()) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
    fun names(key: String) = settings.getAsJsonArray(key)?.map { it.asString }.orEmpty()
    val undecided = servers - (names("enabledMcpjsonServers") + names("disabledMcpjsonServers")).toSet()
    if (undecided.isEmpty()) return
    settings.add("disabledMcpjsonServers", JsonArray().apply { (names("disabledMcpjsonServers") + undecided.sorted()).forEach(::add) })
    file.parent.createDirectories()
    file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(settings))
}

/** Copies [files], relative to [mainRoot], to the same relative paths in [worktree]. */
fun copyIncluded(mainRoot: Path, worktree: Path, files: List<String>) {
    for (file in files) {
        val target = worktree.resolve(file)
        target.parent.createDirectories()
        Files.copy(mainRoot.resolve(file), target, StandardCopyOption.COPY_ATTRIBUTES)
    }
}
