# Worktrees Group Implementation Plan

## TL;DR

- **Goal:** in Folders mode every user folder bound to a git main checkout gets a `Worktrees` group listing that repository's linked worktrees, newest first, each row named `<dir name>  (<branch>)`; a worktree's menu starts a new session in it without touching its branch.
- **Approach:** `gitWorktrees` (already reading `.git/worktrees/*/gitdir`) also returns each admin directory's creation time and sorts by it; the panel reads them off the EDT with the branches; `foldersTree` adds `WorktreesGroup` → `WorktreeRow` nodes; `nodeDir` returns a worktree row's path, so the existing New Claude Session / Open in IDEA / Open in Terminal work on it.
- **Decided with you:** sessions working in a worktree show in both places (their folder as today, and under their worktree row); only user folders in Folders mode, not Projects mode nor `sessions`.
- **Assumed:** the main checkout is not listed (it is the folder itself); a bound directory that is itself a linked worktree has no group; "New Claude Session in Worktree..." is hidden on worktree rows (it would make another worktree); nothing drops into the group (it is a view, like `recent`).
- **Risk:** creation time is NTFS `creationTime` of `.git/worktrees/<name>`; `git worktree repair` or a copied `.git` resets it. A filter hides the group unless a session in it matches.
- **Out of scope:** removing/locking worktrees from the menu, Projects mode, a separate worktree list in Session Context.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a `Worktrees` group under every bound user folder whose directory is a git main checkout, its linked worktrees newest first with their branch and their sessions, and New Claude Session on a worktree row starting in it.

**Architecture:** Discovery stays file-based (`gitWorktrees` in `service/SessionProjects.kt`, no `git` process). The tree builder stays pure and testable (`foldersTree` takes a `worktreesOf` function); the panel feeds it from a map read on a pooled thread in `readBranches`, which already re-renders when what it read changes.

**Tech Stack:** Kotlin, IntelliJ Platform 263 Swing tree, JUnit 5.

**Spec:** this request (2026-11-08): "do folderów z projektem git specjalny folder z wszystkimi worktrees (w nazwie daje też aktualny branch) posortowane po dacie utworzenia od najnowszego na górze, dodatkowo opcja menu na worktree do utworzenia nowej sesji w folderze worktree bez zmiany brancha", plus the two answers above.

## Global Constraints

- Code, comments, strings, test names in English; test names are backtick sentences.
- No fallbacks: a broken `gitdir` or unreadable attribute throws, as `gitWorktrees` does today.
- No IO on the EDT: worktrees, their branches and existence are read in `SessionsPanel.readBranches`'s pooled task.
- Classes named in `plugin.xml` unchanged; no new action class (reuse `NewSessionInDirAction`).

## Review Focus

1. A session in a worktree is listed under its folder and under its worktree row: the folder's count must not count it twice (`folderSummary` dedupes by id) — Task 2 test.
2. A worktree whose directory was deleted but not pruned: row grey italic, New Claude Session disabled (existing `isDirectory` check) — Task 3 via `gone`, Task 2 keeps the row.
3. Dropping a session onto the Worktrees group or a worktree row must be refused, not file it into the folder — Task 2 test.
4. A folder bound to two repositories: one group, all their worktrees merged newest first — Task 2 test.
5. Search filter: group kept only with a matching session inside, as other groups — Task 2 test.

---

| Task | Status | Commit |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |

### Task 1: Worktrees with their creation time

**Files:**
- Modify: `src/main/kotlin/dev/myair/service/SessionProjects.kt`
- Test: `src/test/kotlin/dev/myair/service/SessionProjectsTest.kt`

**Interfaces:**
- Produces: `data class LinkedWorktree(val path: Path, val created: Instant)`; `fun gitWorktrees(root: Path): List<LinkedWorktree>` sorted by `created` descending. `inProjects` uses `.path`.

- [ ] **Step 1: Write the failing test** in `SessionProjectsTest`, reusing `repo(...)`:

