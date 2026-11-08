# Plan view in an editor tab

## TL;DR

- **Goal:** a plan folder opens in an editor tab that shows, and edits in place, the plan's description, milestones and tasks with the sessions working on each task. A Claude session that manages the plan runs in the same tab.
- **Approach:** a new `PlanEditor` on a `PlanVirtualFile` (`myair-plan://<folderId>`, restored after a restart) holds a splitter: the plan on the left (a Swing panel rebuilt from `ClaudeSessionsService.folders` on every change), the manager's terminal on the right. `SessionTerminals` learns to show a process in any `TerminalHost`, not only a `ClaudeSessionEditor`.
- **Manager session:** one per plan, kept in `~/.my-air/plan-managers.json` (session id, cwd, project per plan folder id), outside `structure.json` and its history. The first open starts it (`--session-id`, `PlanStart(folder, null)`), every later open resumes it; `/clear` and `/resume` in it move the record to the new session. It always shows in the plan tab: opening it from the tree or the pinned list focuses that tab.
- **Actions:** edit every title, description and next step, set a task's status, mark a milestone done, add and delete milestones and tasks, start a session on a task (plain or in a worktree), open a session working on a task. No drag and drop.
- **Decided by the user (2026-11-08):** terminal embedded in the same tab; one fixed manager session per plan, resumed on open; the actions above, without dragging.
- **Risks:** `SessionTerminals`, `SessionHosts`, `ActiveSession` and the service's tab heads assume a session shows only in a `ClaudeSessionEditor` of a `ClaudeSessionVirtualFile` tab, so the refactor touches tab lookup, focusing, retargeting, detach, tab marks, the active session and close confirmation.
- **Out of scope:** dragging, several managers per plan, changes to the Session Context Plan card, editing a plan's folder (name, dirs, workdir) from the view.

## Why

The plan lives in the Claude Sessions tree, where only titles show. Descriptions appear in tooltips and in the Session Context Plan card. Every edit goes through a dialog from the context menu. The user wants to see the whole plan and change it directly, and to keep a Claude session at hand that reorganises the plan through `my_air_plan_apply`. Its changes should show in the view as they land.

## Layout

```
┌─ <plan name> ────────────────────────────────────────────────────────────┐
│ Description  [Edit]                     │ Claude: manager session        │
│ rendered Markdown                       │                                │
│─────────────────────────────────────────│ terminal of the manager        │
│ M1 <title>        2/3  [✓ done] [⋯]     │ (CenteredTerminal, as a        │
│   ✓ T1 <title>   done ▾        [⋯]      │  session tab shows it)         │
│   ● T2 <title>   in progress ▾ [▶][⋯]   │                                │
│     next: <next step>                   │                                │
│     <description, rendered>             │                                │
│     ● <session title>   5m              │                                │
│   + Add task                            │                                │
│ + Add milestone                         │                                │
└──────────────────────────────────────────────────────────────────────────┘
```

- An `OnePixelSplitter`, horizontal. Its proportion is stored once for all plan tabs (application property).
- The left side scrolls vertically. The plan's description comes first. Each milestone follows with its tasks. Each task shows its status, its next step, its description and its sessions.
- Markdown is rendered as the tree's tooltips and the Plan card render it (`convertMarkdownToHtml`).
- Look: the white rounded cards of Session Context (`ContextCard`) and the pane font (`PaneFont`). One card for the description, one per milestone, so the view matches Session Context.

## Opening

- **Open Plan** is a new item in the tree menu on a plan folder, milestone, task and `no task` row. Each opens the plan of that row.
- **Open Plan** also appears at the head of the Session Context Plan card, which today shows the plan's path.
- A plan already open in this window is focused, not opened twice. `LightVirtualFile` compares by identity, so `PlanVirtualFile` instances come from an application-level registry keyed by folder id (one instance per plan per IDE), and Open Plan and `PlanFileSystem` both take it from there.
- Tab title: the plan's name, which follows renames. Tab icon: the plan folder's icon in the tree.
- After a restart, `PlanFileSystem.findFileByPath(<folderId>)` restores the tab. When the folder no longer exists or has no plan, the tab shows a notice ("This plan no longer exists.") instead of the view. The same notice replaces the view when a plan open in a tab is deleted.

