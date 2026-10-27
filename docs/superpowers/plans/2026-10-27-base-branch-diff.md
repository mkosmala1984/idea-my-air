# Base Branch Diff Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `5058152` |
| 2 | done | `c9501d5` |
| 3 | done | `bb01116` |
| 4 | done | `f2245d2` |
| 5 | done | `e6fc462` |

## TL;DR

- **Goal:** the context pane and the session menu show what a session's branch changed against the branch it started from (its base): `+N −M`, a file list, and the IDE's diff window.
- **Approach:** the base is stored per branch in the repository's own config, `branch.<branch>.myAirBase`, written by the one place that creates worktrees and by a pick in the pane; without it the base is the default branch (`origin/HEAD`, else the local `main`/`master`). The diff is the working tree against `git merge-base <base> HEAD`, plus untracked files counted as added. Shown with the platform `DiffManager`, contents from `git show`; no Git4Idea dependency.
- **Decided (2026-10-27, see `.claude/decisions/2026-10-27-ae808871-66ab-4506-8437-68c09c5c8f34.md`):** everything above; a stored base that no longer exists shows an error and a picker, never a silent switch to the default; detached HEAD uses the default branch and cannot pick.
- **Needs your input:** none.
- **Risks:** an untracked, not ignored huge file is read whole to count its lines; `git config` writes into the user's `.git/config` (local, not committed); `/clear` in another IDE does not refresh this pane's base until the session changes.
- **Out of scope:** reading the real base of `claude --worktree` and Desktop sessions from the branch reflog ("Created from origin/main"); renames shown as renames (`--no-renames`: a delete and an add); counts in session rows; staging or committing from the diff.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a diff of a session's branch against its base, in the context pane and from the session menu.

**Architecture:** `Git` (in `actions/Git.kt`) gains base storage and a richer `GitState` with a file list against the merge base; `SessionContextPanel` shows the base in Environment and the files in Changes; a new `actions/BaseDiff.kt` opens the diff window from a `GitState` and holds the session-menu action.

**Tech Stack:** Kotlin, IntelliJ Platform (UI DSL v2, `com.intellij.diff`: `DiffManager`, `DiffContentFactory.createFromBytes(Project, ByteArray, FileType, String)`, `SimpleDiffRequestChain.fromProducers(List, Int)`, `DiffRequestProducer`), JUnit 5, real `git`.

**Spec:** the decision register named in the TL;DR and this conversation's grilling summary.

## Global Constraints

- Code, comments, strings in English. No fallbacks: any failing `git` call throws with its stderr (`Git.run`); unknown `--name-status` letters throw.
- `git` never runs on the EDT.
- Tests are plain JUnit 5 with real `git` (`GitTest`), no platform services. Logic that needs tests stays out of Swing code.
- Config key: `branch.<branch>.myAirBase`, value a ref name (`origin/main`), never a SHA.
- Every commit ends with `Plan-Task: N/5`, `Plan: docs/superpowers/plans/2026-10-27-base-branch-diff.md`, then the `Co-Authored-By` line, one trailer block.

## Review Focus

1. A session started from a subdirectory of the repository: paths are relative to the work tree root and untracked files outside the cwd still count. Test in Task 2.
2. The base moved on after the branch was cut (`fetch` brought new commits to `origin/main`): those commits are not in the diff. Test in Task 2.
3. Renaming the worktree branch keeps its base (`git branch -m` moves the config section). Test in Task 1.
4. A binary file, changed or untracked: listed with 0/0, opens as a binary diff. Test in Task 2 (`lineCount`).
5. The stored base deleted: Environment still shows branch and base, Changes shows `Base <name> no longer exists`. Test in Task 2 (`changes == null`), rendering checked by hand in Task 3.

---

