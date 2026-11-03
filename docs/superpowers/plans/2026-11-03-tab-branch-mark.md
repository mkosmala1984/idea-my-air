# Tab Branch Mark Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |

## TL;DR

- **Goal:** a session tab whose session works in another work tree than the window's project (a worktree, so another branch) stands out: its own colour, the branch badge, the branch in the tooltip. Today the badge depends only on where the session started (`cwd != project`), there is no colour, and a session that moved by `EnterWorktree` is not marked.
- **Approach:** read, off the EDT on every refresh, the work tree root and branch of every session's `workDir` and of every open project's base (`Git.head`, HEAD file only, no `git` process); keep them in the service `State`, so a checkout in the window also re-marks; `retargetOpenTabs` hands each tab its `TabPlace`; a pure `tabMark` decides `OtherProject` (as today) / `OtherTree(branch)` / `None`.
- **Needs your input:** (1) colour of an `OtherTree` tab: **recommended** the palette colour of the worktree's path (`projectColor(root)`, reused, so each worktree keeps its own colour), alternative one fixed accent for all worktrees; (2) branch in the tab title (`Fix login [feature-x]`): **recommended no**, badge + tooltip only, since titles are already long; (3) a tab of another project in a worktree keeps the project's colour (recommended) rather than the worktree's.
- **Risks:** a checkout in the window shows on the tabs only at the next refresh (a `claude` event or the 60 s rescan), as there is no git4idea dependency; the palette colour of a worktree may resemble another project's.
- **Out of scope:** session rows in the tree and the pinned list (they already show the branch), the taskbar, a setting to turn the mark off.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox syntax.

**Architecture:** `actions/Git.kt` gains `Head(root, branch)` and `head(dir)`, `headBranch` becomes `head(dir)?.branch`. `editor/TabMark.kt` holds `TabPlace`, `TabMark` and `tabMark(...)`. `ClaudeSessionVirtualFile` gets `@Volatile var place: TabPlace?`. `ClaudeSessionsService.State` gets `heads: Map<Path, Head?>`, computed in `refresh` on the refresher thread. `retargetOpenTabs` sets `file.place` and repaints on a change. `ClaudeSessionFileIconProvider` and `ClaudeSessionTabColorProvider` read `tabMark`.

## Global Constraints

- Code, comments, strings in English. No fallbacks: a malformed `.git` file throws, as `headBranch` does now.
- No file IO on the EDT: icon and colour providers read only `file.place` and the window's head from the service state.
- A tab with no scanned session yet (a new `--session-id` tab) uses its `cwd` as `workDir`; that is its real start, not a fallback.
- Every commit ends with `Plan-Task: N/5`, `Plan: docs/superpowers/plans/2026-11-03-tab-branch-mark.md`, then the `Co-Authored-By` line.

## Task 1: `Git.head`

**Files:** `src/main/kotlin/dev/myair/actions/Git.kt`, `src/test/kotlin/dev/myair/actions/GitTest.kt`

- [ ] Test (real `git` in a temp dir, as `GitTest` does): in a main checkout on `main`, `head(sub)` of a subfolder is `Head(root, "main")`; in a linked worktree (`git worktree add ../wt -b feat`), `head(wt/sub)` is `Head(wt, "feat")`; after `git checkout --detach`, `branch` is null; outside a repository `head` is null.
- [ ] `data class Head(val root: Path, val branch: String?)`; `fun head(dir: Path): Head?` is the body of today's `headBranch` returning `Head(dotGit.parent, branch)`; `headBranch(dir) = head(dir)?.branch`.
- [ ] `./gradlew test --tests dev.myair.actions.GitTest` passes; commit.

## Task 2: `tabMark`

**Files:** `src/main/kotlin/dev/myair/editor/TabMark.kt`, `src/main/kotlin/dev/myair/editor/ClaudeSessionVirtualFile.kt` (`tabTooltip`), `src/test/kotlin/dev/myair/editor/TabTitleTest.kt`

- [ ] Tests, as a table: another project → `OtherProject` whatever the trees; same project, session's root equal to the window's → `None`; same project, other root → `OtherTree(branch)`, with `branch` null when detached; session or window outside a repository → `None`; a session with no `TabPlace` yet → `None`.
- [ ] `data class TabPlace(val head: Head?)`, `sealed interface TabMark { OtherProject; data class OtherTree(val root: Path, val branch: String?); None }`, `fun tabMark(sessionProject: Path, place: TabPlace?, ideBase: Path, ideHead: Head?): TabMark`, `isOtherProject` used for the first case.
- [ ] `tabTooltip(title, project, mark)`: `Project: …`, then for `OtherTree` `Worktree: <root>` and `Branch: <branch>` (or `Branch: detached`); the existing tooltip test is rewritten to the new signature.
- [ ] Tests pass; commit.

## Task 3: heads in the service, places on the tabs

**Files:** `src/main/kotlin/dev/myair/service/ClaudeSessionsService.kt`, `src/main/kotlin/dev/myair/service/TabRetargeting.kt`, `src/main/kotlin/dev/myair/editor/ClaudeSessionVirtualFile.kt`

- [ ] In `refresh`, on the refresher thread after the scan: `heads = (scanned.map { it.workDir } + open tabs' cwd + openProjects' bases).distinct().associateWith(Git::head)`; part of `State`, so a changed branch alone passes the `next == state` check and repaints the tabs.
- [ ] `ClaudeSessionVirtualFile`: `@Volatile var place: TabPlace? = null`.
- [ ] `retargetOpenTabs(live, sessions by id, unread, heads)`: `place = TabPlace(heads[session?.workDir ?: file.cwd])`, compared with the others before `continue`; a place change alone is not published to other IDEs.
- [ ] `ClaudeSessionsService.ideHead(project) = heads[projectBase(project)]` for the providers.
- [ ] `./gradlew test` passes; commit.

## Task 4: icon and colour

**Files:** `src/main/kotlin/dev/myair/editor/ClaudeSessionVirtualFile.kt`

- [ ] `ClaudeSessionFileIconProvider`: the badge when `tabMark` is `OtherTree` (instead of `cwd != project`); the project icon after it when `OtherProject`, as now. A tab of another project keeps the badge when its own `place` root is not its project (decision 3).
- [ ] `ClaudeSessionTabColorProvider`: `OtherProject` → `projectColor(project)` as now; `OtherTree` → `projectColor(root)` (decision 1), same mix with the background.
- [ ] `./gradlew test buildPlugin`; commit.

## Task 5: check in the sandbox, CLAUDE.md

- [ ] `runIde` (without `CLAUDE*` variables, see CLAUDE.md); in the sandbox project open: a session in the main checkout (no badge, no colour), a session started by New Session in Worktree (badge, colour, `Branch:` in the tooltip), a session that runs `EnterWorktree` (marked after the next refresh), a session of another project (project colour, as before). `git checkout -b x` in the window's checkout: after the next refresh the main-checkout session stays unmarked (same tree), the worktree session stays marked.
- [ ] CLAUDE.md, `ClaudeSessionVirtualFile` paragraph: the badge and colour follow `workDir` and the window's work tree (`tabMark`), not the start cwd.
- [ ] Commit.
