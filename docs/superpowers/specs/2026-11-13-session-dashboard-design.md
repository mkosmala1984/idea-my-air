# Sessions dashboard: what is going on now, what was left unfinished

## TL;DR

- **Goal:** one editor tab that answers "where do I stand" across all sessions, above all bringing back the sessions left unfinished days ago that the user no longer remembers (244 sessions had their last message 2–14 days before 2026-11-13, 17 in the last 2 days; the pinned list and the taskbar flyout show only sessions with a live process).
- **Approach (user's pick, A):** a "Sessions" editor tab with three sections: Now (needs input, unread, working), Unfinished (sessions with at least one open signal, grouped by area of work), Finished (merged or without any signal, folded, settled in one go).
- **Signals (user agreed):** unfinished brief tasks, uncommitted or unpushed changes, an open plan task, an unread reply, the agent's last message asking a question; a merged PR ends a session (user's addition).
- **My calls to check:** the merged PR is read from local git (a merge commit on the base whose second parent holds the branch's tip), so it is as fresh as the last `git fetch`; git signals count for a session only through the files it wrote and the branch it last worked on, so sessions sharing a checkout do not all inherit its dirty tree; (b) covers every repository the session wrote into; a removed worktree's branch is looked up in the project's main checkout; a session without a local branch tip gets no merged signal; "asks a question" means the last text of its last assistant message ends with `?`.
- **Risks:** a stale fetch shows merged work as unfinished; stale briefs (tasks never marked completed) keep sessions in Unfinished until settled.
- **Out of scope:** Bitbucket and Jira APIs, changes to the pinned list, the tree and the taskbar app.

## Requirements

- **R1** Tools › Open Sessions Dashboard and a button on the Claude Sessions toolbar open the dashboard as an editor tab; the IDE holds at most one dashboard tab across all its windows: when one is open in any window, the action brings that window to the front and selects the tab instead of opening another.
- **R2** The dashboard tab is restored after an IDE restart, never listed in Recent Files; when the restored windows would hold more than one dashboard tab, only the first one restored stays and the others are closed.
- **R3** Settled sessions (`Pins.settled`) appear in no section; settling a session from any place removes it from the dashboard at the next service change.
- **R4** Now lists exactly the sessions that need input, are working (registry `busy`/`shell`) or are unread, in that order and newest first within each, and each session appears in Now at most once.
- **R5** A session listed in Now appears in no other section.
- **R6** A session not in Now with at least one open signal (R8) and no end overriding it (R9) is listed under Unfinished; with none it is listed under Finished.
- **R7** Unfinished groups sessions by area: the plan folder its `placeOf` lies in, else the ancestor of its `placeOf` folder at depth 2 (the folder itself when it is at depth 1 or 2), else a group named after the last segment of its `project`; groups are ordered by their newest session, sessions within a group newest first.
- **R8** Open signals are: (a) a brief with a task whose status is not `completed`; (b) a file the session wrote is uncommitted in the work tree it lies in (any repository, not only that of `workDir`); (c) the session's branch has commits its upstream lacks, or, without an upstream, commits its base lacks; (d) the plan task its brief names is `in_progress`, `waiting` or `blocked`; (e) the last text block of its last assistant message ends with `?` after trailing whitespace is removed.
- **R9** A merged branch (R10) cancels signals (a), (c), (d) and (e) of that session, never (b).
- **R10** The session's branch is merged when the first-parent history of its base holds a merge commit whose second parent has the branch's tip as ancestor and whose first parent does not; its number is read from the commit subject's `(pull request #<n>)` when present.
- **R11** The session's branch is the `gitBranch` of its last `user`/`assistant` line outside sidechains (`HEAD` counts as none); its tip is `refs/heads/<branch>` in the repository of its `workDir`, or, when `workDir` is gone (a removed worktree), in the main checkout of its `project`; a session without a branch, without such a ref, or whose branch is its base gets neither (c) nor a merged state.
- **R12** Each Unfinished row shows the session's status icon (as in the tree), title, age and one chip per open signal: `brief 2/5`, `uncommitted`, `unpushed`, the plan task's id and status, `asks`; a Finished row shows `PR #<n>` or `merged` when merged.
- **R13** A click on a row opens its session through `ClaudeSessionOpener.open`; a Settle button shown on hover settles that session.
- **R14** Finished is folded by default to a header with its count; unfolded it lists its sessions and a Settle All button, which after a confirmation naming the count settles exactly the sessions listed at the moment of the click.
- **R15** No git command runs on the EDT; while git facts a session needs (each work tree its written files lie in, and its branch's repository) are not read yet its rows show the signals known without git and a loading mark, and they never move to Finished before git was read.
- **R16** A git failure for a work tree shows on the rows of its sessions as an error chip with git's message, and those sessions stay in Unfinished.
- **R17** The dashboard updates on every `ClaudeSessionsService` change while the tab is open, and rereads a work tree's git facts only when its `HEAD`, `index`, `packed-refs` or the loose refs of the branch, its upstream or its base changed since the last read, or when a session whose facts it gives got a newer `lastMessage` or other `changedFiles`, or left Now, since then. Git reads that only look use `--no-optional-locks`, so they do not rewrite `index` and trigger themselves.

## Why

The user starts many sessions; after a few days they cannot remember which of them were left unfinished. My Air shows live state well (status icons, the pinned list, the taskbar flyout with needs input / unread / working), but every one of those shows only sessions with a live process or an unread reply. A session that ended its process three days ago with a half-done brief and unpushed commits shows only as one more grey row in a tree of hundreds. The user wants a high-level overview, not another queue: where each area of work stands, with the sessions that need a decision brought up.

## Goals, non-goals, anti-goals

- Goal: every session left unfinished is visible in one place without searching, wherever it is filed and however old.
- Goal: the user can dispose of finished sessions in bulk, so the Unfinished list stays short enough to read.
- Non-goal: replacing the pinned list or the flyout for live work; Now is a compact repetition of it.
- Non-goal: telling whether work is finished from Jira or Bitbucket.
- Anti-goal: a session with unsaved work (uncommitted files it wrote) shown as finished, under any combination of other signals.
- Anti-goal: every session of a shared checkout flagged because that checkout is dirty.
- Anti-goal: the IDE stalling while git reads dozens of work trees.

## Approaches

| | Shape | Verdict |
|---|---|---|
| **A** | Editor tab, sections Now / Unfinished / Finished, Unfinished grouped by area of work | **picked** by the user: answers "where does each topic stand", has room for signals |
| B | Columns by state (needs input, unread, working, idle) | duplicates the pinned list and flyout; gives no view per topic |
| C | A summary line per top-level folder above the tree | cheap, but the narrow panel has no room for plans, worktrees, signals |

A first sketch of A folded idle areas to one line; the user's reason (forgotten sessions) inverted that: idle-but-unfinished sessions are the core of the view.

## Behaviour

### Layout

```
Sessions                       17 in the last 2 days · 244 older
───────────────────────────────────────────────────────────────
Now        ● 2 need you    ● 3 unread    ● 4 working
  (rows as in the pinned list, two lines each)
───────────────────────────────────────────────────────────────
Unfinished                                                   12
┌ rtm-cloud / security
│ ● TR-11887 Websocket authz     3 d   uncommitted · brief 2/5
└
┌ projects / tenant-migration
│ ● Blokery review AGC           5 d   T12 waiting · asks
└
───────────────────────────────────────────────────────────────
▸ Finished                                                  230
```

In the composer's look of the plan view (`prompt/Composer.kt` cards and chips, `PaneFont`), on the editor background. The header counts sessions not settled whose last message is within 2 days and older (only as information). The exact strings, sizes and colours are the builder's.

### Classifying a session

For each session of `ClaudeSessionsService.listed` not in `Pins.settled`:

1. In Now when the registry says it needs input or works, or it is in `ClaudeSessionsService.unread`. Unscanned (new) sessions with a live process count here like the rest; one without a process cannot exist.
2. Else its signals (R8) and merged state (R10) decide Unfinished or Finished (R6, R9).

Signal details:

- (a) uses `ClaudeSession.brief` as read today; a brief with no tasks gives no signal.
- (b) groups the session's `changedFiles` (absolute paths) by the work tree each lies in (`Git.root` of its existing parent folder) and compares each group with that work tree's uncommitted files (`git status --porcelain`, untracked included). This covers added directories, other repositories and the main checkout before `EnterWorktree`. A file the session wrote that is now committed, deleted and committed, or reverted gives no signal; a file outside any repository or in a folder gone gives none either (nothing can tell whether it is saved).
- A `workDir` gone (removed worktree) keeps (c) and merged state: the branch is looked up in the main checkout of `ClaudeSession.project` (`git worktree remove` keeps the branch). A `workDir` outside a repository gives no (c) or merged state.
- (c) is per branch, not per file: `git rev-list --count <upstream>..<branch>` with the branch's `%(upstream)` when it names a commit, else `<base>..<branch>`. The base of a branch is the one stored for it (`branch.<branch>.myAirBase`), else the repository's default branch, as `Git.state` decides it, but for the session's branch rather than the one checked out.
- (d) uses `SessionFolders.taskOf`.
- (e) needs a new scanner field (below).

### Merged

For the session's branch `B` with tip `T` and base `S`: walk `git log --first-parent --merges --format="%H %P%x00%s" <S>` (bounded by the builder, e.g. merges since the merge base of `S` and `T`), and the first merge commit `M` with parents `P1 P2` such that `T` is an ancestor of `P2` and not of `P1` makes `B` merged. `(pull request #<n>)` in `M`'s subject gives the number. Bitbucket writes `Merged in <branch> (pull request #<n>)`; GitHub's `Merge pull request #<n> from …` gives no number by this rule and shows `merged`. A squash merge is not detected (no merge commit); rtm-cloud's `release/highway` uses true merges (checked 2026-11-13: its last 8 first-parent commits are all `Merged in … (pull request #…)`).

A branch with new commits after its merge has a tip that is no ancestor of any merge's second parent, so it is not merged: correct, that is new work.

### Actions

- Click on a row: `ClaudeSessionOpener.open` (tab here, tab in another IDE, running elsewhere warning, new tab).
- Settle on hover: `Pins.settle` through the service, as `settleAction` does.
- Settle All (Finished): a confirmation "Settle N sessions?"; settles the ids listed when clicked.
- Opening a folder or plan from a group header is left to the builder (Open Plan exists for plan folders).

### Opening and restoring

A `DashboardVirtualFile` under a `DashboardFileSystem` (`myair-dashboard://sessions`), one per IDE held by an application service, as `PlanFiles`/`PlanFileSystem` do: the platform saves it with the open tabs and resolves it on startup, `EditorHistoryManager.OptionallyIncluded` keeps it out of Recent Files. A `FileEditorProvider` gives it the dashboard editor and hides the text editor. The one file per IDE makes the single instance: the action looks for it among the open files of every project window (`FileEditorManager` of each open project) and focuses that window and tab when found, else opens it in the current window. Each IDE process (another IDE instance running My Air) has its own dashboard; cross-IDE focusing is out of scope.

## Data and code

- **Scanner** (`ClaudeSessionScanner`, `ClaudeSession`): two new fields read from the last `user`/`assistant` line outside sidechains and `isMeta`, where `lastMessage` is read: `lastBranch` (its `gitBranch`, `HEAD` as none) and `asks` (true when it is an `assistant` line whose last `text` content item ends with `?` after trimming; tool-use-only lines leave the previous value: the builder decides whether "last assistant text" means the last line with a text item, which is the intent). Both change the transcript-reading classes, so the scan cache rereads everything once, as designed.
- **Git** (`actions/Git.kt`): a new `Git.branchFate(root, branch): BranchFate?` returning null when `refs/heads/<branch>` is missing or the branch is its base, else `unpushed: Int`, `merged: Merged?` (`sha`, `pr: Int?`), computed as above. A new `Git.uncommitted(root): Set<Path>` (`git --no-optional-locks status --porcelain -z`, untracked included) gives the files of (b).
- **Model** (`dashboard/Dashboard.kt`, new package): a pure `dashboardOf(sessions, live, pins, unread, folders, facts: Map<Path, GitFacts>, now): Dashboard` with `now`, `unfinished: List<Area>`, `finished`, each row carrying its signals and a `pending` flag while its work tree's facts are missing (R15) and an `error` (R16). Unit-tested without IDE services.
- **Git reads:** off the EDT on one background executor, one read per distinct work tree root needed by the sessions not in Now and not settled (the roots of their written files and of their branches' repositories): `uncommitted` once, then `branchFate` for each distinct branch looked up there; results kept per root with the mtimes of the files R17 names and the `lastMessage`/`changedFiles` of the sessions it serves; a read whose key is overtaken does not land (`LatestRead` or the same idea).
- **UI** (`dashboard/DashboardEditor.kt`, `DashboardPanel.kt`): subscribes to `ClaudeSessionsService` while open, rebuilds only the sections whose model changed (the plan view's rule, to avoid flicker), unsubscribes on dispose.
- `plugin.xml`: the file system, the editor provider, the action in the Tools menu and the Claude Sessions toolbar.

## Testing

- `DashboardTest`: classification and grouping over hand-built sessions, pins, folders and facts: R3–R9, R12's chip set, R15 (pending never Finished), R16 (error stays Unfinished), area rule of R7 including plan folders, depth 1 and 2 folders and unfiled sessions.
- `GitTest` (real `git` in a temp dir, existing pattern): `branchFate` for a branch merged by a `Merged in b (pull request #12)` merge (pr 12), a branch merged then given a new commit (not merged), a branch never merged with and without an upstream (unpushed counts), a missing branch and a branch equal to its base (null); `uncommitted` with modified, untracked and committed files.
- `DashboardTest` also: a session whose written files lie in two repositories (uncommitted in the second only gives (b)); a session whose `workDir` is gone gets its branch from the project's main checkout; a session that left Now or got a new `lastMessage` invalidates its roots' facts.
- `ClaudeSessionScannerTest`: `lastBranch` and `asks` from transcripts, including sidechain and `isMeta` lines ignored and `HEAD` read as none.
- Manual in `runIde`: R1, R2, R13, R14, R17 (commit in a worktree, the row loses `uncommitted` after the next refresh).

## Left to the builder

Visual details (strings, chip colours, icons, spacing), the bound on the merge walk, whether a group header opens its folder or plan, the executor and caching mechanics within R15–R17, splitting the panel into components.