### Task 1: Store the base of a branch

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/Git.kt` (`addWorktree`, new `setBase`, `storedBase`)
- Test: `src/test/kotlin/dev/myair/actions/GitTest.kt`

**Interfaces:**
- Produces: `Git.setBase(dir: Path, branch: String, base: String)`, `Git.storedBase(dir: Path, branch: String): String?` (null when unset; `git config --get` exits 1), `addWorktree` now also calls `setBase(mainRoot, branch, base)`.

- [x] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a new worktree branch stores its base`() {
    val repo = clone()
    Git.addWorktree(repo, worktreePath(repo, ".claude/worktrees", "ab12cd34"), "worktree-ab12cd34", "origin/feature")
    assertEquals("origin/feature", Git.storedBase(repo, "worktree-ab12cd34"))
}

@Test
fun `a branch without a stored base has none, and a set one survives a rename`() {
    val repo = init("plain")
    git(repo, "branch", "topic")
    assertEquals(null, Git.storedBase(repo, "topic"))
    Git.setBase(repo, "topic", "main")
    git(repo, "branch", "-m", "topic", "renamed")
    assertEquals("main", Git.storedBase(repo, "renamed"))
}
```

- [x] **Step 2: Run** `./gradlew test --tests dev.myair.actions.GitTest` — expected: compilation fails on `storedBase`.
- [x] **Step 3: Implement** `setBase` (`git config branch.<b>.myAirBase <base>`), `storedBase` (`run(..., "config", "--get", key, ok = setOf(0, 1)).trim().ifEmpty { null }`), and the `setBase` call at the end of `addWorktree`; update `addWorktree`'s KDoc.
- [x] **Step 4: Run** the same command — expected: all `GitTest` tests pass.
- [x] **Step 5: Commit** `Store the base of a worktree branch in its git config`.

### Task 2: Diff a work tree against its base

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/Git.kt` (`GitState`, `state`, new `FileChange`, `lineCount`, `show`)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionContextPanel.kt:163-168` (only to compile: `git.added`/`git.removed` become properties)
- Test: `src/test/kotlin/dev/myair/actions/GitTest.kt`

**Interfaces:**
- Consumes: `Git.storedBase` (Task 1), `defaultBranch` via `Git.repo(dir).default`.
- Produces:

```kotlin
data class FileChange(val path: String, val kind: Kind, val added: Int, val removed: Int) {
    enum class Kind { ADDED, MODIFIED, DELETED, UNTRACKED }
}
/**
 * [root]: the work tree root, [FileChange.path] is relative to it. [base]: the stored base, else the default branch;
 * null only without commits. [changes]: null when [base] names no commit; [mergeBase] null then and without commits.
 */
data class GitState(
    val branch: String, val detached: Boolean, val root: Path, val base: String?, val baseStored: Boolean,
    val mergeBase: String?, val changes: List<FileChange>?,
) {
    val added get() = changes.orEmpty().sumOf { it.added }
    val removed get() = changes.orEmpty().sumOf { it.removed }
}
fun lineCount(bytes: ByteArray): Int     // 0 for a binary file (a NUL in the first 8000 bytes, git's test)
fun Git.show(root: Path, commit: String, path: String): ByteArray   // `git show <commit>:<path>` bytes
```

- [x] **Step 1: Write the failing tests.** Replace the four existing `state ...` tests that assert `GitState(...)` equality with field assertions; keep the null and broken-repository ones. New tests, each asserting `changes` as a list of `FileChange`:

```kotlin
@Test
fun `a worktree diffs against the merge base with its stored base, untracked files included`() {
    // clone(), addWorktree off origin/feature; in the worktree commit a.txt "1\n2\n", then write a.txt "1\n2\n3\n"
    // and an untracked new.txt "x\ny\n"
    // assert: branch "worktree-ab12cd34", detached false, base "origin/feature", baseStored true,
    // mergeBase == rev-parse origin/feature,
    // changes == [FileChange("a.txt", ADDED, 3, 0), FileChange("new.txt", UNTRACKED, 2, 0)]
}

@Test
fun `without a stored base the default branch is the base`()            // clone(); checkout -b topic; base "origin/main", baseStored false

