# toolwindow

## Overview

The two tool windows of My Air. **Claude Sessions** (`SessionsPanel`) shows a pinned list of sessions above a tree of all sessions. The tree has two modes: Projects, and Folders (with plans and worktrees). The panel also has a toolbar, search and view options. **Session Context** (`SessionContextPanel`) describes the session of the active session tab as a column of cards. Both read their state from `ClaudeSessionsService` and `ActiveSession` (`dev.myair.service`) and write changes back through them. Pure helpers (`SessionTree.kt`, `SessionContext.kt`, `PinnedLines.kt`, `SectionStates.kt`, `ChangeTree.kt`) are kept apart from the Swing code so they can be tested without the platform.

## Interface

Entry points (named in `plugin.xml`, found at runtime only):

- `SessionsToolWindowFactory`: puts a `SessionsPanel` into the Claude Sessions tool window.
- `SessionContextToolWindowFactory`: puts a `SessionContextPanel` in a scroll pane into the Session Context tool window.

Claude Sessions panel:

- `SessionsPanel` (`SessionsPanel.kt`): pinned list (`TrailerList`, `PinnedRenderer`), tree (`DropLineTree`, `SessionRenderer`), toolbar (This Work Tree a toggle of its own), search with its Search in Conversations toggle, and View Options (Folders mode, Show All Sessions, single click, follow active tab, max age, Sessions in Recent (`RecentCountOption`), `Density`, Increase/Decrease/Reset Font Size, Refresh).
- Tree model (`SessionTree.kt`):
  - Builders: `projectsTree`, `foldersTree`.
  - Row types: `ChangedFile`, `WorkDirRow`, `AddedDir`, `BranchRow`, `BriefGroup`, `BriefGoal`, `BriefTaskRow`, `AgentsGroup`, `AgentRow`, `FilesGroup`, `MilestoneRow`, `PlanTaskRow`, `NoTask`, `UnfiledSessions`, `RecentSessions` (first in Folders mode; `RECENT_COUNT` = 10 by default, View Options offers `RECENT_COUNTS`), `WorktreesGroup`, `WorktreeRow`.
  - Helpers: `inWorkTree`, `Urgency`/`urgencyOf`, `FolderSummary`/`folderSummary` (sessions, most urgent, a review folder's blockers), `reviewBlockers`, `groupSessions`, `targetFolder`, `nodeDir`, `nodeProject`, `openDir`, `reviewStart`, `rowKeys`/`pathsOf` (the selection kept across rebuilds), `Drop`/`dropChange(structure, reviews, dragged, path, childIndex)`, `projectName`.
- Rendering:
  - `SessionRenderers.kt`: `SessionRenderer`, `Density` (COMPACT, LOOSE), `briefCount`, `BriefTask.line`, `PlanStatus.icon`, `statusText`, `tooltipLines` (status, settled, Desktop, the profile unless `default`, the project when the row does not show it, brief, first prompt), `sessionTooltip`, `titleAttributes`.
  - `SessionTrailer.kt`: `SessionTrailer` paints the age, the hover buttons (`TrailerButton`) and group summaries (with "N blockers" in amber); `shortAge`.
  - `PinnedRenderer.kt`: `PinnedRenderer`, `PinnedLayout`, `NEEDS_INPUT_TINT`.
  - `PinnedLines.kt`: `SecondLine` (`NeedsInput`, `Place`), `secondLine`, `pinnedHeight`.
  - `StatusDots.kt`: `StatusDot`, `NEEDS_INPUT_DOT`, `UNREAD_DOT`, `WORKING_DOT`, `Urgency.dot`.
  - `ActiveBars.kt`: `paintActiveBars`.
- Pin and settle (`PinMarks.kt`): `Mark`, `PIN`, `SETTLE`, `pinAction`, `settleAction`, `PinsToggle`, `TrailerList`.
- Drag and drop: `DropLineTree` and `insertLine` (`DropLineTree.kt`), `FolderDrop` (`FolderDrop.kt`, a `TransferHandler`).
- Folder and plan editing (`FolderEditing.kt`): `FolderEditing` (new folder or plan, rename, delete, add or remove a plan, bind or unbind a directory, set or clear the working directory, milestones, tasks, status, next step, description), `deleteMilestone`, `deleteTask` (also used by `planview`), `askFolderName` (the folder-name dialog), `FolderAction`.
- `TurnIntoPlanAction` (`TurnIntoPlan.kt`): Turn into Plan... on the tree's and the pinned list's session menus; a new plan folder where the session shows, the session stored as its manager in its own profile (`PlanFiles.adopt`).
- Persisted UI state: `treeStateXml`/`treeStateOf` (`TreeStates.kt`), `sectionExpanded`/`withSection` (`SectionStates.kt`).

Session Context panel:

- `SessionContextPanel` (`SessionContextPanel.kt`), plus `fileIcon` and `openFile`.
- Model (`SessionContext.kt`):
  - Types: `SessionContext` (with the session's `profile`, shown as a chip after model and effort unless `default`, and the directory its skills are read from), `AddedDirBranch`, `GitRead`, `PlanContext`.
  - Builders: `contextOf`, `gitRead`, `workTreeOf`, `planContextOf`.
  - Skills: `skillGroups`, `skillSections`, `NO_PLUGIN`, `updateState`.
  - Formatting: `modelName`, `backgroundState`, `countText`, `durationText`, `fileLabel`.
  - `OUTPUT_EXTENSIONS` (`md`, `html`), `BACKGROUND_ORDER` (the order of background tasks, shared with the tree's `AgentsGroup`).
- Cards (`ContextCards.kt`): `ContextCard`, `ShrinkingLine`, `Chip`, `commentLabel`, `Cell.shrinkable`, `WrapLine` and `wrapRows` (parts wrapped onto rows when narrow), `horizontallyScrolled`.
- Changes tree (`ChangeTree.kt`): `ChangesFilter` (All, Not pushed, Uncommitted), `openByDefault`, `changeTree(changes, filter): ChangeDir`, the changed files as folders (`ChangeDir`, with file and line counts) and files (`ChangeFile`, with its index in the list), single-folder chains merged as Branch Diff does.
- `CommandDialog` (`CommandDialog.kt`): shows a full command or prompt, with a Copy button.
- `PaneFont` (`PaneFont.kt`): the font-size offset shared by both windows (`points`, `RANGE` -4..12, `subscribe`, `grow`, `growAll`).

## Invariants

- Drops (`dropChange`):
  - Refused into `RecentSessions` and `WorktreesGroup`, which are views only.
  - A session is refused on the root, under a `MilestoneRow` or `PlanTaskRow`, and when any dragged session has a task.
  - A folder is refused when the target is inside the folder itself, when its name is already taken under the target, when the target cannot hold it (`canHold`), or when the move puts a plan in a review folder or a review folder in a review or plan folder (`placementProblem` over the `reviews` passed in, the ids of every `review.json` on disk).
  - A folder or project is refused on any target but a `Folder` or the root.
  - A project is refused when it is bound to a folder already (`projectFolder`) or its name is taken under the target.
  - A milestone or task moves only within its own plan; a milestone dropped onto a row rather than between rows is refused.
  - A drop under `UnfiledSessions` unfiles the session.
- `FolderDrop` drags either sessions alone or exactly one folder, project, milestone or task. It refuses every drop while `canEdit()` is false (not in Folders mode, or `foldersError` is set).
- Plans do not nest (`canNewPlan`). Folder names must not be blank, must contain no `/` and must be unique among siblings (`askFolderName`). A directory bound to one folder cannot be bound to another (`bindDirectory`).
- A milestone with open tasks cannot be deleted (`deleteMilestone`). Remove Plan is enabled only for a plan with no milestones, constraints, decisions, requirements or review (`canRemovePlan`, `Plan.removable`). Delete and Remove Plan first ask whether to interrupt a plan or review manager this IDE runs that is working (`managersConfirmed`).
- Turn into Plan is enabled only for a session that shows outside any plan or review folder and manages no review (`canTurn`), nor a plan (`PlanManagerStore`).
- Unexpected input fails loudly:
  - `folderSummary` throws on an unknown node type.
  - `insertLine` throws on a drop into a collapsed or hidden parent.
  - `inWorkTree` throws on a directory missing from `heads`.
  - `PaneFont.points` requires a value in `RANGE`.
- Pinned list height (`pinnedHeight`): the dragged height, else the content capped at 40% of the panel (`PINNED_MAX_SHARE`). It is never less than one row and always leaves the tree 60 unscaled px (`TREE_MIN`). A drag never stores 0. The list and its edge are hidden only once the service has loaded and the list is empty.
- Background reads (the newest result wins, and results land on the EDT):
  - `readBranches` runs `Git.headBranch`, `exists` and `gitWorktrees` on a pooled thread, guarded by the `branchReads` counter.
  - The conversation search asks `ClaudeSessionsService.searchContent` on a pooled thread; only the latest question (`Search`: query and `indexGeneration`) lands.
  - The window's work-tree root (`Git.head` of the project's base) is read once on a pooled thread.
  - Session Context runs its git reads on one dedicated thread (`LatestRead`). It reads only while the pane is showing, the session's `workDir` exists, and the session, its live entry or `baseGeneration` changed.
  - Skill files (from the session's profile directory, `profileHome`) and contained bases are also read off the EDT.
- Tree state: expansion changes made while `render` swaps the model, or while a search is active, are not stored. A search expands every row and leaves the stored state as it was.
- Re-rendering: when only statuses changed, the tree is repainted rather than rebuilt (`updateTree`). Session Context rebuilds only when what it shows changed, or when the font changes.

## Side Effects

- Application `PropertiesComponent` keys:
  - Claude Sessions: `dev.myair.foldersMode`, `dev.myair.showSettled`, `dev.myair.maxAgeDays`, `dev.myair.recentCount`, `dev.myair.density`, `dev.myair.singleClick`, `dev.myair.followActiveTab`, `dev.myair.pinnedMaxHeight`, `dev.myair.searchConversations`, `dev.myair.treeState.{projects|folders}[.workTree]`.
  - Session Context: `dev.myair.context.sections`, `dev.myair.context.pinnedSkills`, `dev.myair.context.changesAsTree`, `dev.myair.context.changesFilter`.
  - Both windows: `dev.myair.fontSize` (published on an application message bus topic).
- Project `PropertiesComponent` key: `dev.myair.thisWorkTree`.
- Folder, plan and pin changes go through `ClaudeSessionsService.updateFolders(ChangeSource.UI, ...)` and `updatePins`, which persist them in `~/.my-air`.
- Git:
  - Reads: `Git.head`, `Git.headBranch`, `gitWorktrees`, `Git.state`, `Git.branchOf`, `Git.root` (`workTreeOf`), `Git.nearestBases`.
  - Write: `Git.setBase`, followed by `ActiveSession.baseChanged`.
- Actions started from the panels:
  - Open: sessions (`ClaudeSessionOpener.open`), plans (`PlanOpener.open`), reviews (`ReviewOpener.open`), files, diffs (`showBaseDiff`, `showCombinedBaseDiff`, `showBaseChangesTree`).
  - Browser and external tools: Plannotator (`reviewInPlannotator`), artifact and skill origin URLs (`BrowserUtil.browse`).
  - Other tools: Open in IDEA, Open in Terminal (a Terminal tab).
  - Sessions: `continueSession` starts a new session continuing the shown one, `newSessionHere` one in the folder it works in, filed where it is; `SessionPane.type` types `/<skill> ` into a tab's prompt without sending it.
  - Menus: `NewSessionInDirAction` (plain and in a worktree), `NewReviewAction`, `SameSettingsAction`, `SaveAsPresetAction`, `ShowChangesVsBaseAction`, `TurnIntoPlanAction`; the pinned list's Remove from List (`updatePins { it.remove(...) }`); Refresh (`ClaudeSessionsService.refresh`).
- Dialogs: modal input, confirm, error and choose dialogs, and file choosers (`FolderEditing`, `askFolderName`); `CommandDialog`, whose Copy button writes to the clipboard; `SessionContextPanel`'s messages when the repository has no other branch to pick as base, the session's tab runs no claude, or a file to open is gone.
- A 60 s Swing `Timer` refreshes the ages. Subscriptions to `ClaudeSessionsService`, `ActiveSession`, `PaneFont` and `ReviewService` (repaints the tree's blocker counts) are tied to the tool window's disposable.
- Registered actions used: `dev.myair.NewSessionWithPrompt`, `dev.myair.NewClaudeSession`, `dev.myair.NewClaudeSessionInWorktree`, `dev.myair.OpenSessionsDashboard`, `dev.myair.OpenScheduledTasks`; `PresetsGroup` on the toolbar. Popup places: `ClaudeSessionsPopup`, `ClaudePinnedPopup`.

## Verification

- Run: `./gradlew test --tests 'dev.myair.toolwindow.*'`
