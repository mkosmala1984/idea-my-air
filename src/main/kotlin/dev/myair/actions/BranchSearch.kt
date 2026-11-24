package dev.myair.actions

private val TICKET_KEYS = Regex("""\b[A-Z][A-Z0-9]+-\d+\b""")
private val WORDS = Regex("[A-Za-z0-9]{3,}")

/**
 * A branch found by [findBranches]: [name] is its local name, [ref] the ref to check out (the local branch, else a remote
 * one), [worktree] the worktree holding it, [main] whether that is the main checkout.
 */
data class BranchMatch(val name: String, val ref: String, val worktree: Worktree?, val main: Boolean) {
    val remoteOnly get() = ref != name
}

/**
 * The branches of [branches] ([Git.repo] names; the ones starting with a name of [remotes] and `/` are remote) that [text]
 * names: with Jira issue keys in it, those whose name contains any key; else those containing every word of 3 or more
 * letters and digits. A local and a remote branch of one name are one match. [worktrees] is [Git.worktrees], the main
 * checkout first. Matches with a worktree come first, then by name.
 */
fun findBranches(text: String, branches: List<String>, remotes: Set<String>, worktrees: List<Worktree>): List<BranchMatch> {
    val bare = remotes.fold(text) { rest, remote -> rest.replace("$remote/", " ") }
    val keys = TICKET_KEYS.findAll(bare).map { it.value.lowercase() }.toList()
    val words = if (keys.isEmpty()) WORDS.findAll(bare).map { it.value.lowercase() }.toList() else emptyList()
    if (keys.isEmpty() && words.isEmpty()) return emptyList()
    val refs = branches.associateBy { ref -> remotes.firstOrNull { ref.startsWith("$it/") }?.let { ref.removePrefix("$it/") } ?: ref }
    // A local branch of the same name wins over the remote one.
    val local = branches.filter { ref -> remotes.none { ref.startsWith("$it/") } }.toSet()
    return refs.keys.filter { name ->
        val lower = name.lowercase()
        if (keys.isNotEmpty()) keys.any { it in lower } else words.all { it in lower }
    }.map { name ->
        val tree = worktrees.firstOrNull { it.branch == name }
        BranchMatch(name, if (name in local) name else refs.getValue(name), tree, tree != null && tree == worktrees.first())
    }.sortedWith(compareBy({ it.worktree == null }, { it.name }))
}
