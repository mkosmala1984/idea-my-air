# File Format Interface – My Air System

**Related High-Level View Section:** 6.2  
**Related Functional Requirements:** FR-SD-*, FR-SM-*, FR-SO-*, FR-TW-*

---

## 1. Overview

The My Air system uses file-based interfaces for all data exchange. This document specifies the exact file formats used by the plugin and taskbar widget, including schema definitions, validation rules, and compatibility considerations. The plugin never talks to a `claude` process; it reads the files `claude` writes and owns the files under `~/.my-air/`.

---

## 2. File Format Matrix

| File | Location | Format | Owner | Access | Description |
|------|----------|--------|-------|--------|-------------|
| Claude transcripts | `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl` | JSONL | Claude Code | Read | Session message history |
| Claude registry | `~/.claude/sessions/<pid>.json` | JSON | Claude Code | Read | Live session registry |
| Desktop sessions | `%LOCALAPPDATA%/Claude-Data/*/claude-code-sessions/*/*/local_*.json` | JSON | Claude Desktop | Read | Folder a Desktop session started in |
| Plugin and skill metadata | `~/.claude/plugins/installed_plugins.json`, `~/.claude/plugins/known_marketplaces.json`, `~/.agents/.skill-lock.json` | JSON | Claude Code / `skills` tool | Read | Skill origin |
| Git worktree links | `<checkout>/.git/worktrees/*/gitdir` | Text | git | Read | Linked worktrees of a checkout |
| IDE state | `~/.my-air/ides/<pid>.json` | JSON | My Air | Read/Write | Per-IDE tab list |
| Focus requests | `~/.my-air/requests/<pid>/<uuid>.json` | JSON | My Air, taskbar widget | Read/Write | Cross-IDE focus requests |
| Pins | `~/.my-air/pins.json` | JSON | My Air | Read/Write | Pinned, dismissed and settled sessions |
| Structure | `~/.my-air/structure.json` | JSON | My Air | Read/Write | Folder organization and plans |
| History | `~/.my-air/history/structure-*.json` | JSON | My Air | Read/Write | Structure snapshots |
| Presets | `~/.my-air/presets.json` | JSON | My Air | Read/Write | Session configurations |
| Seen | `~/.my-air/seen/<sessionId>` | Text | My Air, taskbar widget | Read/Write | Last seen timestamps |
| Board | `~/.my-air/taskbar/board.json` | JSON | My Air | Write (widget reads) | Taskbar board data |
| Images | `~/.my-air/images/<uuid>.png` | PNG | My Air | Read/Write | Pasted prompt images |
| Scan cache | `~/.my-air/scan-cache-<hash>.json` | JSON | My Air | Read/Write | Scanner state |
| Prompt files | `~/.my-air/prompts/<IDE pid>-<uuid>.md` | Markdown | My Air | Write (claude reads) | Plan system prompt of a session tab |
| Brief reminder hook | `~/.my-air/hooks/brief-reminder.json` | JSON | My Air | Write (claude reads) | `--settings` file with a `UserPromptSubmit` hook |

---

## 3. Claude Code File Formats

### 3.1 Transcript Format (JSONL)

**Location:** `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`. Transcripts of subagents one level deeper are not read.

**Encoding:** UTF-8, one JSON object per line. Only complete lines (ending in a newline) are read; transcripts are append-only, so a rescan reads from the stored byte offset.

**Session id:** the file name without `.jsonl`; it is not read from the lines.

**Message lines** (`user`, `assistant`, or `system` with `subtype` `local_command`):

```json
{
  "type": "assistant",
  "timestamp": "2026-11-07T18:02:40.710Z",
  "cwd": "E:\\dev\\projects\\my-air",
  "gitBranch": "main",
  "isSidechain": false,
  "effort": "medium",
  "message": {
    "model": "claude-opus-5-5",
    "content": [{"type": "text", "text": "..."}]
  }
}
```