## Editing

Every edit goes through `ClaudeSessionsService.updateFolders(ChangeSource.UI, …)` with the operations `SessionFolders` already has: `setDescription`, `editMilestone`, `editTask`, `addMilestone`, `addTask`, `deletePlanItem`. An edit therefore lands in the history and can be undone like one made from the tree.

| Element | How it is edited |
|---|---|
| plan / milestone / task description | **Edit** swaps the rendered Markdown for a text area with Save and Cancel. Ctrl+Enter saves, Esc cancels, and an empty text clears it. |
| milestone / task title | A click on the title turns it into a text field. Enter saves, Esc cancels, and a blank title is refused. |
| task next step | The same as a title, but an empty value clears it. |
| task status | The status label is a dropdown with the six `PlanStatus` values and their icons (`status.icon`). |
| milestone done | A toggle on the milestone header. |
| add milestone / task | A **+ Add milestone** or **+ Add task** row at the end turns into a title field. Enter adds the item and leaves a fresh field open for the next one. |
| delete | In the item's `⋯` menu. It asks first, and the rules match `FolderEditing.delete`: a milestone with open tasks is refused and its open tasks are named. |

Live updates: the panel subscribes to `ClaudeSessionsService` and rebuilds when the plan or its sessions change. An editor open at that moment, such as a text area or a title field, keeps the text being typed. On save, its operation applies by id to the structure as it is then. An item deleted meanwhile is unchanged (the `SessionFolders` rule), and the editor closes.

## Sessions and task actions

- Under each task: the sessions whose brief names it (`SessionFolders.taskOf`). Each row shows its status icon (`sessionIcon`), title (bold when unread) and age, ordered as the tree orders them. A click opens the session with `ClaudeSessionOpener.open`.
- Each task has two actions: **New Session** and **New Session in Worktree...**. Both start a session with `PlanStart(folderId, taskId)` and file it in the plan folder, as the tree's actions on a task row do (`NewSessionInDirAction` with `selectedPlanStart`). The directory is the plan folder's start directory (`startFolder`). When none is set, the IDE project's directory is used.
- The manager session shows only in the terminal, not under a task. Its brief names no task.

## Manager session

- **Store (my call):** `~/.my-air/plan-managers.json`, `{ "<folderId>": { "session": "<id>", "cwd": "<dir>", "project": "<dir>" } }`, written atomically (`writeAtomically`), shared by every IDE. Not in `structure.json`: a history restore or an older My Air rewriting that file would drop the manager, and the manager is not something the user edits. An entry whose folder no longer exists is removed on the next write.
- On open, when the plan has no entry, the editor picks the cwd (below), writes the entry with a new UUID, then starts with `--session-id <id>`. With an entry, `--resume <id>` is used, or `--session-id <id>` when there is no transcript yet (`restartArgs`), always in the stored cwd and project, so the window that opens it does not change where it runs. Either way, `planStart = PlanStart(folderId, null)` gives it the plan prompt.
- **`/clear`, `/resume` (my call):** when retargeting moves the manager's file to another session id (`retargetOpenTabs`), the entry follows it, so the next open resumes what the tab showed last and opening that session from the tree goes to the plan tab.
- **`/bg`:** the start already reads the registry and attaches to the job (`claude attach <jobId>`); the plan tab follows the job as a session tab does (`shownBy`).
- The manager also gets one more `--append-system-prompt`: it is this plan's manager, it changes the plan through `my_air_plan_apply` (deleting or moving only with the user's agreement, as for every session), and the user sees the plan beside it. The exact wording is for the builder.
- Its cwd, chosen at first start and stored: the plan folder's start directory (`startFolder`), else the IDE project's of the window that first opens it. When that directory is gone, the terminal side shows a notice, as a restored session tab does.
- **Running elsewhere (my call: as a session tab).** The checks a restored session tab makes (`ClaudeSessionEditor.restoreBlocker`: directory gone; the registry shows the session running other than as a job, with Switch to Window / Open Anyway) move into a function both editors call; the plan editor runs it every time it is about to start the manager, not only when restored. A plan tab of the same plan in another window of this IDE takes the process as a split does: the newest shows it, the other shows Show Here. Another IDE's plan tab is reached first by Open Plan through `TabsSync.focusElsewhere`, as for sessions, since the manager's session id is published with the tab.
- The manager is filed in the plan folder, so in the tree it shows under `no task`, like any other session of the plan.
- Process: `SessionTerminals` runs it like any session. Closing the plan tab detaches it or ends it by the same rules as a session tab (`releaseOf`, `retires`, the close confirmations).
- **Opening the manager from elsewhere** (tree, pinned list, taskbar, another IDE) goes to the plan tab. `SessionHosts.findTab` treats an open plan tab as the tab of its manager session. When the plan tab is closed and the process detached, opening the session reopens the plan tab, not a session tab.

