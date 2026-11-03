# Sessions of this work tree, and search by branch

## TL;DR

- **Goal:** in a window opened on a worktree (or on the main checkout), see only the sessions that belong to that work tree; and find a session by the name of its branch.
- **Work tree filter:** a toggle button "This Work Tree" on the Claude Sessions toolbar, next to Search, kept per IDE project. When on, the tree (both modes) keeps the sessions whose `workDir` or `cwd` lies in the window's work tree, compared by root as the tab mark compares them (`Git.head`). The list of pinned and running sessions above the tree is not filtered.
- **Search by branch:** each word of the search matches the title, the cwd (as now), the branch the session started on (`startBranch`) or the branch checked out where it works now (`Git.head(workDir).branch`).
- **My calls, to check:** (1) the pinned list stays unfiltered, as with "Hide Sessions Older Than"; (2) "started here" counts too, so the main checkout's window keeps a session that moved to a worktree with `EnterWorktree`; (3) the toggle is disabled in a window whose project is not in a git repository; (4) the filtered tree keeps its own expansion state, so turning the filter off brings back the tree as it was.
- **Risks:** small. `ClaudeSessionsService.heads` gains every session's `cwd` (one HEAD file read per distinct cwd per refresh).
- **Out of scope:** moving a session to another IDE instance by worktree/branch, filtering the pinned list, filtering by project (Projects mode already groups by project), showing the branch in the session row itself.

## Why

The user works in several worktrees of one repository, often each in its own IDE window (`Open in IDEA` on a worktree). Today the Claude Sessions panel in every window shows the same tree: every session of every project. In a worktree window the sessions that matter are those working in that worktree, and they sit mixed with the main checkout's under one project row (`inProjects` folds linked worktrees into the main checkout). Search covers the title and the cwd; a worktree created by My Air is named after its base branch and date (`main-2026-10-28-3fb8`), so the cwd rarely carries the branch name the user remembers, and a removed worktree's path says nothing at all.

## Goals

- One click narrows the tree to the sessions of the window's work tree; the choice survives a restart, per window's project.
- Typing a branch name in Search finds the sessions on that branch, also those whose worktree is gone.

## Non-goals

- Narrowing the pinned/running list.
- A per-branch grouping of the tree.
- Matching branches by anything but a case-insensitive substring.

## Anti-goals

- File or git IO on the EDT: heads are read on the refresh thread, the window's work tree once off the EDT.
- A session silently missing from the filtered tree because its directory was not read: a session whose `workDir` or `cwd` has no entry in the heads map is a bug and throws.

## Behaviour

### The window's work tree

- The window's work tree is the root of `Git.head(projectBase(project))`, read once on a pooled thread when the panel is built (a project's base never changes, so neither does its root). Until it lands, and when it is null (not in a repository), the toggle is disabled; its tooltip says "Not in a Git work tree" in the second case.
- The toggle's description names the root and its branch: `Show only sessions working or started in E:/…/main-2026-10-28-3fb8 (worktree-main-2026-10-28-3fb8)`. The branch is the one the service's heads show for the base; the panel already repaints on every service change.

### Which sessions match

A session matches the window's work tree when `heads[workDir]?.root == windowRoot` or `heads[cwd]?.root == windowRoot`, `heads` being the map the service reads on every refresh (`tabHeads`, made public as `ClaudeSessionsService.heads` and extended with every session's `cwd`).

Consequences, all intended:

| Session | Window on main checkout `M` | Window on worktree `W` (under `M/.claude/worktrees/`) |
|---|---|---|
| started and works in `M` | shown | hidden |
| started in `W`, works in `W` | hidden (roots differ, though `W` lies under `M`) | shown |
| started in `M`, `EnterWorktree` into `W` | shown (started here) | shown (works here) |
| its worktree was removed (`Git.head` null for both dirs) | hidden | — |
| in another project | hidden | hidden |

A `cd` within the work tree changes nothing, since roots are compared, not paths.

### The tree under the filter

- Projects mode: `projectsTree` of the matching sessions.
- Folders mode: the folders, plan milestones and tasks that hold no matching session are left out, as a search leaves them out today; `recent` holds the 10 newest matching sessions; `sessions` (unfiled) stays even when empty, as under search.
- Search and the filter combine: a session must match both.
- "Hide Sessions Older Than" and "Show All Sessions" apply as before.
- Empty result: the tree's empty text is "No sessions in this work tree".
- Expansion: the filtered tree keeps its own `TreeState` per mode (application properties `dev.myair.treeState.projects.workTree` and `.folders.workTree`), remembered and restored exactly as the unfiltered ones are; a search still expands everything and remembers nothing.

### The toggle

- A `ToggleAction` "This Work Tree" with the branch icon (`AllIcons.Vcs.Branch`) on the `ClaudeSessionsView` toolbar, before View Options and Search.
- Its state is kept in the project's `PropertiesComponent` (`dev.myair.thisWorkTree`), so the worktree window can keep it on while the main window keeps it off.
- Turning it on or off rebuilds the tree (`render`).

### Search by branch

- `ClaudeSession.matches(query, branch)` gains the branch checked out where the session works now; each word of the query must be found, case-insensitively, in the title, the cwd, `startBranch` or `branch`. The panel passes `heads.getValue(workDir)?.branch`.
- Both modes use it: `projectsTree` input and `foldersTree`'s filtering.
- The search action's description becomes "Filter sessions by title, project path or branch".
- The session's branch rows (`WorkDirRow` with its branch) already show under each session, and a search expands everything, so a match by branch is visible without changing the row.

## Constraints

- Logic that needs tests stays out of IDE services: the matching rules are pure functions over `ClaudeSession`, a heads map and a root, tested in `SessionTreeTest` / a new `ClaudeSessionTest` case; `foldersTree` takes a predicate (or null for none) instead of a query string, so it can carry both the search and the work tree filter.
- The CLAUDE.md paragraph on the tab store / tree describes the filter and the search fields once implemented.

## Left to the builder

- Names of the new functions and where in `toolwindow/` they live.
- Whether the "Not in a Git work tree" case is a disabled action with a description or hidden action: disabled is preferred.
- Tests: the table above as one parameterised test over a fake heads map; search by `startBranch` and by current branch, including a session whose `startBranch` differs from its current branch.
