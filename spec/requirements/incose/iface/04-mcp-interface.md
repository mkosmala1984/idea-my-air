# MCP Interface – My Air System

**Related High-Level View Section:** 3.8  
**Related Functional Requirements:** FR-MT-*

---

## 1. Overview

The My Air plugin exposes tools through the IDE's MCP (Model Context Protocol) server, allowing external AI agents to interact with session management, folder organization, and plan tracking.

The tools never ask the user anything themselves. Where a tool description says the agent must get the user's approval first, that is an instruction to the agent in the tool description; no code enforces it. Every write to the folder structure leaves a snapshot that can be restored.

---

## 2. MCP Toolset Registration

### 2.1 Registration File

**Path:** `src/main/resources/META-INF/my-air-mcp.xml`

```xml
<idea-plugin>
  <extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="dev.myair.mcp.FoldersMcpToolset"/>
    <mcpToolset implementation="dev.myair.mcp.BriefMcpToolset"/>
  </extensions>
</idea-plugin>
```

`plugin.xml` loads it through an optional dependency: `<depends optional="true" config-file="my-air-mcp.xml">com.intellij.mcpServer</depends>`.

### 2.2 Toolset Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `FoldersMcpToolset` | `dev.myair.mcp` | Folder management, session search, session start, plan management, history |
| `BriefMcpToolset` | `dev.myair.mcp` | Session brief description |

### 2.3 Toolset Properties

| Property | Value | Description |
|----------|-------|-------------|
| `isExperimental()` | `false` | Tools visible by default (both toolsets) |
| `isEnabled()` | `BriefMcpToolset`: `MyAirSettings.briefs`; `FoldersMcpToolset`: not overridden (always enabled) | Brief tools enabled via settings |

---

## 3. FoldersMcpToolset

All tools of this toolset that read or write folders fail with `Cannot read <structure.json path>: <error>` while `~/.my-air/structure.json` cannot be read.

### 3.1 Tool: `my_air_sessions_find`

**Purpose:** Search Claude Code sessions with flexible filtering.

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `since` | String | No | Earliest last message (inclusive): `<n>d` or `<n>h` before now, an ISO date, or an ISO local date-time |
| `until` | String | No | Latest last message (inclusive), same forms as since; a date covers the whole day |
| `query` | String | No | Words that must each occur, ignoring case, in the title, the project path (cwd), the branch the session started on, or the branch checked out where it works now |
| `folder` | String | No | Folder path: sessions placed in it or any folder below it |
| `unfiled` | Boolean | No | true: only unfiled sessions; false: only filed ones. `true` cannot be combined with `folder` |
| `offset` | Int | No | Matches to skip (default 0, must not be negative) |
| `limit` | Int | No | Page size (default 50, range 1..100) |

#### Response