## Terminal host refactor

Today `SessionTerminals.attach`/`release` take a `ClaudeSessionEditor`, and `Runtime.owner` is one. They change to a small interface implemented by `ClaudeSessionEditor` and by the plan editor's terminal side:

```kotlin
interface TerminalHost {
    fun show(view: TerminalView)
    fun showElsewhere()
}
```

Keying by `ClaudeSessionVirtualFile` stays. The manager's `ClaudeSessionVirtualFile` lives in the same application-level registry as the `PlanVirtualFile` (one pair per plan per IDE), not in the editor: `SessionTerminals.runtimes` is keyed by file identity, so a reopened plan tab must find the same file to bind a detached process. It is never opened as a tab, so `FileEditorManager` never sees it. The registry maps the manager file back to its plan.

Places that assume a session shows only in a `ClaudeSessionVirtualFile` tab must also see plan tabs, which carry their manager's file, and must focus the plan tab where they would open the session file:
- `SessionHosts.findTab`/`openTabs`/`visibleSessions`/`focus`, and `SessionTerminals.detached()` consumers (a detached manager reopens its plan tab);
- `retargetOpenTabs` (plus the entry move above) and `tabHeads` (`ClaudeSessionsService`);
- `ActiveSession` (my call: a selected plan tab makes its manager the active session, so Session Context, Branch Diff and seen-marking work as for a session tab);
- `TabsSync.publish`, `ProjectCloseConfirmation`;
- the column re-layout in `MyAirSettings` that filters `ClaudeSessionEditor`.

The builder confirms the list with `analyze_calls`.

## Behaviour table

| Event | Result |
|---|---|
| Open Plan on a plan with no manager | tab opens; entry written to `plan-managers.json`; manager starts with `--session-id` |
| `/clear` in the manager | the entry moves to the new session id |
| manager running in a terminal outside the IDE | terminal side shows "running elsewhere" with Switch to Window / Open Anyway |
| Open Plan on a plan with a manager | tab opens; manager resumes (or binds its running process when detached) |
| Open Plan while the plan's tab is open in this window | that tab is focused |
| manager calls `my_air_plan_apply` | the view rebuilds within one service refresh |
| user edits a title while the manager deletes that task | save changes nothing; field closes |
| plan tab closed, manager working, keep-detached on | process runs on detached; opening the manager reopens the plan tab |
| plan folder deleted while open | view replaced by "This plan no longer exists."; manager process ends |
| IDE restart with plan tab open | tab restored; manager resumes when the tab is first selected |
| manager's directory gone | terminal side shows a notice; the plan side still works |

## Tests

Pure parts get JUnit tests, as in the rest of the project:
- the `myair-plan` URL round trip;
- `plan-managers.json` parse and write, an entry following a retarget, entries of deleted folders pruned;
- the manager's start arguments (new id, resume, no transcript);
- the view model built from `SessionFolders` and sessions: milestones, tasks, the sessions per task, the manager left out.

The Swing panel and the terminal hosting are checked by hand in the sandbox (`runIde`) against the behaviour table.

## Left to the builder

- The exact Swing components and spacing, within the Session Context look.
- The wording of the manager's added prompt.
- Whether the view model is a separate data class or built directly in the panel, as long as the parts above are tested.
- The `CLAUDE.md` paragraph describing the view, added with the code.