@Test
fun `commits the base got after the branch was cut are not changes`()
// clone(); checkout -b topic; commit t.txt; commit u.txt in upstream main; fetch -> changes == [t.txt ADDED]

@Test
fun `a stored base that names no commit leaves changes null`()          // setBase(repo, "topic", "gone"): base "gone", changes null, mergeBase null

@Test
fun `uncommitted edits and deletions count against the merge base`()
// the old "names its branch and counts uncommitted lines" case: a.txt MODIFIED 3/1; plus git rm'd b.txt "1\n2\n" -> DELETED 0/2

@Test
fun `a detached head diffs against the default branch`()                // detached true, base "main"

@Test
fun `a repository without commits lists its files as untracked`()       // base null, mergeBase null, changes = untracked files

@Test
fun `from a subdirectory paths are relative to the root and untracked files outside it count`()

@Test
fun `lines are counted as git counts them and a binary file has none`() {
    assertEquals(0, lineCount(byteArrayOf()))
    assertEquals(1, lineCount("a".toByteArray()))
    assertEquals(1, lineCount("a\n".toByteArray()))
    assertEquals(2, lineCount("a\nb".toByteArray()))
    assertEquals(0, lineCount(byteArrayOf(97, 0, 10)))
}
```

- [x] **Step 2: Run** `./gradlew test --tests dev.myair.actions.GitTest` — expected: compilation fails on `FileChange`.
- [x] **Step 3: Implement.** In `state`, after the existing repository checks: `root` from `rev-parse --show-toplevel`; every later call runs in `root`. Base: `storedBase(root, branch)` when not detached, else `Git.repo(root).default`. Commit check: `rev-parse --verify -q <base>^{commit}` blank → `changes = null`. Tracked changes: `diff --no-renames -z --name-status <mergeBase>` for the kind (`A` ADDED; `M`, `T`, `U` MODIFIED; `D` DELETED; anything else throws) joined by path with `diff --no-renames -z --numstat <mergeBase>`; untracked: `ls-files -z --others --exclude-standard` with `lineCount` of each file. Order: tracked by path, then untracked by path. Keep `--no-optional-locks` on every read. `show` needs `run` split into a `ByteArray` variant that `run` decodes.
- [x] **Step 4: Fix the compile in `SessionContextPanel`** (the counts are now properties; no behaviour change yet).
- [x] **Step 5: Run** `./gradlew test` — expected: all tests pass.
- [x] **Step 6: Commit** `Diff a session's work tree against the merge base with its base branch`.

### Task 3: Show the base and the changed files in the context pane

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/NewClaudeSessionInWorktreeAction.kt` (extract the popup)
- Create: `src/main/kotlin/dev/myair/actions/BaseDiff.kt`
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionContextPanel.kt` (Environment branch row, Changes section, re-read after a pick)

