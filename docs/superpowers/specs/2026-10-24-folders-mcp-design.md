# MCP tools for session folders

## TL;DR

- **Goal:** on request in a Claude Code conversation ("review the sessions of the last 7 days and propose where to file them"), an agent reads the sessions and folders, shows a table of proposed changes, and applies them after the user replies "ok".
- **Approach:** the plugin adds tools to the MCP server built into IntelliJ (`com.intellij.mcpServer.mcpToolset`, as Air does with `air_set_session_title`); they reuse `SessionFolders`, `StructureStore` and the session scanner. Folders are addressed by path (`rtm/fixes`), so the agent's table and its tool calls use the same names.
- **Sessions** are fetched by one general query, `my_air_sessions_find`: by date/hours, by name, by folder or unfiled, paged (user's request).
- **History:** every write of `structure.json`, from the tree or from MCP, first copies the current file to `~/.my-air/history/`; the newest 20 copies are kept. Tools list and restore them.
- **Confirmation** happens in the conversation (user's decision): the tool descriptions tell the agent to show the table and wait for "ok", and to confirm every folder deletion separately. The tools do not enforce it; history is the safety net.
- **Decisions needing input:** none open; calls I made are marked *(my call)*.
- **Risks:** the tools exist only while an IDE with this plugin runs; the change list is passed as a JSON string *(my call)*, since structured parameters would need kotlinx.serialization in the build. The MCP dependency is optional, so the plugin still loads without the MCP Server plugin.
- **Out of scope:** undo from the tree UI, non-session items, an agent acting on its own in the background.

## Why

Folders mode lets the user file sessions by dragging, one at a time. Sorting a week of sessions is tedious; an agent that already sees the transcripts can propose the filing in one table. The structure file was designed for this: plain JSON in `~/.my-air/`, watched by every IDE, so a change made through MCP shows in all trees without extra wiring.

## Goals

- An agent can list recent sessions with enough context to judge their topic, read the folder tree, and apply any number of folder changes in one atomic write.
- Every structure write is preceded by a snapshot; the agent can list snapshots and restore one.
- The user approves changes in the conversation before they are written.

## Non-goals

- Enforcing the approval in code, or a confirmation dialog in the IDE (user's decision: confirmation in the conversation).
- Standalone MCP server working without an IDE.
- Undo/redo in the tree UI. Restoring goes through the agent, or by copying a history file by hand.

## Anti-goals

- A half-applied change list. One invalid change rejects the whole call and nothing is written.
- Silent no-ops for the agent. In the tree UI, an operation on a folder another IDE deleted does nothing (Folders mode spec); through MCP, an unknown path or session id is an error naming it, because there it is the agent's mistake.
- Snapshot files accumulating without bound.

## Agent workflow (what the tool descriptions encode)

1. `my_air_sessions_find(since = "7d")`, page by page until `total` is covered, and `my_air_folders_get()`.
2. Show the user a table: session title | project | current folder | proposed folder, with new folders marked. Wait for "ok" or corrections.
3. For each folder deletion in the plan, ask about it explicitly, naming how many sessions return to `sessions`.
4. `my_air_folders_apply(changes)` with the approved list. Report the result.
5. On "undo": `my_air_history_list()`, then `my_air_history_restore(name)`.

## Tools

All are `suspend` methods of one `McpToolset` class with `@McpTool` and `@McpDescription`, registered in `plugin.xml` under `com.intellij.mcpServer` (`<mcpToolset implementation="..."/>`), with `<depends>com.intellij.mcpServer</depends>` and `bundledPlugin("com.intellij.mcpServer")` in `build.gradle.kts`. Parameters are strings and ints; results are JSON strings *(my call)*.

- `McpToolset.isExperimental()` defaults to `true` on this IDE build; the toolset overrides it to `false`, as Air's `AcpSessionTitleMcpToolset` does, or the tools may be hidden.
- Only `McpExpectedError` reaches the agent with its text; any other exception is logged as an IDE error and may reach the agent as "Unknown error". So input errors (bad change, unknown path or id, bad snapshot name, unreadable `structure.json`) are thrown as `McpExpectedError` with a message naming the offending input. Anything else stays a plain exception: it is a bug and should surface as an IDE error.

### `my_air_sessions_find(since?, until?, query?, folder?, unfiled?, offset?, limit?)`

One general query over all scanned sessions (user's request: filter by date and hours, names, folders). All parameters are optional and combine with AND. Sessions come newest first (`lastModified`), one page at a time:

| Parameter | Meaning |
|---|---|
| `since`, `until` | bounds on `lastModified`, inclusive. Either an ISO-8601 date or local date-time (`2026-10-17`, `2026-10-24T09:00`, in the IDE's time zone; a bare date as `since` means its start, as `until` its end), or relative to now: `<n>d` or `<n>h` (`7d`, `12h`). Anything else is an error. |
| `query` | the tree's filter: every word must occur, case-insensitively, in the title or the project path (`ClaudeSession.matches`) |
| `folder` | a folder path: sessions filed in it or any folder below it |
| `unfiled` | `true`: only sessions under `sessions`. Together with `folder`: error |
| `offset`, `limit` | paging; `limit` defaults to 50, at most 100 *(my call: 50 entries with prompts stay well under Claude Code's 25k-token MCP output limit; a 7-day window held 130 sessions on the user's machine)* |

Result:

```json
{"total": 130, "offset": 0, "sessions": [
  {"id": "…", "title": "…", "project": "E:\\dev\\projects\\rtm-cloud", "modified": "2026-10-24T13:57:09+02:00",
   "folder": "rtm/fixes", "prompts": ["first human prompt…", "second…", "third…"], "changedFiles": 4}]}
```

- `total` counts all matches, so the agent knows whether to fetch the next page. `offset` beyond `total` gives an empty page, not an error.
- Filing already-filed sessions elsewhere is in scope: they are returned with their `folder`, and the agent may propose moving them.
- Parsing and filtering live in a pure, unit-tested function over (sessions, structure, now, zone); the tool only fetches the state. The IDE's MCP server accepts optional parameters: its `CallableBridge` checks `KParameter.isOptional` and calls through `callSuspendBy` (read from this IDE build's bytecode), so they are nullable with `null` defaults.
- `folder` is the path of the folder the session is filed in, or `null` (under `sessions`).
- `prompts`: the first 3 human prompts of the transcript, each cut to 300 characters *(my call: title and project alone are often not enough to judge the topic)*. The scanner already finds the first human prompt for the title; it keeps the first 3 instead. Same notion of "human prompt" as the title.

### `my_air_folders_get()`

The folder tree in the user's order:

```json
[{"path": "rtm", "sessions": ["<id>", …], "folders": [{"path": "rtm/fixes", "sessions": [], "folders": []}]}]
```

Fails with the read error when `structure.json` is unreadable (same text Folders mode shows).

### `my_air_folders_apply(changes: String)`

`changes` is a JSON array, applied in order, each change seeing the result of the previous ones:

| `op` | fields | effect |
|---|---|---|
| `create` | `path` | new folder, last among its siblings; the parent must exist (earlier in the list or before) |
| `rename` | `path`, `name` | |
| `move` | `path`, `to` (parent path, `null` for top level), optional `index` (default: last) | |
| `delete` | `path` | the folder and its subtree; their sessions return to `sessions` |
| `assign` | `session` (id), `path` (`null` returns it to `sessions`) | the session must be in the current scan |

- Atomic: all changes run against one fresh read inside `StructureStore.update`; any invalid change throws and nothing is written. One call = one snapshot.
- Unknown path, unknown session id, unknown `op`, missing field, duplicate sibling name, move into own subtree, bad index: error naming the change by its position and content.
- Result: the tree after the change, as `my_air_folders_get` returns it.
- Description text tells the agent: apply only what the user approved in the conversation; confirm each `delete` separately first.

### `my_air_history_list()`

Snapshots newest first: `[{"name": "structure-20261024-135709-538-mcp.json", "taken": "…", "source": "mcp", "folders": 12, "sessions": 40}]`. A snapshot that does not parse is listed with `"error": "<message>"` instead of the counts, so a bad copy is visible but does not hide the others *(my call)*.

### `my_air_history_restore(name: String)`

Replaces `structure.json` with that snapshot, through `StructureStore.update`, so the replaced version is itself snapshotted and the restore can be undone. Unknown name or unparsable snapshot: error, nothing written.

## Paths

- A folder's path is its name and its ancestors' names joined with `/`. Names are unique among siblings (case-insensitive), so a path names one folder; lookup is case-insensitive like the uniqueness rule.
- **`/` becomes illegal in folder names** *(my call)*, enforced by the `SessionFolders` invariant and by the name dialog in the tree. Existing files containing such a name then fail to parse, loudly, as any invalid file does.

## History

- `StructureStore.update` copies the current `structure.json` (when it exists and the change differs) to `~/.my-air/history/structure-<yyyyMMdd-HHmmss-SSS>-<source>.json` before writing, then deletes all but the newest 20 snapshots by name order. Name collision within one millisecond: the copy fails loudly rather than overwriting.
- `<source>` says what made the change the snapshot precedes *(my call)*: `ui` (tree action or drop), `auto` (new-session assignment, `/clear` carry-over), `mcp` (`folders_apply`), `restore` (`history_restore`). `update` takes the source as a parameter. `history_list` returns it as `"source"`.
- All sources count toward the 20 *(my call)*: a burst of automatic writes can push older snapshots out, which is acceptable for an undo of recent changes.
- Undo semantics, stated in the `history_restore` description: "undo the last change I asked for" restores the newest `mcp` snapshot. Restoring replaces the whole file, so changes made after that snapshot (`ui`, `auto` entries newer than it in the list) are lost; the agent names them to the user and asks before restoring when there are any.
- Applies to every write: tree actions, drops, new-session assignment, `/clear` carry-over, MCP.
- `history/` sits inside the watched `~/.my-air/` but is a subdirectory, so the non-recursive watch does not reload on snapshots (at most one cheap no-op refresh).

## Shape of the code (guidance)

- `FolderChanges` (pure, unit-tested): parses the `changes` JSON strictly and applies it to a `SessionFolders` given the set of scanned session ids; path resolution and the "unknown is an error" rule live here, not in `SessionFolders`, whose no-op semantics stay for the UI.
- `StructureHistory(dir, keep = 20)` (unit-tested with a temp dir): `snapshot(text)`, `list()`, `read(name)`; used by `StructureStore`.
- `FoldersMcpToolset`: thin; reads `ClaudeSessionsService` state on the EDT where needed, calls `updateFolders` so panels refresh at once.
- JSON output with Gson, as elsewhere.

## Tests

- `FolderChanges`: each op; ordering (create then assign into it; rename then refer by new path); every error case listed above leaves the input untouched; case-insensitive path lookup.
- `StructureHistory`/`StructureStore`: snapshot before each differing write and none for an unchanged one; pruning to 20; restore snapshots the replaced version; unparsable snapshot listed with an error and refused by restore.
- `SessionFolders`: `/` in a name rejected.
- Scanner: first 3 human prompts, cut to 300 characters.
- Session query: `since`/`until` as date, date-time and relative forms, inclusive bounds, bare-date `until` covering the whole day; `query`; `folder` including subfolders; `unfiled`; `folder` with `unfiled` rejected; malformed time rejected; paging with `total`, default and maximum `limit`, `offset` past the end.
- Manual: in a Claude Code session connected to the IDE's MCP server, ask for the 7-day review, approve a table, see the trees update; ask to undo.

## Left to the builder

- Exact description texts of the tools (they must state the workflow above).
- Class and file names beyond those given; the JSON field order.