```kotlin
@Test
fun `worktrees come newest first by when git made their admin directory`() {
    val old = root.resolve("w/old"); val new = root.resolve("w/new")
    val app = repo(root.resolve("app"), old, new)
    fun created(name: String, at: Instant) = Files.getFileAttributeView(app.resolve(".git/worktrees/$name"), BasicFileAttributeView::class.java)
        .setTimes(null, null, FileTime.from(at))
    created("old", Instant.parse("2026-01-01T00:00:00Z")); created("new", Instant.parse("2026-02-01T00:00:00Z"))
    assertEquals(
        listOf(LinkedWorktree(new, Instant.parse("2026-02-01T00:00:00Z")), LinkedWorktree(old, Instant.parse("2026-01-01T00:00:00Z"))),
        gitWorktrees(app),
    )
}
```

- [ ] **Step 2:** `./gradlew test --tests dev.myair.service.SessionProjectsTest` → FAIL (unresolved `LinkedWorktree`).
- [ ] **Step 3: Implement.** Map each admin dir having `gitdir` to `LinkedWorktree(Path.of(gitdir text).parent, readAttributes<BasicFileAttributes>(admin).creationTime().toInstant())`, `sortedByDescending { it.created }`; update the KDoc; `inProjects` maps `.path`.
- [ ] **Step 4:** same command → PASS (all four tests).
- [ ] **Step 5: Commit** `Read when each linked worktree was made` with trailers `Plan-Task: 1/3` and `Plan: docs/superpowers/plans/2026-11-08-worktrees-group.md`.

### Task 2: Worktrees group in the Folders tree

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt`
- Test: `src/test/kotlin/dev/myair/toolwindow/SessionTreeTest.kt`

**Interfaces:**
- Consumes: `LinkedWorktree` (Task 1).
- Produces:
  - `class WorktreesGroup(val folder: Folder)`, `toString() = "worktrees ${folder.id}"`.
  - `data class WorktreeRow(val path: Path, val branch: String?, val created: Instant)`, `toString() = "worktree $path"`.
  - `foldersTree(structure, sessions, branchOf, filter, worktreesOf: (Path) -> List<WorktreeRow>)` — new last parameter; existing callers in tests pass `NO_WORKTREES = { emptyList() }`.
  - `nodeDir`: `is WorktreeRow -> item.path`.

Behaviour of `folderNode(folder)`: children are subfolders, then — when `folder.dirs.flatMap { worktreesOf(Path.of(it)) }` is non-empty — `WorktreesGroup(folder)` holding those rows sorted by `created` descending, each holding (as `sessionNode`) every session of `shown` whose `workDir` or `cwd` `startsWith` its path, wherever it is filed; then the folder's own sessions / plan nodes as today. Under a filter, a row with no session is dropped and the group with no row (use `keep`). `folderSummary`: `WorktreesGroup`, `WorktreeRow` are groups; the count is `sessions.distinctBy { it.id }.size`. `dropChange`: return null when `path` contains a `WorktreesGroup` (next to the `RecentSessions` check).

- [ ] **Step 1: Write the failing tests**, structure `SessionFolders().createFolder(null, "app", "app", listOf("/app"))`, sessions `a1` cwd `/app/.claude/worktrees/x`, `a2` cwd `/app`, `worktreesOf = { if (it == Path.of("/app")) listOf(WorktreeRow(Path.of("/app/.claude/worktrees/x"), "feat", Instant.ofEpochMilli(1)), WorktreeRow(Path.of("/w/y"), null, Instant.ofEpochMilli(2))) else emptyList() }`:
  - `a bound folder lists its worktrees newest first, each with the sessions working in it, which also stay in the folder` → `shape` equals `"app(worktrees app(worktree ${Path.of("/w/y")}, worktree ${Path.of("/app/.claude/worktrees/x")}(a1)), a1, a2), recent(a1, a2), sessions"` (adjust ids/time so a1 is newest).
  - `a folder without worktrees has no worktrees group` → existing `structure` with `NO_WORKTREES` gives the unchanged shape of the first test.
  - `a folder bound to two repositories merges their worktrees newest first` → two dirs, rows interleave by `created`.
  - `a filter keeps a worktree only with a matching session` → filter matching `a2` only: `"app(a2), recent(a2), sessions"`.
  - `a session shown in its folder and its worktree counts once` → `folderSummary(appNode) { null }!!.sessions == 2`.
  - `nothing drops into the worktrees group` → `dropChange(structure, listOf(a2), pathToWorktreeRow, -1)` is null and to the group is null.
  - `a worktree row starts sessions in its directory` → `nodeDir(pathToWorktreeRow) == Path.of("/app/.claude/worktrees/x")`.
- [ ] **Step 2:** `./gradlew test --tests dev.myair.toolwindow.SessionTreeTest` → FAIL (compile).
- [ ] **Step 3: Implement** the interfaces above in `SessionTree.kt`; update `foldersTree`'s KDoc.
- [ ] **Step 4:** same command → PASS, then `./gradlew test` → PASS.
- [ ] **Step 5: Commit** `List a bound folder's worktrees in the tree` with `Plan-Task: 2/3` and `Plan:` trailers.