**Interfaces:**
- Consumes: `GitState`, `FileChange`, `Git.show`, `Git.setBase`, `Git.repo`, `recentBases`, `baseChoices`.
- Produces:
  - `fun basePopup(title: String, choices: BaseChoices, chosen: (String) -> Unit): ListPopup` in `NewClaudeSessionInWorktreeAction.kt`, used by `startInWorktree` (behaviour unchanged) and the pane.
  - `fun showBaseDiff(project: Project, state: GitState, selected: Int)` in `BaseDiff.kt`: `DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain.fromProducers(producers, selected), DiffDialogHints.FRAME)`, one `DiffRequestProducer` per `FileChange` (name: its path). Its `process` (off the EDT, the platform's contract) builds a `SimpleDiffRequest` titled by the path with sides `"<base> (merge base)"` and `"Working tree"`: left `createFromBytes(project, Git.show(root, mergeBase, path), fileType, path)`, `createEmpty()` for ADDED and UNTRACKED; right the file read from disk, `createEmpty()` for DELETED. File type from `FileTypeManager.getFileTypeByFileName`. Requires `state.changes != null`.

- [x] **Step 1: Extract `basePopup`** from `startInWorktree` and run `./gradlew test` — expected: pass (no behaviour change).
- [x] **Step 2: Write `BaseDiff.kt`** with `showBaseDiff` as above.
- [x] **Step 3: Environment row:** after the branch label, `←` and the base. Not detached: a link that reads `Git.repo(cwd)` and `recentBases` on `gitReader`, then shows `basePopup("Base Branch of <branch>", baseChoices(...), …)` under the link; the choice runs `Git.setBase(cwd, branch, it)` on `gitReader`, then sets `readFor = null` and calls `update()`. Not stored: the link text ends with ` (default)`. Detached: the base as a comment, no link. Base null (no commits): no base part.
- [x] **Step 4: Changes section:** `changes == null` → one comment row `Base <base> no longer exists`. Otherwise the counts row becomes a link opening `showBaseDiff(project, state, 0)` with the tooltip `Changes against <base>`, then a row per `FileChange` paged by `FILES_SHOWN` under the key `"Changes"` like `files(...)`: the path as a link opening `showBaseDiff(project, state, index)`, coloured by `FileStatus.ADDED`/`MODIFIED`/`DELETED`/`UNKNOWN` (untracked), and `+a −r` as a comment. Generalise `files(...)` so both lists share the paging rather than copying it.
- [ ] **Step 5: Verify by hand** (left to the user) in the sandbox (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde` after `./gradlew buildPlugin`): a session started in a new worktree shows `worktree-<id8> ← origin/main`; edit a file there and the counts and list update on the next refresh; clicking a file opens the diff on that file and the arrows walk the others; picking another base rewrites `git config branch.worktree-<id8>.myAirBase`; setting it to `gone` by hand shows `Base gone no longer exists`; a session in the main checkout shows `main ← origin/main (default)`.
- [x] **Step 6: Run** `./gradlew test` — expected: pass.
- [x] **Step 7: Commit** `Show a session's base branch and its changed files in the context pane`.

### Task 4: Show Changes vs Base on the session menu

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/BaseDiff.kt` (new action)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt:172,191` (both menus, after `SaveAsPresetAction`)

**Interfaces:**
- Consumes: `showBaseDiff`, `Git.state`.
- Produces: `class ShowChangesVsBaseAction(private val selected: () -> List<ClaudeSession>)`, shaped like `SameSettingsAction`: "Show Changes vs Base", `AllIcons.Actions.Diff`, visible for one selected session, enabled when its `cwd` exists.

- [x] **Step 1: Implement:** `Task.Backgroundable` "Reading changes" runs `Git.state(session.cwd)`; on success: null → error dialog `<cwd> is not in a git repository`; `changes == null` → `Base <base> no longer exists`; empty → info `No changes against <base>`; else `showBaseDiff(project, state, 0)`. A thrown failure shows its message, as `background` in `NewClaudeSessionInWorktreeAction.kt` does.
- [x] **Step 2: Register** in both menus.
- [ ] **Step 3: Verify by hand** (left to the user) in the sandbox: on a finished worktree session with no tab, the menu entry opens the diff; on a session whose folder was removed the entry is disabled.
- [x] **Step 4: Run** `./gradlew test buildPlugin` — expected: pass, zip built.
- [x] **Step 5: Commit** `Open a session's changes against its base from the session menu`.

### Task 5: Documentation

**Files:**
- Modify: `CLAUDE.md` (the worktree bullet: the base stored in `branch.<b>.myAirBase`; the `SessionContextPanel` bullet: Environment base, Changes against the merge base with untracked files, the diff window, the menu action)
- Modify: `docs/ideas.md` (drop "diff zmian wykonanych na branchu względem początkowego brancha" and "dodanie do panelu envionment brancha początkowego")

- [x] **Step 1: Edit both files**, matching the style of the surrounding text (one dense paragraph per bullet).
- [x] **Step 2: Commit** `Describe the base branch diff`.
