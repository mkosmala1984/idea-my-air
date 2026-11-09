# toolwindow

## Overview

The two tool windows of My Air. **Claude Sessions** (`SessionsPanel`) shows a pinned list of sessions above a tree of all sessions. The tree has two modes: Projects, and Folders (with plans and worktrees). The panel also has a toolbar, search and view options. **Session Context** (`SessionContextPanel`) describes the session of the active session tab as a column of cards. Both read their state from `ClaudeSessionsService` and `ActiveSession` (`dev.myair.service`) and write changes back through them. Pure helpers (`SessionTree.kt`, `SessionContext.kt`, `PinnedLines.kt`, `SectionStates.kt`) are kept apart from the Swing code so they can be tested without the platform.

## Interface

Entry points (named in `plugin.xml`, found at runtime only):

- `SessionsToolWindowFactory`: puts a `SessionsPanel` into the Claude Sessions tool window.
- `SessionContextToolWindowFactory`: puts a `SessionContextPanel` in a scroll pane into the Session Context tool window.

Claude Sessions panel:

- `SessionsPanel` (`SessionsPanel.kt`): pinned list (`TrailerList`, `PinnedRenderer`), tree (`DropLineTree`, `SessionRenderer`), toolbar, search and View Options (Folders mode, Show All Sessions, max age, `Density`, single click, follow active tab, This Work Tree).
- Tree model (`SessionTree.kt`):
  - Builders: `projectsTree`, `foldersTree`.
  - Row types: `ChangedFile`, `WorkDirRow`, `AddedDir`, `BranchRow`, `BriefGroup`, `BriefGoal`, `BriefTaskRow`, `FilesGroup`, `MilestoneRow`, `PlanTaskRow`, `NoTask`, `UnfiledSessions`, `RecentSessions` (`RECENT_COUNT` = 10), `WorktreesGroup`, `WorktreeRow`.
  - Helpers: `inWorkTree`, `Urgency`/`urgencyOf`, `FolderSummary`/`folderSummary`, `groupSessions`, `targetFolder`, `nodeDir`, `nodeProject`, `openDir`, `Drop`/`dropChange`, `projectName`.
- Rendering:
  - `SessionRenderers.kt`: `SessionRenderer`, `Density` (COMPACT, LOOSE), `briefCount`, `BriefTask.line`, `PlanStatus.icon`, `statusText`, `tooltipLines`, `sessionTooltip`, `titleAttributes`.
  - `SessionTrailer.kt`: `SessionTrailer` paints the age, the hover buttons (`TrailerButton`) and group summaries; `shortAge`.
  - `PinnedRenderer.kt`: `PinnedRenderer`, `PinnedLayout`, `NEEDS_INPUT_TINT`.
  - `PinnedLines.kt`: `SecondLine` (`NeedsInput`, `Place`), `secondLine`, `pinnedHeight`.
  - `StatusDots.kt`: `StatusDot`, `NEEDS_INPUT_DOT`, `UNREAD_DOT`, `WORKING_DOT`, `Urgency.dot`.
  - `ActiveBars.kt`: `paintActiveBars`.
- Pin and settle (`PinMarks.kt`): `Mark`, `PIN`, `SETTLE`, `pinAction`, `settleAction`, `PinsToggle`, `TrailerList`.
- Drag and drop: `DropLineTree` and `insertLine` (`DropLineTree.kt`), `FolderDrop` (`FolderDrop.kt`, a `TransferHandler`).
- Folder and plan editing (`FolderEditing.kt`): `FolderEditing` (new folder or plan, rename, delete, add or remove a plan, bind or unbind a directory, set or clear the working directory, milestones, tasks, status, next step, description), `deleteMilestone`, `deleteTask` (also used by `planview`), `FolderAction`.
- Persisted UI state: `treeStateXml`/`treeStateOf` (`TreeStates.kt`), `sectionExpanded`/`withSection` (`SectionStates.kt`).

Session Context panel:

- `SessionContextPanel` (`SessionContextPanel.kt`), plus `fileIcon` and `openFile`.
- Model (`SessionContext.kt`):
  - Types: `SessionContext`, `AddedDirBranch`, `GitRead`, `PlanContext`.
  - Builders: `contextOf`, `gitRead`, `workTreeOf`, `planContextOf`.
  - Skills: `skillGroups`, `skillSections`, `NO_PLUGIN`, `updateState`.
  - Formatting: `modelName`, `backgroundState`, `countText`, `durationText`, `fileLabel`.
  - `OUTPUT_EXTENSIONS` (`md`, `html`).
- Cards (`ContextCards.kt`): `ContextCard`, `ShrinkingLine`, `Chip`, `ChangeBar`, `commentLabel`, `Cell.shrinkable`.
- `CommandDialog` (`CommandDialog.kt`): shows a full command or prompt, with a Copy button.
- `PaneFont` (`PaneFont.kt`): the font-size offset shared by both windows (`points`, `RANGE` -4..12, `subscribe`, `grow`, `growAll`).

