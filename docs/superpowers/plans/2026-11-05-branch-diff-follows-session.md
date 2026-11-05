# Branch Diff Follows the Active Session Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `a66d3bc` (idea-branch-diff, branch `follow-session`) |
| 2 | done | `7142e35` (idea-branch-diff) |
| 3 | done | `1a973c1` (idea-branch-diff) |
| 4 | done (manual check moved to Task 8) | `3cffa93` (idea-branch-diff) |
| 5 | done (visual check in Task 8) | `c7c1bb0` (idea-branch-diff) |
| 6 | | |
| 7 | | |
| 8 | | |

## TL;DR

- **Goal:** the Branch Diff tool window (plugin `idea-branch-diff`, `E:\dev\projects\idea-branch-diff`) shows the diff of the work tree of the active Claude session (the selected session tab, kept while another file is selected and the tab stays open, as in Session Context) against that session's base, and switches when another session tab is selected. With no session tab it shows the project repository against its own chosen base, as today.
- **Approach:** Branch Diff gains a `DiffTarget(root, base, label)` and an extension point `diffTargetProvider`; it knows nothing of My Air. My Air implements the extension point in an optional `config-file`, handing over the session's `workDir` root and the base it already resolves (`branch.<b>.myAirBase`, else the default branch). The dependency goes this way because My Air is built on Java 25 / IU-262 (class version 69, checked), Branch Diff on Java 21 / 2026.1: Branch Diff could not compile against My Air.
- **The core change in Branch Diff:** it reads only `repositories.firstOrNull()` and `ChangeListManager`. A session worktree is not a VCS root (checked: `get_repositories` on my-air lists only the main checkout, not `.claude/worktrees/orchestrator-spec`), so uncommitted and untracked files have to come from git in the target root: `GitChangeUtils.getDiffWithWorkingDir(project, root, "HEAD", null, false)` and `git ls-files --others --exclude-standard -z`. This replaces `ChangeListManager` in project mode too: one code path.
- **Decided (2026-11-05): the recommendations below.** Were (recommendation first):
  1. "the last branch" = the session's **base**: `myAirBase` stored for its branch, else the default branch (the same one Session Context shows). Alternative: `startBranch`, the branch the session started on.
  2. The base picker while following a session **writes `myAirBase`** (`Git.setBase`), so Branch Diff and Session Context agree. Alternative: picker disabled in that mode.
  3. A "Follow Claude Session" toolbar toggle, **on by default**, shown only when a provider is installed.
  4. Gutter markers **follow the target** (they mark files under the session's root). Alternative: they stay on the project repository.
  5. Session Context keeps its Changes section (list, Show diff, Tree, Plannotator). Branch Diff adds the persistent tree, timeline, counts and gutter. Recommendation: keep both now, decide on removing duplicates later.
- **Risks:** project mode loses `ChangeListManager` (changelists, IDE-ignored files) and runs `git diff` on each refresh; a target outside the content roots needs `LocalFileSystem.addRootToWatch` for VFS events; the My Air sandbox must load the Branch Diff zip (`localPlugin`), so changes in Branch Diff need its `buildPlugin` first.
- **Out of scope:** removing Session Context's Changes, several sessions at once, a session of a repository without commits (Branch Diff shows its "no merge-base" message).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox syntax. Tasks 1–5 are in `E:\dev\projects\idea-branch-diff`, tasks 6–8 in `E:\dev\projects\my-air`.

**Architecture:**

```
idea-branch-diff                                   my-air (optional depends on dev.kosmala.branch-diff-tree)
 service/DiffTarget.kt                              service/ActiveSession.kt   (project service: the active session tab)
   data class DiffTarget(root: Path, base, label)   branchdiff/SessionDiffTargetProvider.kt
   interface DiffTargetProvider (EP)        <----     implements DiffTargetProvider
   BranchDiffTargets (project service):             META-INF/my-air-branch-diff.xml
     current(): DiffTarget, TARGET_CHANGED topic
 GitDiffService.computeDiff(target)
 Panel / Gutter / BaseContentProvider / Plannotator take target.root
```

```kotlin
// idea-branch-diff
data class DiffTarget(val root: Path, val base: String, val label: String?)  // label null: the project's own repository

interface DiffTargetProvider {
    /** The target to show now, or null when there is none (no session tab). Called off the EDT: it may run git. */
    fun target(project: Project): DiffTarget?
    /** Calls [onChange] whenever [target] may return something else. */
    fun subscribe(project: Project, parent: Disposable, onChange: () -> Unit)
    /** Stores [base] as the base of [target]; [subscribe]'s callback then fires. */
    fun setBase(project: Project, target: DiffTarget, base: String)
    companion object { val EP = ExtensionPointName<DiffTargetProvider>("dev.kosmala.branch-diff-tree.diffTargetProvider") }
}
```

## Global Constraints

- Code, comments, strings in English. No fallbacks: two providers registered throws; a target root that is not a git work tree throws with git's stderr; `computeDiff` failures go to the panel's existing error label.
- Git runs off the EDT (`assertIsNonDispatchThread` stays); UI changes on the EDT.
- Project mode is a mode, not a fallback: follow off, or no provider, or the provider returns null.
- Commits in each repository end with `Plan-Task: N/8`, `Plan: docs/superpowers/plans/2026-11-05-branch-diff-follows-session.md` (in idea-branch-diff: `Plan: E:/dev/projects/my-air/docs/superpowers/plans/2026-11-05-branch-diff-follows-session.md`), then the `Co-Authored-By` line.

## Task 1: `DiffTarget`, extension point, `BranchDiffTargets` (idea-branch-diff)

**Files:** `service/DiffTarget.kt` (new), `service/BranchDiffSettings.kt`, `META-INF/plugin.xml`, test `service/DiffTargetTest.kt`

- [x] Test the pure parts: `singleProvider(providers)` (none → null, one → it, two → throws) and `resolveTarget(follow, provider, provide, projectTarget)`: follow off → project target without asking the provider; no provider → same; provider returns null → same; provider returns a target → that target.
- [x] `plugin.xml`: `<extensionPoints><extensionPoint name="diffTargetProvider" interface="dev.kosmala.branchdifftree.service.DiffTargetProvider"/></extensionPoints>` (not dynamic: installing My Air restarts the IDE anyway).
- [x] `BranchDiffSettings.followSession: Boolean = true`.
- [x] `BranchDiffTargets` (project service): `current(): DiffTarget` (off the EDT) through `resolveTarget`, the project root being `repositories.single().root` (the only place left that asks `GitRepositoryManager`; zero roots throws "No Git repository in project", the message the panel already maps, several throw); `TARGET_CHANGED` project topic fired by `changed()`, which the provider's `subscribe` calls (the follow toggle will too, Task 5).
- [x] `./gradlew test buildPlugin`: 8 test classes, 0 failures, `DiffTargetTest` 5/5; commit.

## Task 2: `computeDiff(target)` from git, not `ChangeListManager` (idea-branch-diff)

**Files:** `service/GitDiffService.kt`, `service/DiffMerger.kt` (signature only if needed), test `service/GitDiffServiceWorktreeTest.kt`

- [x] Test `GitDiffServiceWorktreeTest` (`BasePlatformTestCase`, real `git` in a temp dir, service calls on a pooled thread since it asserts off-EDT, `VfsRootAccess.allowRootAccess` for the temp dir): a repository on `main`; a linked worktree `wt` on `feat`, not a VCS root of the light project; in it a committed change to `a.txt`, an uncommitted edit of `b.txt`, untracked `c.txt`, ignored `d.log`. `computeDiff(DiffTarget(wt, "main", "session"))` returns exactly `a.txt` MODIFIED committed, `b.txt` MODIFIED uncommitted, `c.txt` ADDED uncommitted; `currentBranch` `feat`; the main checkout has no change, branch `main`. Second test: `listRecentBranches(root)` lists `main` and `other`. Mutation check: with `getDiffWithWorkingDir` replaced by an empty list the first test fails, missing `b.txt`.
- [x] `computeDiff(target: DiffTarget, includeLineStats)`: `root = LocalFileSystem.refreshAndFindFileByNioFile(target.root)` (null throws); merge-base and committed diff as before with `root`; `uncommitted = GitChangeUtils.getDiffWithWorkingDir(project, root, "HEAD", null, false)`; untracked from `git ls-files --others --exclude-standard -z` as `FilePath`s; `fetchCommitDates`, `fetchLineStats`, `untrackedLineStats` take `root`. `currentBranch` from `git branch --show-current` (empty: `(detached HEAD)`). New private `git(root, command, params)` throws on failure (`getOutputOrThrow`).
- [x] `listRecentBranches(root, maxAgeDays)`; a failing `for-each-ref` throws instead of returning no branches. The panel's `refresh` resolves `BranchDiffTargets.current()` on the pooled thread and passes it to both (the rest of Task 3 is still open).
- [x] All 9 test classes pass (`DiffMergerTest` unchanged); `buildPlugin` OK; commit.

## Task 3: every caller takes the target's root (idea-branch-diff)

**Files:** `toolwindow/BranchDiffPanel.kt` (lines ~117–130, 174, 207), `gutter/BranchDiffGutterController.kt` (~112), `service/BaseContentProvider.kt` (~40), `action/PlannotatorReviewAction.kt` (~28)

- [x] `DiffSnapshot` carries `target` and `root` (the target's `VirtualFile`, found when the diff was computed) in place of `baseBranch`; the panel (editor selection, opening a file, empty-tree messages), the gutter and `BaseContentProvider.getBaseContent(root, path, hash)` read them instead of `repositories.firstOrNull()`. The cache key stays `(path, hash)`: a commit's content is the same in every work tree. Plannotator gets the panel's snapshot (`PlannotatorReviewAction(project) { lastSnapshot }`), runs in `target.root` with `target.base` and is enabled only with a snapshot. The "no common history" text moved into `computeDiff`'s failure, since the base is the target's, not the setting's.
- [x] `repositories.firstOrNull` is gone from `src/main`; the only `GitRepositoryManager` use left is in `BranchDiffTargets`.
- [x] `GitDiffServiceWorktreeTest` also checks `snapshot.root` is the worktree and `getBaseContent(root, "a.txt", mergeBase)` reads `a` there; all 9 test classes pass, `buildPlugin` OK; commit. The base picker (`showBranchPopup`, `updateBranchButtonText`) still reads `settings.baseBranch`: Task 5.

## Task 4: refresh on the target (idea-branch-diff)

**Files:** `listener/BranchDiffRepositoryListener.kt`

- [x] Subscribe to `TARGET_CHANGED` → refresh through the existing 300 ms `SingleAlarm`.
- [x] Replace `changeListUpdateDone` and `VcsManagedFilesHolder` with a `BulkFileListener` on `VFS_CHANGES` filtered by the pure `isRelevant(path, root, gitDirs)` (`TargetEventsTest`, 3 tests): under the root, except inside a git dir, where only `HEAD`, `index`, `packed-refs` and `refs/` of the deepest matching git dir count. `DiffSnapshot.gitDirs` (`git rev-parse --path-format=absolute --git-dir --git-common-dir`, distinct; `GitDiffServiceWorktreeTest` checks both shapes) gives a worktree's `.git/worktrees/<name>` and the common `.git`. `GIT_REPO_CHANGE` kept. The panel hands each shown snapshot to the listener (`BranchDiffPanel(project, onSnapshot)`), which watches its root and git dirs with `replaceWatchedRoots` (always, not only outside content roots: a duplicate request costs nothing) and removes them on dispose.
- [ ] Manual (in Task 8): edit a file in a worktree outside the IDE; the tree updates within a second. Known limit: the VFS reports changes only for files it has loaded, so an external change in a folder of the worktree never opened in the IDE goes unseen; in follow mode My Air's notifications cover it (Task 6 fires on every change of the session's transcript, which records each edit `claude` makes).
- [x] Commit.

## Task 5: follow toggle and header (idea-branch-diff)

**Files:** `action/FollowSessionAction.kt` (new), `toolwindow/BranchDiffPanel.kt`, `plugin.xml`

- [x] `FollowSessionAction` (toggle, `followSession`, publishes `TARGET_CHANGED`), visible only when a provider is installed.
- [x] The branch button: `branchButtonText(target, branch, settingsBase)` (`BranchButtonTextTest`, 3 tests): `label · branch → base` for a provided target, `label → base` when its diff failed, `base` for the project's own, the setting before any target. The panel keeps `shownTarget` also when the diff fails, so the base of a session with no common history can still be changed. Choosing a base on a provided target calls `provider.setBase` on a pooled thread (decision 2); otherwise it writes `settings.baseBranch` and refreshes. `BranchDiffTargets.current()` asserts that a provided target has a `label`, which is what tells the two apart.
- [x] `test buildPlugin` (11 test classes, 0 failures); commit; idea-branch-diff `CLAUDE.md` describes targets, the extension point, the refresh triggers and the VFS limit.

## Task 6: `ActiveSession` (my-air)

**Files:** `service/ActiveSession.kt` (new), `toolwindow/SessionContextPanel.kt`, `plugin.xml`, test `service/ActiveSessionTest.kt`

- [ ] Test the pure rule `activeTab(selected: VirtualFile?, last: ClaudeSessionVirtualFile?, isOpen: (VirtualFile) -> Boolean)`: a selected session tab wins; another file selected keeps `last` while open; `last` closed → null.
- [ ] Project service `ActiveSession`: `tab`, updated on `selectionChanged` (the rule above), `subscribe(parent, listener)` fired when the tab or the shown `ClaudeSession` (its transcript, so every edit and commit `claude` makes), its head (`service.heads`) or its live entry changes; Branch Diff debounces, so firing on each transcript change is cheap.
- [ ] `SessionContextPanel.update()` reads `ActiveSession.tab` instead of its own `shownTab`, so both panes pick the same session.
- [ ] `./gradlew test`; commit.

## Task 7: `SessionDiffTargetProvider` (my-air)

**Files:** `branchdiff/SessionDiffTargetProvider.kt` (new), `actions/Git.kt`, `META-INF/plugin.xml`, `META-INF/my-air-branch-diff.xml` (new), `build.gradle.kts`, `GitTest.kt`

- [ ] `Git.baseOf(root): String?` extracted from `state()` (stored base, else `repo(root).default`; `state()` calls it). `GitTest`: stored base wins; without one, the default branch.
- [ ] `build.gradle.kts`: `localPlugin("E:/dev/projects/idea-branch-diff/build/distributions/branch-diff-tree-0.1.0.zip")` in `intellijPlatform { }`.
- [ ] `plugin.xml`: `<depends optional="true" config-file="my-air-branch-diff.xml">dev.kosmala.branch-diff-tree</depends>`; the config file registers `<extensions defaultExtensionNs="dev.kosmala.branch-diff-tree"><diffTargetProvider implementation="dev.myair.branchdiff.SessionDiffTargetProvider"/></extensions>`.
- [ ] `target`: the active session's `workDir`, `root = Git.root(workDir)` (null: not in a repository → null target), `base = Git.baseOf(root)` (null → throws with "no base"), `label = session.title`. `subscribe` → `ActiveSession.subscribe`. `setBase` → `Git.setBase(root, Git.branchOf(root)!!, base)` (detached throws), then notify.
- [ ] `./gradlew test buildPlugin`; commit; CLAUDE.md: a paragraph on the Branch Diff provider.

## Task 8: check in the sandbox (my-air)

- [ ] Sandbox with both plugins (`runIde`, command from CLAUDE.md). Open two session tabs: one in the main checkout, one in a worktree. Selecting each switches Branch Diff's tree and header; selecting a plain file keeps the last session; closing that tab returns to project mode; editing a file in the worktree refreshes the tree; changing the base in Branch Diff changes it in Session Context and the other way round.
- [ ] Status `waiting: visual check by the user` until confirmed.
