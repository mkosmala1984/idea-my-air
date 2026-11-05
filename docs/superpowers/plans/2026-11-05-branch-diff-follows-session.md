# Branch Diff Follows the Active Session Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |
| 6 | | |
| 7 | | |
| 8 | | |

## TL;DR

- **Goal:** the Branch Diff tool window (plugin `idea-branch-diff`, `E:\dev\projects\idea-branch-diff`) shows the diff of the work tree of the active Claude session (the selected session tab, kept while another file is selected and the tab stays open, as in Session Context) against that session's base, and switches when another session tab is selected. With no session tab it shows the project repository against its own chosen base, as today.
- **Approach:** Branch Diff gains a `DiffTarget(root, base, label)` and an extension point `diffTargetProvider`; it knows nothing of My Air. My Air implements the extension point in an optional `config-file`, handing over the session's `workDir` root and the base it already resolves (`branch.<b>.myAirBase`, else the default branch). The dependency goes this way because My Air is built on Java 25 / IU-262 (class version 69, checked), Branch Diff on Java 21 / 2026.1: Branch Diff could not compile against My Air.
- **The core change in Branch Diff:** it reads only `repositories.firstOrNull()` and `ChangeListManager`. A session worktree is not a VCS root (checked: `get_repositories` on my-air lists only the main checkout, not `.claude/worktrees/orchestrator-spec`), so uncommitted and untracked files have to come from git in the target root: `GitChangeUtils.getDiffWithWorkingDir(project, root, "HEAD", null, false)` and `git ls-files --others --exclude-standard -z`. This replaces `ChangeListManager` in project mode too: one code path.
- **Decisions for you (recommendation first):**
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

- [ ] Test the pure resolver `resolveTarget(follow: Boolean, providers: List<DiffTargetProvider>, provided: () -> DiffTarget?, projectRoot: Path, settingsBase: String): DiffTarget`: follow off → `DiffTarget(projectRoot, settingsBase, null)`; no provider → same; provider returns null → same; provider returns a target → that target; two providers → throws.
- [ ] `plugin.xml`: `<extensionPoints><extensionPoint name="diffTargetProvider" interface="dev.kosmala.branchdifftree.service.DiffTargetProvider" dynamic="true"/></extensionPoints>`.
- [ ] `BranchDiffSettings.followSession: Boolean = true`.
- [ ] `BranchDiffTargets` (project service): `current(): DiffTarget` (off the EDT) through `resolveTarget`, the project root being `repositories.single().root` (the only place left that asks `GitRepositoryManager`; zero or several roots throws, as the panel's "No Git repository" message shows today); `TARGET_CHANGED` project topic fired from every provider's `subscribe` and from the follow toggle.
- [ ] `./gradlew test --tests dev.kosmala.branchdifftree.service.DiffTargetTest`; commit.

## Task 2: `computeDiff(target)` from git, not `ChangeListManager` (idea-branch-diff)

**Files:** `service/GitDiffService.kt`, `service/DiffMerger.kt` (signature only if needed), test `service/GitDiffServiceWorktreeTest.kt`

- [ ] Test (`BasePlatformTestCase` or `HeavyPlatformTestCase`, real `git` in a temp dir, since `GitLineHandler` needs a project): a repository on `main` with `a.txt`; a linked worktree `git worktree add ../wt -b feat main`, not registered as a VCS root; in it commit a change to `a.txt`, edit `b.txt` (committed on `main`) without committing, create untracked `c.txt`, add an ignored `d.log` with `.gitignore` `*.log`. `computeDiff(DiffTarget(wt, "main", "s"))` returns exactly `a.txt` MODIFIED committed, `b.txt` MODIFIED uncommitted, `c.txt` ADDED uncommitted; `currentBranch` `feat`. The same against the main checkout gives the project-mode result.
- [ ] `computeDiff(target: DiffTarget, includeLineStats)`: `root = LocalFileSystem.refreshAndFindFileByNioFile(target.root)` (null throws); merge-base and committed diff as now with `root`; `uncommitted = GitChangeUtils.getDiffWithWorkingDir(project, root, "HEAD", null, false)`; untracked from `git ls-files --others --exclude-standard -z` as `FilePath`s; `fetchCommitDates`, `fetchLineStats`, `untrackedLineStats` take `root`. `currentBranch` from `git branch --show-current` (empty: `(detached HEAD)`), since no `GitRepository` exists for a worktree.
- [ ] `listRecentBranches(root, maxAgeDays)`.
- [ ] Existing tests pass (`DiffMergerTest` unchanged: merge rules stay); commit.

## Task 3: every caller takes the target's root (idea-branch-diff)

**Files:** `toolwindow/BranchDiffPanel.kt` (lines ~117–130, 174, 207), `gutter/BranchDiffGutterController.kt` (~112), `service/BaseContentProvider.kt` (~40), `action/PlannotatorReviewAction.kt` (~28)

- [ ] `DiffSnapshot` carries its `target`; the panel, gutter and base provider read `snapshot.target.root` instead of `repositories.firstOrNull()`. `BaseContentProvider` cache key gains the root. Plannotator runs in `target.root` with `target.base`.
- [ ] `grep -rn "repositories.firstOrNull" src/main` returns nothing; the only `GitRepositoryManager` use left is in `BranchDiffTargets`.
- [ ] Tests pass; commit.

## Task 4: refresh on the target (idea-branch-diff)

**Files:** `listener/BranchDiffRepositoryListener.kt`

- [ ] Subscribe to `TARGET_CHANGED` → refresh through the existing 300 ms `SingleAlarm`.
- [ ] Replace `changeListUpdateDone` and `VcsManagedFilesHolder` (they come from `ChangeListManager`, no longer read) with a `BulkFileListener` on `VFS_CHANGES` filtered to paths under the current target root (excluding `.git/` except `HEAD`, `index`, `refs/`); keep `GIT_REPO_CHANGE`. When the target root is outside the project's content roots, `LocalFileSystem.addRootToWatch(root, true)`, removed when the target changes.
- [ ] Manual: edit a file in a worktree outside the IDE; the tree updates within a second.
- [ ] Commit.

## Task 5: follow toggle and header (idea-branch-diff)

**Files:** `action/FollowSessionAction.kt` (new), `toolwindow/BranchDiffPanel.kt`, `plugin.xml`

- [ ] `FollowSessionAction` (toggle, `followSession`), visible only when `DiffTargetProvider.EP` has an extension.
- [ ] The branch button shows `label · branch → base` when `target.label != null`, else `base ▾` as now. Choosing a base: project mode writes `settings.baseBranch`; follow mode calls `provider.setBase(project, target, base)` (decision 2).
- [ ] `buildPlugin`; commit; update idea-branch-diff `CLAUDE.md` (target, extension point, no `ChangeListManager`).

## Task 6: `ActiveSession` (my-air)

**Files:** `service/ActiveSession.kt` (new), `toolwindow/SessionContextPanel.kt`, `plugin.xml`, test `service/ActiveSessionTest.kt`

- [ ] Test the pure rule `activeTab(selected: VirtualFile?, last: ClaudeSessionVirtualFile?, isOpen: (VirtualFile) -> Boolean)`: a selected session tab wins; another file selected keeps `last` while open; `last` closed → null.
- [ ] Project service `ActiveSession`: `tab`, updated on `selectionChanged` (the rule above), `subscribe(parent, listener)` fired when the tab or that session's `workDir`, its head (`service.heads`) or its live entry changes.
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
