# Session folders mode

## TL;DR

- **Goal:** a second view mode of the Claude Sessions tool window where the user arranges sessions into their own folders and subfolders (e.g. `rtm/rtm migration`, `rtm/fixes`, `testflo`, `experiments`), with a fixed `sessions` folder at the bottom holding every unassigned session in today's project layout.
- **Approach:** one JSON file `~/.my-air/structure.json`, shared by all IDEs and later editable by an agent over MCP; a pure model class with the tree operations (unit-tested), a small file store, the existing watcher pattern for live reload, drag and drop in the existing tree.
- **New sessions** go to the folder of the last selected node in that project window (a folder, or the folder a selected session sits in).
- **Decisions needing input:** none open; calls I made are marked *(my call)* below.
- **Risks:** two IDEs editing the structure at the same instant: the later write wins (every operation re-reads the file first, so the window is milliseconds).
- **Folder order** is manual, set by dragging (user's decision); sessions inside a folder stay newest first.
- **Out of scope:** MCP server, non-session items (Jira, PR, files, notes, requirements), manual ordering of sessions inside a folder.

## Why

The default view groups sessions by the directory `claude` ran in. The user thinks about work by topic, not by checkout: one topic spans repositories, one repository hosts unrelated topics. The folders are the start of a workspace that will later also hold Jira tasks, PRs, files, requirements and decisions, managed by an agent through MCP. So the structure must live in a plain file outside the IDE, and its format must accept new item types without a migration.

## Goals

- A toolbar toggle switches the tool window between **Projects** (today's view, unchanged) and **Folders**. The choice is remembered per IDE (application-level `PropertiesComponent`) *(my call)*.
- Folders mode shows user folders at the top level, then a fixed `sessions` folder as the last root child.
- `sessions` holds every session not assigned to a user folder, laid out exactly like Projects mode (cwd → sessions → changed files).
- Each session is in exactly one place: in one user folder, or (unassigned) under `sessions`.
- The user can create a folder (at the root or inside a folder), rename it, delete it, and drag sessions and folders around.
- New sessions land in the last selected folder of the project window they were started from.
- All IDEs show the same structure and see each other's changes live.

## Non-goals

- MCP access, item types other than sessions. The file format reserves room for them (see Format).
- Manual ordering of sessions within a folder.
- Showing a session in more than one folder.

## Anti-goals

- Losing a user's folder assignments because a session is temporarily missing from the scan (transcript not yet written, scan failed). Assignments are only removed by the user.
- Silently repairing a malformed `structure.json`. It throws, like the rest of this plugin does on unexpected `claude` formats.

## Behaviour

### Tree in Folders mode

```
rtm
  rtm migration
    <session>   rtm-cloud          <- greyed project name after the title
  fixes
  other
testflo
experiments
other
sessions                           <- fixed, always last
  E:\dev\projects\rtm-cloud
    <session>
      <changed file>
  ...
```

- Folders keep the order the user gives them by dragging (the user's decision); the order of the `folders` arrays in the file is that order. A new folder goes last among its siblings.
- Inside a user folder: subfolders first, in their manual order; then sessions, newest first, flat. A session row renders as today (title, live status, time, greyed when not running) plus its project name (last segment of cwd) in grey *(my call)*. Changed files stay as children of the session.
- `sessions` renders with a distinct icon, cannot be renamed, deleted or moved, and is not a drop target for folders.
- A folder whose sessions are all missing from the scan still shows (empty). A session id assigned in the file but absent from the scan is not shown and is kept in the file.
- The filter field works in both modes: it keeps sessions that match (as today), keeps the folders on their path, hides other folders, and expands everything. An empty folder is hidden while a filter is active.
- Expansion and selection survive rebuilds, as today (`TreeState`). Node ids for `TreeState`: folders by their id, sessions by their id.

### Operations

| Action | How | Effect |
|---|---|---|
| New folder | context menu on a folder or empty space, toolbar button | prompts for a name; created inside the selected folder, or at the root if nothing / `sessions` / something under `sessions` is selected |
| Rename | context menu on a user folder | prompts with the current name |
| Delete | context menu on a user folder, `Delete` key | confirm dialog naming how many sessions return to `sessions`; removes the folder and its subtree; every session assigned in that subtree becomes unassigned |
| Move session | drag a session onto a user folder | assigned to that folder (removed from its previous one) |
| Unassign session | drag a session onto `sessions` or anything under it | removed from its folder |
| Move folder into | drag a user folder onto another user folder | moved with its subtree, placed last among the new siblings; dropping a folder into itself or its descendant is rejected (no drop indicator) |
| Reorder folder | drag a user folder between two sibling folders, at any level including the root (above `sessions`) | moved to that position, changing parent if the gap belongs to another folder; the drop indicator is a line between rows, distinct from the "onto" highlight |
| Multi-select drag | several sessions at once | each moved as above *(my call: supported, since `SimpleTree` gives it nearly free)* |

- Folder names: non-blank, trimmed, unique among siblings (case-insensitive). The name dialog rejects violations with a message; the model asserts them.
- Dragging a folder into a parent that already has a sibling of that name is refused before the drop (no drop indicator), checked against the structure the panel shows *(my call)*.
- Races with another IDE *(my call)*: an operation runs against a freshly re-read file. If its target folder or the moved folder no longer exists there, the operation changes nothing (the reload that follows shows the current state). Every other invariant violation inside `update` is a bug and throws.

### Malformed `structure.json`

*(my call, following "fail loudly")*: reading throws; the error is logged as an IDE error (as `refresh` does with a failed scan), and Folders mode shows the error message with the file path in place of the tree. No last-good tree is kept. Folder actions are disabled in that state, so nothing writes over the broken file. The next file event re-reads it, and the tree returns once the file is fixed. Projects mode is unaffected.
- Projects mode keeps no folder actions; dragging is off there.

### Where new sessions go

- Each project window remembers its **target folder**: updated whenever the tree selection changes in Folders mode. Selecting a user folder → that folder. Selecting a session in a user folder, or a changed file under such a session → that session's folder. Selecting anything under `sessions` → no target (unassigned).
- Held in memory per project window only; a new window starts with no target *(my call)*. Projects mode does not change it; switching modes does not clear it.
- `ClaudeSessionOpener.startNew` assigns the new session id to the target folder right away, before `claude` writes anything (the id is chosen up front with `--session-id`). This applies to every entry point of New Claude Session (toolbar, Tools menu), since they all go through `startNew`.
- If the target folder no longer exists in the file (deleted by another IDE), the session stays unassigned. That is the plain "no target" state, not an error.
- **`/clear` in a tab** makes `claude` start a new session id in the same process; `retarget` already detects it. The new id is assigned to the folder of the id the tab showed before, if that one was assigned *(my call: a cleared conversation is the same line of work)*. `/resume` of an existing session moves nothing.
- Telling them apart *(my call)*: when `retarget` moves a tab to a new id, the folder carries over only if the new id is absent from the current scan (a session that has just started; this is the same hint `retarget` already uses to show `NEW_SESSION_TITLE`) and is not assigned anywhere yet. A resumed session is already in the scan, so it never moves. An existing assignment is never overwritten.

## Format: `~/.my-air/structure.json`

```json
{
  "version": 1,
  "folders": [
    {
      "id": "6f1c…",
      "name": "rtm",
      "folders": [
        { "id": "a03e…", "name": "rtm migration", "folders": [], "items": [ { "type": "session", "id": "<session uuid>" } ] }
      ],
      "items": []
    }
  ]
}
```

- `folders` at the root are the user's top-level folders; `sessions` is not stored, it is everything unassigned.
- Folder `id` is a random UUID, stable across renames and moves; the target folder and `TreeState` refer to it.
- `items` is a list of `{type, id}`. Version 1 knows only `"session"`. Any other `type`, a missing field, a duplicate folder id, or a session id present twice throws when the file is read. A later item type is added by extending the reader, not by skipping unknown entries.
- A missing file means an empty structure. It is created on the first change.
- Writes go to a temp file and are moved into place (`ATOMIC_MOVE`), like `TabStore`.

## Shape of the code (guidance, the builder may adjust)

- `SessionFolders` (pure, no IDE services, unit-tested): immutable tree value with the operations above (`createFolder`, `rename`, `delete`, `moveFolder(folderId, parentId?, index)`, `assign(sessionId, folderId?)`, `folderOf(sessionId)`), asserting invariants (unique sibling names, no cycles, each session at most once). JSON read/write with Gson, which the IDE ships.
- `StructureStore(file)`: `read()` and `update(op: (SessionFolders) -> SessionFolders)` which re-reads the file, applies `op`, writes atomically. Every user action goes through `update`, so concurrent IDEs lose at most a change made within the same read-write window.
- Live reload: watch `~/.my-air/` for `structure.json` (create/modify) with the same debounce as `ClaudeHomeWatcher`, then notify subscribers on the EDT. `ClaudeSessionsService` already owns refresh and subscription; extending it to also hold the structure keeps one notification path for panels *(my call)*.
- `SessionsPanel` gets a mode, a second `buildRoot` for Folders mode, a context menu, and a `TransferHandler` (or the platform `DnDSupport`) for drag and drop. The target folder lives in a project-level service read by `startNew`.

## Tests

- `SessionFolders`: each operation, each rejected input (duplicate name, cycle, unknown folder), delete returning its subtree's sessions to unassigned, JSON round-trip, every malformed-file case throwing.
- `StructureStore`: `update` against a file changed by another writer between operations keeps both changes; missing file reads as empty.
- Folders-mode tree building as a pure function from (structure, sessions, filter) to a node tree, tested without Swing rendering: assigned sessions not under `sessions`, missing sessions hidden but kept, filter hiding empty folders.
- Target folder resolution from a selected node: folder, session in folder, session under `sessions`, deleted folder.
- Manual check in the sandbox IDE: drag and drop, two IDE instances seeing each other's changes, New Claude Session landing in the selected folder, `/clear` keeping the folder.

## Left to the builder

- Icons, exact menu wording, keyboard shortcuts beyond `Delete`.
- Drag-and-drop mechanism (`TransferHandler` vs `DnDSupport`) and drop-indicator look.
- Whether the structure watcher is a second `ClaudeHomeWatcher` instance or a small generalisation of it.

## Addendum 2026-10-25: projects in folders

- A project row under `sessions` can be dragged onto a user folder or between folders. It becomes a folder named after the directory and bound to it (`"project": "<cwd>"`, format version 2; version 1 reads as unbound). The drop is refused when the name is taken among the new siblings or the project is bound already.
- Where a session shows (`SessionFolders.placeOf`): the folder it is filed in, else the folder bound to its cwd, else `sessions`. The tree, `my_air_sessions_find` (`folder`, `unfiled`, `folder` in the result) and `my_air_folders_get` (`project`) all use it.
- MCP: `create` takes an optional `project` (a directory `my_air_sessions_find` reports), making the new folder bound to it.
- A bound folder is an ordinary folder otherwise: subfolders, rename, move, filing sessions into it and its subfolders. Its sessions do not repeat the project name. Deleting it sends the project back to `sessions`; a session of a bound project cannot be dropped on `sessions`.