### Task 3: Panel reads worktrees, renders rows, menu on a worktree

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt` (`readBranches`, `render`, popup group)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionRenderers.kt`
- Modify: `CLAUDE.md` (Folders paragraph: the Worktrees group, its order, duplication, menu)

**Interfaces:**
- Consumes: `gitWorktrees` (Task 1), `WorktreesGroup`, `WorktreeRow`, new `foldersTree` parameter (Task 2).

- [ ] **Step 1: Read them off the EDT.** In `readBranches`, capture on the EDT `val bound = service.folders.folders.flatMap { it.subtree() }.flatMap { it.dirs }.map(Path::of).toSet()`; in the pooled task compute `val trees = bound.associateWith { gitWorktrees(it).map { w -> WorktreeRow(w.path, Git.headBranch(w.path), w.created) } }` and add every worktree path to the existence check feeding `gone`. A new field `worktrees: Map<Path, List<WorktreeRow>>` joins the "changed → render" comparison. `render` passes `{ worktrees[it].orEmpty() }` to `foldersTree`.
- [ ] **Step 2: Render.** `WorktreesGroup` → `d.grow(AllIcons.Nodes.Folder)`, text `Worktrees` in `GRAY_ITALIC_ATTRIBUTES`, as `recent`. `WorktreeRow` → `d.grow(AllIcons.Vcs.Branch)`, `append(path.fileName)` (grey italic when `isGone(path)`), then `"  (${branch})"` grayed when `branch != null`; tooltip `"<path>\nCreated <created, local date-time>"` plus ` (folder no longer exists)` when gone. Session rows under a `WorktreeRow` render as under `recent` (`projectInRow = true`) so the project shows.
- [ ] **Step 3: Menu.** "New Claude Session" needs nothing (Task 2's `nodeDir`). Give "New Claude Session in Worktree..." a dir provider that returns null when the selected node's `userObject is WorktreeRow`, so it is hidden there.
- [ ] **Step 4: Verify.** `./gradlew test buildPlugin` → BUILD SUCCESSFUL. Then the sandbox (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`), Folders mode, folder bound to `E:/dev/projects/my-air` after `git worktree add` of two trees a minute apart: group shows both, newer on top, branch in brackets; one session started from a worktree row (New Claude Session) runs with `git -C <worktree> branch --show-current` unchanged and appears under the folder and under the row; delete one worktree's directory → row grey italic, New Claude Session disabled. Record in the commit message what was checked.
- [ ] **Step 5: Update `CLAUDE.md`** (Folders/plan folders paragraph) with the group, ordering by admin-dir creation time, duplication, refused drops, the menu.
- [ ] **Step 6: Commit** `Show worktrees under their bound folder and start sessions there` with `Plan-Task: 3/3` and `Plan:` trailers.