```json
{
  "total": 125,
  "offset": 0,
  "sessions": [
    {
      "id": "<session-id>",
      "title": "Session title",
      "project": "E:\\dev\\projects\\my-air",
      "lastMessage": "2026-11-07T19:02:40.710+01:00",
      "folder": "rtm/fixes",
      "prompts": ["First human prompt..."],
      "changedFiles": 12
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `total` | Int | Number of all matches |
| `offset` | Int | Matches skipped |
| `sessions[].id` | String | Session id |
| `sessions[].title` | String | Session title |
| `sessions[].project` | String | The directory claude ran in (cwd) |
| `sessions[].lastMessage` | String | ISO-8601 offset date-time of the latest message, in the IDE's time zone |
| `sessions[].folder` | String/null | Path of the folder the session shows in: the plan folder of the task its brief names, else where the user filed it, else null when the user kept it in `sessions`, else the folder its brief names, else the folder its project is bound to; null when unfiled |
| `sessions[].prompts` | String[] | The session's first human prompts, each cut to 300 characters |
| `sessions[].changedFiles` | Int | Number of files the session changed |

#### Constraints

- All filters optional and combinable
- Results ordered newest first
- Pagination via offset/limit; fetch the next page with offset until `total` is covered
- Time forms: `7d`, `12h`, `2026-10-17`, `2026-10-24T09:00`; dates are in the IDE's time zone
- Errors: unknown time form, `folder` with `unfiled=true`, unknown folder (`no folder '<path>'`), limit outside 1..100, negative offset

---

### 3.2 Tool: `my_air_session_start`

**Purpose:** Create a new Claude Code session in a My Air tab of the IDE project the call came through.

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `prompt` | String | Yes | The session's first message |
| `dir` | String | No | Project directory (default: the folder's start directory when `folder` is given, else the configuration's, else the IDE project's) |
| `model` | String | No | `claude --model`: opus, sonnet, haiku, or full model id |
| `effort` | String | No | `claude --effort`: low, medium, high, xhigh, max |
| `add_dirs` | String | No | JSON array of strings: directories for `--add-dir` |
| `worktree` | Boolean | No | true: start in a new worktree of dir's repository |
| `base` | String | No* | Required with worktree: the branch the worktree checks out (a remote one: its local namesake); when another worktree holds it and cannot be freed, a new branch off it |
| `branch` | String | No | Only with worktree: new branch of this name off base, checked out in the worktree |
| `plan_task` | String | No | Plan task id (e.g., T12): the session is filed in that task's plan folder and gets the plan as context |
| `preset` | String | No | Name of a saved session configuration; the other arguments replace its fields |
| `folder` | String | No | Folder path: the session is filed there and, without `dir`, runs in the working directory or bound directory of that folder or the nearest folder above it having either |

*Required when the effective `worktree` (argument, else the configuration's) is true. `worktree: false` also drops the configuration's base.

#### Response

```json
{
  "id": "<new-session-id>",
  "cwd": "E:\\dev\\projects\\my-air\\.claude\\worktrees\\main-2026-11-08-3fb8",
  "project": "E:\\dev\\projects\\my-air"
}
```

`cwd` is where the session runs (the new worktree when one was made); `project` is the project directory.

#### Constraints

- The tool description tells the agent to start a session only when the user asked for it in the conversation; this is not enforced by code
- Worktree requires a base; `git` failures (e.g., unknown branch) are returned with git's stderr
- Branch only valid with worktree (`branch without worktree`)
- Errors returned to agent, not shown as dialogs: unknown configuration (lists the configurations), unreadable `presets.json`, unknown folder, no folder at or above `folder` with a working or bound directory, unknown plan task (lists the open tasks), invalid `add_dirs` JSON, directories that do not exist (`not directories: ...`)
- Arguments given replace the configuration's values

---

### 3.3 Tool: `my_air_folders_get`

**Purpose:** Read the user's folder tree.

#### Parameters

None.

#### Response

A JSON array of the top-level folders in the user's order; each folder holds its subfolders under `folders`.

```json
[
  {
    "path": "rtm",
    "dirs": [],
    "workdir": null,
    "plan": null,
    "sessions": [],
    "folders": [
      {
        "path": "rtm/fixes",
        "dirs": [],
        "workdir": "E:\\dev\\rtm",
        "plan": null,
        "sessions": ["session-id-1", "session-id-2"],
        "folders": []
      },
      {
        "path": "rtm/my-air",
        "dirs": ["E:\\dev\\my-air"],
        "workdir": null,
        "plan": "5/12 tasks done",
        "sessions": [],
        "folders": []
      }
    ]
  }
]
```

#### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `path` | String | Folder path (names joined by /) |
| `dirs` | String[] | Bound directories (empty for most) |
| `workdir` | String/null | Working directory for new sessions started from the folder or below |
| `plan` | String/null | For a plan folder `"<done>/<total> tasks done"` (canceled tasks not counted in total), else null |
| `sessions` | String[] | Ids of sessions the user filed directly in the folder (sessions placed there by their brief are not listed) |
| `folders` | Array | Child folders, same shape |

---

### 3.4 Tool: `my_air_folders_apply`

**Purpose:** Apply changes to folder structure (all-or-nothing).

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `changes` | String | Yes | JSON array of change objects, applied in order, each seeing the result of the previous ones |

#### Change Operations

Each change is an object with `op` and only the fields listed; an unknown `op` or an unknown field is an error.

| Operation | Fields | Description |
|-----------|--------|-------------|
| `create` | `path`, `dirs?`, `plan?` | Create folder; the parent must exist and the name must be free. `dirs`: array of strings, each a directory some scanned session ran in and not bound yet. `plan`: boolean; a plan folder may not lie in a plan folder |
| `add_plan` | `path` | Make an existing folder a plan folder (refused when it has a plan, lies in a plan folder or holds one) |
| `bind` | `path`, `dir` | Bind a directory to the folder; `dir` must be a directory some scanned session ran in and bound to no folder |
| `unbind` | `dir` | Unbind a directory (must be bound) |
| `set_workdir` | `path`, `dir` | Set the working directory; `dir` key required, null clears it, otherwise an absolute existing directory |
| `rename` | `path`, `name` | Rename folder |
| `move` | `path`, `to`, `index?` | Move folder; `to` key required, null is the top level; `index` default last, within 0..number of siblings; a folder cannot move into itself |
| `delete` | `path` | Delete folder (its sessions go to their brief's or project's folder, else become unfiled; a plan in it is deleted too; its bindings are removed) |
| `assign` | `session`, `path` | File a session (must be a scanned session); `path` key required, null takes it out of its folder |

#### Examples

```json
[
  {"op": "create", "path": "rtm/fixes"},
  {"op": "create", "path": "rtm/my-air", "dirs": ["E:\\dev\\my-air"], "plan": true},
  {"op": "bind", "path": "rtm/my-air", "dir": "E:\\dev\\my-air-web"},
  {"op": "set_workdir", "path": "rtm/fixes", "dir": "E:\\dev\\rtm"},
  {"op": "rename", "path": "rtm/fixes", "name": "bugs"},
  {"op": "move", "path": "rtm/bugs", "to": "testflo", "index": 0},
  {"op": "delete", "path": "testflo/bugs"},
  {"op": "assign", "session": "<id>", "path": null}
]
```

#### Response

The folder tree after the change (same format as `my_air_folders_get`).

#### Constraints

- All-or-nothing: any failure leaves the structure unchanged
- Changes leave a history snapshot (source `mcp`)
- The tool description tells the agent to show the user a table of the proposed filing and wait for approval, and to ask about every delete separately; this is not enforced by code
- Folders matched ignoring case

---

### 3.5 Tool: `my_air_plan_get`

**Purpose:** Read plans for a project directory.

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dir` | String | Yes | A directory of the project (normally the session's working directory) |

#### Response

A JSON array: the plan folder that the folder bound to the directory (or to one above it) is or lies in, else every plan folder below that bound folder.

```json
[
  {
    "folder": "rtm/my-air",
    "dirs": ["E:\\dev\\my-air"],
    "workdir": null,
    "description": "Plan description in Markdown...",
    "milestones": [
      {
        "id": "M1",
        "title": "Milestone title",
        "description": "Description...",
        "status": "open",
        "progress": "1/3",
        "tasks": [
          {
            "id": "T2",
            "title": "Task title",
            "description": "Description...",
            "status": "in_progress",
            "next": "Next step...",
            "sessions": [
              {
                "id": "session-id-1",
                "title": "Session title",
                "lastMessage": "2026-11-07T19:02:40.710+01:00",
                "status": "working"
              }
            ]
          }
        ]
      }
    ]
  }
]
```

| Field | Type | Description |
|-------|------|-------------|
| `folder` | String | Plan folder path (the plan's name), which `my_air_plan_apply` takes |
| `dirs` | String[] | Directories bound to the plan folder |
| `workdir` | String/null | The plan folder's working directory |
| `description` | String/null | Plan description (Markdown) |
| `milestones[].status` | String | `open` or `done` |
| `milestones[].progress` | String | `"<done>/<total non-canceled>"` of its tasks |
| `tasks[].status` | String | Task status (below) |
| `tasks[].next` | String/null | Next step |
| `tasks[].sessions` | Array | Sessions whose brief names the task, newest first: `id`, `title`, `lastMessage` (ISO offset date-time), `status` (`working`, `needs_input`, `idle`, or null without a process) |

#### Task Status Values

- `pending`
- `in_progress`
- `waiting` (waiting for the user)
- `blocked` (blocked by someone else)
- `done`
- `canceled`

#### Constraints

- Fails when directory has no plan; the message names the bound folder, if any, and how to create a plan folder
- Returns plans as JSON array
- Each task lists sessions working on it

---

### 3.6 Tool: `my_air_plan_apply`

**Purpose:** Modify a plan (all-or-nothing).

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `plan` | String | Yes | Plan's folder path (as `my_air_plan_get` gives it); `no plan folder at '<path>'` otherwise |
| `changes` | String | Yes | JSON array of change objects, applied in order |

#### Change Operations

Each change is an object with `op` and only the fields listed; an unknown `op` or an unknown field is an error. Ids must belong to this plan. Titles, descriptions and next steps must not be blank.

| Operation | Fields | Description |
|-----------|--------|-------------|
| `set_description` | `description` | Set the plan description; key required, null clears it |
| `add_milestone` | `title`, `description?`, `index?` | Add milestone (index default last) |
| `add_task` | `milestone`, `title`, `description?`, `index?` | Add task to milestone |
| `update` | `id`, `title?`, `description?`, `status?`, `next?` | Update a milestone or task; description or next null clears it. A milestone takes status `open` or `done` and no `next`; a task takes a task status |
| `move` | `id`, `milestone?`, `index` | Move a task (to `milestone`, default its own) or a milestone within its plan (no `milestone` field); `index` required, the position after the move |
| `delete` | `id` | Delete task; a milestone only when all its tasks are done or canceled |

#### Examples

```json
[
  {"op": "set_description", "description": "Updated plan description"},
  {"op": "add_milestone", "title": "New milestone", "description": "...", "index": 0},
  {"op": "add_task", "milestone": "M1", "title": "New task", "description": "..."},
  {"op": "update", "id": "T4", "status": "waiting", "next": "Awaiting review"},
  {"op": "move", "id": "T4", "milestone": "M2", "index": 0},
  {"op": "delete", "id": "T4"}
]
```

#### Response

The plan after the change: one plan object in the format of an element of the `my_air_plan_get` array.

#### Constraints

- All-or-nothing: any failure leaves the structure unchanged
- Changes leave a history snapshot (source `mcp`)
- New IDs (M<n>, T<n>) unique across all plans and never reused
- The tool description tells the agent to delete or move milestones and tasks only after the user agreed; this is not enforced by code

---

### 3.7 Tool: `my_air_history_list`

**Purpose:** List structure history snapshots, newest first, each taken just before a change.

#### Parameters

None.

#### Response

```json
[
  {
    "name": "structure-20261107-180240-710-mcp.json",
    "taken": "2026-11-07T18:02:40.710",
    "source": "mcp",
    "folders": 15,
    "sessions": 42
  }
]
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Snapshot file name, which `my_air_history_restore` takes |
| `taken` | String | Local date-time (no zone) the snapshot was taken |
| `source` | String | What made the change (below) |
| `folders` | Int | Number of folders in the snapshot |
| `sessions` | Int | Number of sessions filed in the snapshot's folders |
| `error` | String | Present instead of `folders`/`sessions` when the snapshot does not parse |

#### Source Values

- `ui` – User in the tree
- `auto` – New session filed or continued by /clear
- `mcp` – Change list via MCP
- `brief` – Folder created by session brief
- `restore` – A restore operation

---

### 3.8 Tool: `my_air_history_restore`

**Purpose:** Restore folder structure, plans included, from a snapshot.

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | String | Yes | The snapshot's name as `my_air_history_list` gives it; any other name is refused |

#### Response

The folder tree after the restore (same format as `my_air_folders_get`).

#### Constraints

- Replaced version is also snapshotted (source `restore`)
- Restoring replaces everything
- A snapshot that does not parse is refused: `snapshot '<name>' is not a valid structure: ...`
- The tool description tells the agent to name the snapshots newer than the one restored and ask the user first; this is not enforced by code

---

## 4. BriefMcpToolset

### 4.1 Tool: `my_air_set_brief`

**Purpose:** Describe the session for the user's session list. Nothing is stored by the tool except a folder it creates; the call and its result land in the session's transcript, which the scanner reads.

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `goal` | String | Yes | One non-blank sentence (max 200 chars): what session is for |
| `tasks` | String | Yes | JSON array of tasks (max 15, each with content and status, at most one in_progress); `[]` when none |
| `plan_task` | String | No | ID of plan task this session works on (task id form such as T12; must exist) |
| `folder` | String | No | Folder path to file session in, matched ignoring case |
| `title` | String | No | Non-blank session title (max 60 chars) |

#### Tasks Format

```json
[
  {
    "content": "Task description (non-blank, max 120 chars)",
    "status": "pending"
  }
]
```

`status` is one of `pending`, `in_progress`, `completed`.

#### Response

A plain string, not JSON:

- `ok` – without `folder`
- `ok, folder <folder-id> at '<path>'` – with `folder`, an existing folder
- `ok, folder <folder-id> at '<path>' (created)` – with `folder`, a folder the call created

#### Constraints

- Call when goal understood and when it changes; every call replaces the whole brief
- Tasks as outcomes, not tool steps
- Write in user's language
- Folder/title only when filing enabled (`MyAirSettings.fileSessions`); otherwise the error `filing sessions is turned off; leave out folder and title`
- Missing folder created one level below an existing folder; never at the top level, and never at or in a plan folder
- `plan_task`, `folder` and `title` keep their value when a later call leaves them out
- User's own filing wins over agent's

---

## 5. Error Handling

### 5.1 Error Format

Input errors (`IllegalArgumentException`) and `git` failures (`GitFailure`, message carrying git's stderr) are thrown as `McpExpectedError` with their message, e.g. `branch without worktree`. The IDE's MCP server turns that into the tool's error result. Other exceptions (broken internal invariants) are not converted.

### 5.2 Error Categories

| Category | Examples |
|----------|----------|
| **Input validation** | Invalid parameters, missing required fields |
| **Git failures** | Worktree creation fails, branch not found |
| **State errors** | No folder at path, no plan folder, unreadable `structure.json` |
| **Permission errors** | Filing turned off but folder/title provided |

### 5.3 Error Handling Principles

- Errors returned to agent, not shown as dialogs
- Clear, actionable error messages
- Input validation before execution
- No silent failures

---

## 6. JSON Format Conventions

### 6.1 Naming Conventions

- **Tool names:** `my_air_<resource>_<verb>` (e.g., `my_air_sessions_find`), except `my_air_set_brief`
- **Parameters:** snake_case
- **Response fields:** camelCase
- **Dates:** inputs ISO 8601 or relative (`7d`, `12h`); outputs ISO 8601 strings (offset date-time; history `taken` as local date-time)

### 6.2 Response Structure

| Tool Type | Response Format |
|-----------|-----------------|
| Read (get/find) | Resource data or list |
| Write (apply/start) | Updated resource or new resource ID |
| List | Array of items with metadata |

### 6.3 Pagination

- `offset` – Matches to skip (parameter, echoed in the response)
- `limit` – Page size (parameter only)
- `total` – Total matches (response)
- Fetch next page with `offset += limit` until covered

---

## 7. Security Considerations

### 7.1 User Approval

Approval rules are instructions to the agent in the tool descriptions; no code asks the user or blocks the call.

| Operation | Approval the description asks for |
|-----------|-------------------|
| `my_air_session_start` | User must ask in conversation |
| `my_air_folders_apply` | Show table, wait for ok; ask about each delete |
| `my_air_plan_apply` (delete/move) | User agreed |
| `my_air_history_restore` | Ask when newer snapshots exist |

### 7.2 Data Protection

- No session content exposed beyond search results
- Filing decisions can be overridden by user
- History snapshots provide undo capability
- Agent cannot delete sessions

### 7.3 Access Control

- Tools available when MCP Server plugin enabled
- Brief tools additionally gated by settings
- No direct file system access for agents

---

## 8. Tool Availability

### 8.1 Detection

- Tools registered via `my-air-mcp.xml`
- Available when MCP Server plugin enabled
- Plugin works without MCP Server (tools just unavailable)

### 8.2 Status Indication

- `isExperimental()` returns false (tools visible)
- `isEnabled()` checks settings (for Brief tools)
- No errors when MCP Server disabled

---

## 9. Traceability

| Tool | FR Coverage | Requirements Doc |
|------|-------------|------------------|
| `my_air_sessions_find` | FR-MT-002 | fr/08-mcp-tools.md §FR-MT-002 |
| `my_air_session_start` | FR-MT-007 | fr/08-mcp-tools.md §FR-MT-007 |
| `my_air_folders_get` | FR-MT-003 | fr/08-mcp-tools.md §FR-MT-003 |
| `my_air_folders_apply` | FR-MT-003 | fr/08-mcp-tools.md §FR-MT-003 |
| `my_air_plan_get` | FR-MT-004 | fr/08-mcp-tools.md §FR-MT-004 |
| `my_air_plan_apply` | FR-MT-004 | fr/08-mcp-tools.md §FR-MT-004 |
| `my_air_history_list` | FR-MT-005 | fr/08-mcp-tools.md §FR-MT-005 |
| `my_air_history_restore` | FR-MT-005 | fr/08-mcp-tools.md §FR-MT-005 |
| `my_air_set_brief` | FR-MT-006 | fr/08-mcp-tools.md §FR-MT-006 |

---

## 10. Testing

### 10.1 Test Strategy

| Tool | Test Type | Coverage |
|------|-----------|----------|
| `my_air_sessions_find` | Unit tests | Filtering, pagination, sorting |
| `my_air_session_start` | Unit tests | Merging arguments with a configuration (`SessionStartTest`) |
| `my_air_folders_*` | Unit tests | CRUD operations, validation |
| `my_air_plan_*` | Unit tests | Milestone/task management |
| `my_air_history_*` | Unit tests | Snapshot listing, restore |
| `my_air_set_brief` | Unit tests | Brief validation, folder creation |

### 10.2 Test Scenarios

- Valid inputs with expected results
- Invalid inputs with clear error messages
- Edge cases (empty results, max limits)
- Concurrent operations
- Error recovery