**Fields read:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | String | Yes | Line type; `user`, `assistant`, `system` are messages; other types listed below |
| `subtype` | String | No | `local_command` makes a `system` line a message |
| `timestamp` | String | Yes on message lines | ISO-8601 instant; a message line without it throws |
| `isSidechain`, `isMeta` | Boolean | No | true: not part of the main conversation (not counted for date, model, prompts) |
| `cwd` | String | No | The session's cwd comes from the first line carrying it |
| `gitBranch` | String | No | Of that same line: the branch the session started on (`HEAD` means none) |
| `effort` | String | No | Top-level effort of the last main assistant line |
| `message.model` | String | No | Model of the last main assistant line; `<synthetic>` (claude's own error replies) is skipped |
| `message.content` | String or Array | Yes on message lines | A string, or an array of blocks (`text`, `tool_use`, `tool_result`) |
| `toolUseResult` | Object | No | Result of a tool call (Artifact publish, background task start) |

**Other line types read:**

| Line | Fields | Use |
|------|--------|-----|
| `{"type":"custom-title", ...}` | `customTitle` | Title set by `/rename` (last one wins) |
| `{"type":"ai-title", ...}` | `aiTitle` | Generated title |
| `{"type":"continued-in", ...}` | `continuedInSessionId` | `/bg` continued the session under a new id |
| `attachment` of type `environment` | `attachment.snapshot.workingDirectory`, `attachment.snapshot.additionalWorkingDirectories` (both required) | Where the session works now, added directories |
| `attachment` of type `skill_listing` | `names`, `isInitial` (both required) | Skills offered |
| `attachment` of type `deferred_tools_delta` | `addedNames`, `removedNames` (both required) | MCP tools (`mcp__<server>__<tool>`) |
| `{"type":"queue-operation","operation":"enqueue", ...}` | `content` with `<task-notification>` (`<task-id>`, `<status>`, `<summary>`, `<output-file>`, `<usage>`) | End of a background task |

Assistant `tool_use` items named `Write`, `Edit`, `MultiEdit`, `NotebookEdit` (`input.file_path` or `input.notebook_path`) give the changed files; `my_air_set_brief` calls and their results give the brief.

**Validation Rules:**

- Each complete line must be valid JSON
- Lines of types not listed above are skipped
- Unknown fields ignored (forward compatibility)
- Fields marked required above throw when missing; an unknown task-notification `<status>` throws

**Extraction Logic:**

- Title: the last `custom-title`, else the brief's `title`, else the last `ai-title`, else the first human prompt
- Date: `timestamp` of the last main message line (not the file's mtime)
- Model and effort: of the last main assistant line, skipping `<synthetic>`
- Live status is not taken from the transcript but from the registry (§3.2)
- One session may lie in several project directories (`--resume` from another folder copies it); the copy with the latest message, else the longest, counts

### 3.2 Registry Format (JSON)

**Location:** `~/.claude/sessions/<pid>.json`

**Schema:**

```json
{
  "pid": 12345,
  "sessionId": "string",
  "kind": "interactive",
  "status": "busy",
  "statusUpdatedAt": 1794070960710,
  "waitingFor": "dialog open",
  "entrypoint": "cli",
  "procStart": "134064544000000000",
  "cwd": "E:\\dev\\projects\\my-air",
  "startedAt": 1794070960710
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `pid` | Long | Yes | Process ID |
| `sessionId` | String | Yes | Session identifier (rewritten by `/clear` and `/resume`) |
| `kind` | String | Yes | `interactive` or `bg` (background job worker); `daemon` and `daemon-worker` are skipped; any other value throws |
| `spare` | Boolean | No | true: spare worker started ahead for the next job; skipped |
| `status` | String | Yes | Current status |
| `statusUpdatedAt` | Long | No | Epoch ms of the last status change; a file without it belongs to a `claude` still starting and is skipped |
| `waitingFor` | String | No | What it's waiting for |
| `entrypoint` | String | Yes | What started it, e.g. `cli` or `claude-desktop` |
| `procStart` | String | Yes | Process start time as a Windows FILETIME (100 ns ticks since 1601), a decimal string |
| `cwd` | String | Yes | Directory the process started in |
| `startedAt` | Long | Yes | Start time (epoch ms) |
| `jobId` | String | Yes when `kind` is `bg` | Background job ID |
| `parkedJobId` | String | No | Job that `/bg` parked this process into; such a file is not an entry of its own but listed under that job's entry |

**Validation Rules:**

- `status` must be one of: `busy`, `shell`, `waiting`, `idle`
- Unknown status values throw exceptions (fail loudly)
- `procStart` validated against actual process start time
- A file whose pid does not belong to the process that wrote it (gone or reused pid) is skipped
- When a job's worker and another process show one session, the worker is the entry

**Status Mapping:**

| Registry Status | Session Status | Description |
|-----------------|----------------|-------------|
| `busy` | Working | Actively processing |
| `shell` | Working | Running shell command |
| `waiting` | NeedsInput | Waiting for user input |
| `idle` | Idle | Waiting for the next prompt |

### 3.3 Desktop Session Format (JSON)

**Location:** `%LOCALAPPDATA%/Claude-Data/<profile>/claude-code-sessions/<account>/<org>/local_<id>.json`

Only two top-level fields are read; the rest (large prompt and tool snapshots) is skipped.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `originCwd` | String | Yes | Folder the user started the Desktop session in; becomes the session's project |
| `cliSessionId` | String | No | The `claude` session id; a Desktop session without one has no transcript and is ignored |

A `claude` session named by two Desktop files throws. Each file is parsed again only when its mtime changes. A missing data directory means Claude Desktop is not installed.

### 3.4 Plugin and Skill Metadata (JSON)

Read only to tell a skill's origin; nothing is fetched from the network.

| File | Fields read |
|------|-------------|
| `~/.claude/plugins/installed_plugins.json` | `plugins.<plugin>@<marketplace>[]`: `installPath` (required), `version`, `gitCommitSha` |
| `~/.claude/plugins/known_marketplaces.json` | `<marketplace>.installLocation`, `.lastUpdated` (ISO instant), `.source.source`, `.source.repo` |
| `<installLocation>/.claude-plugin/marketplace.json` | `plugins[]`: `name`, `version`, `source` (path string, or object with `repo`/`url`/`sha`) |
| `<plugin>/.claude-plugin/plugin.json` | `version` |
| `~/.agents/.skill-lock.json` | `skills.<name>`: `source` (required), `sourceType`, `skillPath`, `updatedAt` |

### 3.5 Git Worktree Links (Text)

**Location:** `<checkout>/.git/worktrees/*/gitdir`, read for every directory sessions ran in. Each holds the path of a linked worktree's `.git` file; its parent is the worktree. A session whose cwd lies in such a worktree gets that main checkout as its project.

---

## 4. My Air State File Formats

### 4.1 IDE State Format

**Location:** `~/.my-air/ides/<pid>.json`

**Schema:**

```json
{
  "pid": 12345,
  "started": 1794070960710,
  "tabs": [
    {
      "sessionIds": ["string"],
      "title": "string",
      "project": "string"
    }
  ]
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `pid` | Long | Yes | IDE process ID |
| `started` | Long | Yes | IDE process start time (epoch ms) |
| `tabs` | Array | Yes | Open session tabs |
| `tabs[].sessionIds` | String[] | Yes | Every id the tab may stand for (an Air tab exposes two candidates) |
| `tabs[].title` | String | Yes | Tab title |
| `tabs[].project` | String | Yes | IDE project of the tab |

**Lifecycle:**

- Rewritten on tab open/close, project startup and tab re-targeting
- Deleted when the IDE disposes the plugin (withdraw)
- A file whose `pid` and `started` no longer match a live process is ignored and deleted by the reader
- Read with Gson; unknown keys ignored

---

### 4.2 Focus Request Format

**Location:** `~/.my-air/requests/<pid>/<uuid>.json`, `<pid>` being the target IDE

**Schema:**

```json
{
  "sessionId": "string"
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | String | Yes | Target session ID |

**Writers:**

- Another IDE (`TabStore.requestFocus`, atomic write)
- The taskbar widget (writes `<uuid>.tmp`, then renames it to `<uuid>.json`)

**Lifecycle:**

- Created when focus requested
- The target IDE drains `*.json` at start and on every create event in its directory
- Each file is deleted when read, then the session is opened
- No expiry

---

### 4.3 Pins Format

**Location:** `~/.my-air/pins.json`

**Schema:**

```json
{
  "sessions": ["session-id-1", "session-id-2"],
  "dismissed": {"session-id-5": 1794070960710},
  "settled": ["session-id-3", "session-id-4"]
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessions` | String[] | Yes | Session IDs pinned by hand |
| `dismissed` | Object | No | Running sessions removed from the pinned list: session ID to epoch ms of removal; written only when not empty |
| `settled` | String[] | No | Settled session IDs; written only when not empty |

Any other key throws; a non-string session ID throws.

**Semantics:**

- **Pinned list:** sessions pinned by hand plus every session with a live process, minus dismissed ones; a dismissed session returns once it has a newer message and no longer works
- **Settled:** hidden from the list and the tree until Show All Sessions is on; settling unpins, pinning unsettles
- Shared across all IDEs
- Atomic writes

---

### 4.4 Structure Format

**Location:** `~/.my-air/structure.json`

**Schema (version 4):**

```json
{
  "version": 4,
  "nextId": 7,
  "folders": [
    {
      "id": "3f1c...",
      "name": "rtm",
      "folders": [
        {
          "id": "9a2b...",
          "name": "my-air",
          "folders": [],
          "items": [{"type": "session", "id": "session-id-1"}],
          "dirs": ["E:\\dev\\my-air"],
          "plan": {
            "description": "string",
            "milestones": []
          },
          "workdir": "E:\\dev\\rtm"
        }
      ],
      "items": [],
      "dirs": []
    }
  ],
  "unfiled": ["session-id-3"]
}
```

**Root Object:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | Int | Yes | 1..4; another value throws |
| `nextId` | Int | Yes (version ≥ 3) | Number of the next milestone or task id (`M<n>`, `T<n>`), one counter for both |
| `folders` | Array | Yes | Top-level folders in the user's order |
| `unfiled` | String[] | Yes (version 4) | Sessions the user dropped into `sessions` by hand |

**Folder Object:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Folder identifier (UUID) |
| `name` | String | Yes | Folder name |
| `folders` | Array | Yes | Child folders, same shape |
| `items` | Array | Yes | Filed items `{"type": "session", "id": "<session id>"}` |
| `dirs` | String[] | Yes (version ≥ 3) | Bound project directories (version 2: a single optional `project`) |
| `plan` | Object | No | Plan data, present only on a plan folder |
| `workdir` | String | No | Working directory for sessions started from the folder or below; written only when set |

**Plan Object:**

```json
{
  "description": "string",
  "milestones": [
    {
      "id": "M1",
      "title": "string",
      "done": false,
      "description": "string",
      "tasks": [
        {
          "id": "T2",
          "title": "string",
          "status": "in_progress",
          "next": "string",
          "description": "string"
        }
      ]
    }
  ]
}
```

| Object | Field | Type | Required |
|--------|-------|------|----------|
| Plan | `milestones` | Array | Yes |
| Plan | `description` | String | No (written only when set) |
| Milestone | `id`, `title` | String | Yes |
| Milestone | `done` | Boolean | Yes |
| Milestone | `tasks` | Array | Yes |
| Milestone | `description` | String | No |
| Task | `id`, `title` | String | Yes |
| Task | `status` | String | Yes: `pending`, `in_progress`, `waiting`, `blocked`, `done`, `canceled`; another value throws |
| Task | `next`, `description` | String | No |

Which sessions work on a task is not stored here; it comes from the sessions' briefs.

**Validation Rules:**

- Folder IDs unique
- Parent-child relationships valid (no cycles)
- Plan folder cannot contain another plan folder
- Task IDs (M<n>, T<n>) unique across all plans
- Unknown keys at any level throw; missing required keys throw

---

### 4.5 History Snapshot Format

**Location:** `~/.my-air/history/structure-<yyyyMMdd-HHmmss-SSS>-<source>.json`, `<source>` one of `ui`, `auto`, `mcp`, `brief`, `restore`; local time

**Schema:** a verbatim copy of `structure.json` as it was before a change (§4.4). No metadata is stored in the file: the snapshot name, time and source come from the file name; the folder and session counts are computed when listed.

**Writing:**

- Taken before each change that alters `structure.json`, when the file existed
- Written with create-new semantics; a name taken twice in one millisecond fails rather than overwriting
- Any other `structure-*.json` name in the directory throws when listed

**Retention:**

- Only the newest 20 snapshots are kept; older ones are deleted after each snapshot
- Restore creates new snapshot of current state

---

### 4.6 Presets Format

**Location:** `~/.my-air/presets.json`

**Schema:**

```json
{
  "presets": [
    {
      "name": "string",
      "model": "opus",
      "effort": "medium",
      "project": "E:\\dev\\projects\\my-air",
      "worktree": true,
      "base": "main",
      "addDirs": ["E:\\dev\\shared"],
      "pinned": false
    }
  ]
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Preset name, non-blank, unique |
| `model` | String | No | `--model` |
| `effort` | String | No | `--effort`: one of `low`, `medium`, `high`, `xhigh`, `max` |
| `project` | String | No | Project directory (none: the IDE project's) |
| `worktree` | Boolean | No | Start in a new worktree |
| `base` | String | No | Fixed base branch; only with `worktree` (worktree without base: picked in a popup) |
| `addDirs` | String[] | No | `--add-dir` paths |
| `pinned` | Boolean | No | Has its own toolbar button |

The root must hold exactly the key `presets`; any other key in the root or a preset throws. Values must not be blank.

**Semantics:**

- MCP arguments override preset values
- Presets are templates, not mandatory configurations
- Shared across all IDEs
- Model and effort apply only to new sessions; `--resume` gets none of a preset's flags

---

### 4.7 Seen Format

**Location:** `~/.my-air/seen/<sessionId>`

**Format:** Plain text, single line with epoch milliseconds.

```
1794070960710
```

**Semantics:**

- Records when user last viewed session
- Used for unread badge
- One file per session
- Atomic writes
- Written by the IDE and by the taskbar widget (temp name with a dot, which the reader skips)

---

### 4.8 Board Format

**Location:** `~/.my-air/taskbar/board.json`

**Schema:**

```json
{
  "sessions": [
    {
      "id": "string",
      "title": "string",
      "project": "my-air",
      "status": "NEEDS_INPUT",
      "waitingFor": "dialog open",
      "lastMessage": 1794070960710,
      "unread": false,
      "activity": "string"
    }
  ],
  "recent": [
    {
      "id": "string",
      "title": "string",
      "project": "my-air",
      "lastMessage": 1794070960710
    }
  ]
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessions` | Array | Yes | Every session with a running process: needing input first, then idle, then working |
| `sessions[].id` | String | Yes | Session identifier |
| `sessions[].title` | String | Yes | Session title (`New session` without a transcript) |
| `sessions[].status` | String | Yes | Session status |
| `sessions[].unread` | Boolean | Yes | Idle with a message newer than its seen time |
| `sessions[].project` | String | No | File name of the project directory; missing without a transcript |
| `sessions[].waitingFor` | String | No | Waiting description |
| `sessions[].lastMessage` | Long | No | Last message time (epoch ms); missing without a transcript |
| `sessions[].activity` | String | No | Task in progress of the brief, else its goal |
| `recent` | Array | Yes | Up to 5 newest sessions no other group lists (idle and read, or without a process), settled ones left out |
| `recent[].id`, `.title`, `.project` | String | Yes | As above |
| `recent[].lastMessage` | Long | Yes | Epoch ms |

Null fields are left out (Gson default). Each IDE writes the file when a refresh changes it.

**Validation Rules:**

- Taskbar widget uses strict JSON parsing
- `UnmappedMemberHandling = Disallow` (C#)
- Unknown fields cause parse error
- A format change requires updating the widget in step: an added field breaks an older widget

**Status Values:**

| Status | Description | Taskbar group and dot color |
|--------|-------------|-------|
| `WORKING` | Actively processing | Working, grey (#8A8A8A light / #9A9A9A dark) |
| `NEEDS_INPUT` | Waiting for user | Needs input, amber (#F5A623) |
| `IDLE` | Waiting for the next prompt | Unread, blue (#005FB8 light / #4CC2FF dark), when `unread`; otherwise not grouped |

---

### 4.9 Prompt Files (Markdown)

**Location:** `~/.my-air/prompts/<IDE pid>-<uuid>.md`

Passed to a session in a plan with `--append-system-prompt-file`: the plan's name, path and description, its task's and milestone's. Written atomically when the tab starts its process; it lives until the process ends, since `claude` reads it again after a compaction. Files whose IDE pid is no longer alive are deleted when the next one is written.

### 4.10 Brief Reminder Hook (JSON)

**Location:** `~/.my-air/hooks/brief-reminder.json`, passed with `--settings` when briefs are on.

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {"hooks": [{"type": "command", "command": "powershell -NoProfile -NonInteractive -EncodedCommand <base64 UTF-16LE script>"}]}
    ]
  }
}
```

The script adds a reminder to call `my_air_set_brief` to every prompt until the transcript holds a `my_air_set_brief` `tool_use`. Written atomically.

---

## 5. Image Format

**Location:** `~/.my-air/images/<uuid>.png`

**Format:** PNG (compressed)

**Naming Convention:**

```
<random UUID>.png
```

Example:

```
3fb8a1c2-6d4e-4b0f-9a71-2c5d8e9f0a1b.png
```

**Semantics:**

- Pasted images from prompts
- Referenced as `@<path>` in prompt text, quoted when the path has a space
- Shared across IDEs
- Images last modified more than 30 days ago are deleted whenever a prompt panel opens

---

## 6. Scan Cache Format

**Location:** `~/.my-air/scan-cache-<hash>.json`, `<hash>` the first 8 hex digits of the SHA-256 of the plugin jar's absolute path

**Schema:**

```json
{
  "build": "<sha256 hex>",
  "transcripts": {
    "<transcript path>": {
      "offset": 12345,
      "conversation": {},
      "messages": {},
      "environment": {},
      "artifacts": {},
      "calls": {},
      "background": {},
      "brief": null
    }
  }
}
```

**Field Descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `build` | String | Yes | SHA-256 of the names and bytes of the plugin's `dev/myair/claude/` classes |
| `transcripts` | Object | Yes | Per transcript path, what the scanner read up to `offset` |
| `transcripts[path].offset` | Long | Yes | Byte after the last complete line read |
| `transcripts[path].*` | Object | Yes | Scanner state per concern (Gson serialization of `ClaudeSessionScanner.Transcript`; instants and paths as strings) |

**Semantics:**

- One file per plugin jar, so the installed plugin and a sandbox keep their own
- Tracks read positions in transcripts
- Enables incremental scanning
- Discarded (scan starts over) when `build` differs, i.e. when the transcript-reading classes changed
- Written by the first scan, then at most once a minute and on exit; atomic write

---

## 7. Format Validation

### 7.1 Strict Validation Principles

| Principle | Implementation |
|-----------|----------------|
| **Fail loudly** | Unknown formats throw exceptions |
| **No silent skipping** | Invalid own files throw; `structure.json` errors are shown and block folder changes. Exceptions: registry files of dead processes or still-starting `claude` are skipped; history listing reports an unparseable snapshot in an `error` field |
| **Type safety** | Strict type checking on parse |
| **Version checking** | `structure.json` version must be 1..4; the scan cache is discarded on a build mismatch |

### 7.2 Kotlin JSON Parsing

The plugin uses Gson (shipped with the IDE): `JsonParser` with hand-written field checks for its own files and `claude`'s, `Gson.fromJson` for `ides/*.json` and focus requests.

| Files | Unknown keys | Missing required keys |
|-------|--------------|----------------------|
| `structure.json`, `pins.json`, `presets.json` | Throw | Throw |
| Transcripts, registry, Desktop session files | Ignored | Throw for the fields marked required |
| `ides/*.json`, focus requests | Ignored | Not checked (Gson) |

### 7.3 C# JSON Parsing (Taskbar Widget)

```csharp
var options = new JsonSerializerOptions
{
    PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    RespectNullableAnnotations = true,
    RespectRequiredConstructorParameters = true,
    UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
    Converters = { new JsonStringEnumConverter<SessionStatus>(JsonNamingPolicy.SnakeCaseUpper, allowIntegerValues: false) },
};
```

Used for `board.json` and `ides/*.json`.

### 7.4 Validation Errors

| Error Type | Handling |
|------------|----------|
| Invalid JSON | Throw (parser exception); for `structure.json` the error is shown and folder changes are refused; for `presets.json` it is named in the presets dropdown and in `my_air_session_start` errors |
| Unknown status | Throw exception |
| Missing required field | Throw exception |
| Type mismatch | Throw exception |
| Version mismatch | `structure.json`: throw `unsupported structure version <n>`; scan cache: start over |

---

## 8. Format Compatibility

### 8.1 Forward Compatibility

- `claude`'s files: unknown keys ignored, so new `claude` fields do not break reading; changed formats of the fields read fail loudly
- My Air's own files (`structure.json`, `pins.json`, `presets.json`): unknown keys throw, so a newer file cannot be read by an older build
- New optional fields are written only when set, so files without them stay readable by older builds

### 8.2 Backward Compatibility

- New versions read old files: `structure.json` versions 1 to 3 read as the same structure without what came later
- Old versions cannot read a version-4 `structure.json`, nor any file carrying a field they do not know
- Format changes documented

### 8.3 Breaking Changes

- `structure.json` carries a version, raised when the root or folder shape changes; optional fields written only when set (`workdir`, descriptions, `next`) were added within version 4
- Migration path required
- Deprecated fields kept for one version

---

## 9. Atomic Write Pattern

### 9.1 Write Algorithm

```
1. Write the text to a temp file <name>.<uuid>.tmp next to the target
2. Rename it over the target with Windows POSIX-semantics rename (replaces a file others hold open)
3. On ERROR_ACCESS_DENIED or ERROR_SHARING_VIOLATION retry, up to 5 attempts 50 ms apart; any other error throws
4. Delete the temp file in any case (finally)
```

Used for `ides/*.json`, focus requests, `pins.json`, `structure.json`, `presets.json`, `seen/*`, `board.json`, the scan cache, prompt files and the brief reminder hook. History snapshots are written directly with create-new semantics.

### 9.2 Implementation

```kotlin
fun writeAtomically(target: Path, text: String) {
    val temp = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
    temp.writeText(text)
    try {
        for (attempt in 1..RENAME_ATTEMPTS) {
            val error = rename(temp, target) ?: return   // SetFileInformationByHandle, FILE_RENAME_INFO_EX,
                                                         // REPLACE_IF_EXISTS | POSIX_SEMANTICS
            check(error in TRANSIENT_ERRORS && attempt < RENAME_ATTEMPTS) { "cannot move $temp to $target: error $error" }
            Thread.sleep(50)
        }
    } finally {
        temp.deleteIfExists()
    }
}
```

### 9.3 Benefits

- No corrupted state from crashes
- No partial reads from concurrent access
- Atomic on same filesystem

---

## 10. File Watching

### 10.1 Watched Paths

One `ClaudeHomeWatcher` (Java `WatchService`, create/modify/delete, not recursive) watches all paths below, with one debounce: a refresh 500 ms after the last event, but no later than 700 ms after the first.

| Path | Purpose |
|------|---------|
| `~/.claude/projects/` and every `~/.claude/projects/<dir>/` (a new directory gets its own watch) | Detect transcript changes |
| `~/.claude/sessions/` | Detect registry changes |
| `~/.my-air/` | Detect state changes (structure, pins, presets, scan cache) |
| `~/.my-air/seen/` | Detect seen marks |

`board.json` lies in `~/.my-air/taskbar/`, which is not watched. Focus requests are watched separately by each IDE in its own `~/.my-air/requests/<pid>/`.

### 10.2 Event Handling

```
File change detected (or 60 s timer)
        │
        ▼
Debounce timer
        │
        ▼
Schedule scan on background thread
        │
        ▼
Read appended lines of transcripts, registry and own files
        │
        ▼
Update service state
        │
        ▼
Notify UI listeners (on EDT)
```

---

## 11. Traceability

| Format | FR Coverage | Architecture Doc |
|--------|-------------|------------------|
| Claude transcripts | FR-SD-001 | arch/02-data-architecture.md §2.1 |
| Claude registry | FR-SD-001 | arch/02-data-architecture.md §2.1 |
| IDE state | FR-CI-* | arch/02-data-architecture.md §2.2 |
| Focus requests | FR-CI-* | arch/02-data-architecture.md §2.2 |
| Pins | FR-SM-* | arch/02-data-architecture.md §2.2 |
| Structure | FR-SO-* | arch/02-data-architecture.md §2.2 |
| History | FR-SO-* | arch/02-data-architecture.md §2.2 |
| Presets | FR-SC-* | arch/02-data-architecture.md §2.2 |
| Seen | FR-SM-* | arch/02-data-architecture.md §2.2 |
| Board | FR-TW-* | arch/02-data-architecture.md §2.2 |
| Images | FR-SC-* | arch/02-data-architecture.md §2.2 |
| Scan cache | FR-SD-* | arch/02-data-architecture.md §2.2 |

---

## 12. Testing

### 12.1 Test Strategy

| Format | Test Type | Coverage |
|--------|-----------|----------|
| Claude transcripts | Unit tests | Parsing, validation, edge cases |
| Claude registry | Unit tests | Status mapping, PID validation |
| IDE state | Unit tests | Read/write, lifecycle |
| Focus requests | Unit tests | Creation, consumption |
| Pins | Unit tests | Add/remove, persistence |
| Structure | Unit tests | CRUD, validation, hierarchy |
| History | Unit tests | Snapshot, restore, retention |
| Presets | Unit tests | Read/write, override |
| Board | Unit tests | Generation, validation |

### 12.2 Test Data

- Sample transcript files with various message types
- Registry files with all status values
- Structure files with complex hierarchies
- Board files with all session statuses
- Edge cases: empty files, invalid JSON, missing fields