## Invariants

- Drops (`dropChange`):
  - Refused into `RecentSessions` and `WorktreesGroup`, which are views only.
  - A session is refused on the root, under a `MilestoneRow` or `PlanTaskRow`, and when any dragged session has a task.
  - A folder is refused when the target is inside the folder itself, when its name is already taken under the target, or when the target cannot hold it (`canHold`).
  - A milestone or task moves only within its own plan.
  - A drop under `UnfiledSessions` unfiles the session.
- `FolderDrop` drags either sessions alone or exactly one folder, project, milestone or task. It refuses every drop while `canEdit()` is false (not in Folders mode, or `foldersError` is set).
- Plans do not nest (`canNewPlan`). Folder names must not be blank, must contain no `/` and must be unique among siblings (`askName`). A directory bound to one folder cannot be bound to another (`bindDirectory`).
- A milestone with open tasks cannot be deleted (`deleteMilestone`). Remove Plan is enabled only for a plan with no milestones (`canRemovePlan`). Delete and Remove Plan first ask whether to interrupt a plan manager that is working (`managersConfirmed`).
- Unexpected input fails loudly:
  - `folderSummary` throws on an unknown node type.
  - `insertLine` throws on a drop into a collapsed or hidden parent.
  - `inWorkTree` throws on a directory missing from `heads`.
  - `ChangeBar` requires `added + removed > 0`.
  - `PaneFont.points` requires a value in `RANGE`.
- Pinned list height (`pinnedHeight`): the dragged height, else the content capped at 40% of the panel (`PINNED_MAX_SHARE`). It is never less than one row and always leaves the tree 60 unscaled px (`TREE_MIN`). A drag never stores 0. The list and its edge are hidden only once the service has loaded and the list is empty.
- Background reads (the newest result wins, and results land on the EDT):
  - `readBranches` runs `Git.headBranch`, `exists` and `gitWorktrees` on a pooled thread, guarded by the `branchReads` counter.
  - Session Context runs its git reads on one dedicated thread (`LatestRead`). It reads only while the pane is showing and only when the session, its live entry or `baseGeneration` changed.
  - Skill files and contained bases are also read off the EDT.
- Tree state: expansion changes made while `render` swaps the model, or while a search is active, are not stored. A search expands every row and leaves the stored state as it was.
- Re-rendering: when only statuses changed, the tree is repainted rather than rebuilt (`updateTree`). Session Context rebuilds only when what it shows changed, or when the font changes.

## Side Effects

- Application `PropertiesComponent` keys:
  - Claude Sessions: `dev.myair.foldersMode`, `dev.myair.showSettled`, `dev.myair.maxAgeDays`, `dev.myair.density`, `dev.myair.singleClick`, `dev.myair.followActiveTab`, `dev.myair.pinnedMaxHeight`, `dev.myair.treeState.{projects|folders}[.workTree]`.
  - Session Context: `dev.myair.context.sections`, `dev.myair.context.pinnedSkills`.
  - Both windows: `dev.myair.fontSize` (published on an application message bus topic).
- Project `PropertiesComponent` key: `dev.myair.thisWorkTree`.
- Folder, plan and pin changes go through `ClaudeSessionsService.updateFolders(ChangeSource.UI, ...)` and `updatePins`, which persist them in `~/.my-air`.
- Git:
  - Reads: `Git.head`, `Git.headBranch`, `gitWorktrees`, `Git.state`, `Git.branchOf`, `Git.containedBases`.
  - Write: `Git.setBase`, followed by `ActiveSession.baseChanged`.
- Actions started from the panels:
  - Open: sessions (`ClaudeSessionOpener.open`), plans (`PlanOpener.open`), files, diffs (`showBaseDiff`, `showCombinedBaseDiff`, `showBaseChangesTree`).
  - Browser and external tools: Plannotator (`reviewInPlannotator`), artifact and skill origin URLs (`BrowserUtil.browse`).
  - Other tools: Open in IDEA, Open in Terminal (a Terminal tab).
  - Sessions: `continueSession` starts a new session; `SessionPane.type` types `/<skill> ` into a tab's prompt without sending it.
- Dialogs: modal input, confirm, error and choose dialogs, and file choosers (`FolderEditing`); `CommandDialog`, whose Copy button writes to the clipboard.
- A 60 s Swing `Timer` refreshes the ages. Subscriptions to `ClaudeSessionsService`, `ActiveSession` and `PaneFont` are tied to the tool window's disposable.
- Registered actions used: `dev.myair.NewSessionWithPrompt`, `dev.myair.NewClaudeSession`, `dev.myair.NewClaudeSessionInWorktree`. Popup places: `ClaudeSessionsPopup`, `ClaudePinnedPopup`.

## Verification

- Run: `./gradlew test --tests 'dev.myair.toolwindow.*'`
