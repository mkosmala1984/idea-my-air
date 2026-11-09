# Data Architecture – My Air System

**Related High-Level View Section:** 6.2  
**Related Functional Requirements:** FR-SD-*, FR-SM-*, FR-SO-*

---

## 1. Overview

The My Air system uses a file-based data architecture with two groups of data sources:
1. **Claude files** – Read-only: Claude Code's `~/.claude/` and Claude Desktop's `%LOCALAPPDATA%/Claude-Data/`
2. **My Air state files** (`~/.my-air/`) – Read/write, shared plugin state

Session state is read from files only; the plugin has no IPC channel to `claude`.

---

## 2. Data Sources

### 2.1 Claude Files (Read-Only)

| Path | Format | Description |
|------|--------|-------------|
| `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl` | JSONL | Session transcripts |
| `~/.claude/sessions/<pid>.json` | JSON | Live session registry |
| `%LOCALAPPDATA%/Claude-Data/*/claude-code-sessions/*/*/local_*.json` | JSON | Claude Desktop sessions: `originCwd` by `cliSessionId` |
| `~/.claude/skills`, `<cwd>/.claude/skills`, `commands/`, `plugins/installed_plugins.json`, `plugins/known_marketplaces.json`, `~/.agents/.skill-lock.json` | Files / JSON | Skill files and origin, read for Session Context |

### 2.2 My Air State Files (Read/Write)

| Path | Format | Description | Owner |
|------|--------|-------------|-------|
| `ides/<pid>.json` | JSON | IDE tab state | Each IDE instance |
| `requests/<pid>/` | JSON | Focus requests | Requesting IDE/widget |
| `pins.json` | JSON | Pinned/dismissed/settled sessions | All IDEs (shared) |
| `structure.json` | JSON | Folder structure (version 4) | All IDEs (shared) |
| `history/` | JSON | Structure snapshots | All IDEs (shared) |
| `presets.json` | JSON | Session configurations | All IDEs (shared) |
| `seen/<id>` | Text | Last seen timestamps (epoch ms) | All IDEs and the widget (shared) |
| `taskbar/board.json` | JSON | Taskbar board data | All IDEs (shared) |
| `images/` | Binary | Pasted prompt images | All IDEs (shared) |
| `scan-cache-<hash>.json` | JSON | Scan offsets | One per plugin jar path (installed plugin and sandbox apart) |
| `hooks/brief-reminder.json` | JSON | `--settings` file with the brief reminder `UserPromptSubmit` hook | All IDEs (shared) |
| `prompts/<IDE pid>-<uuid>.md` | Markdown | Plan prompt passed with `--append-system-prompt-file` | Each IDE instance |

---

## 3. Data Models

### 3.1 ClaudeSession

**Source:** Transcript (plus Desktop session files and worktree lists for `project`)

```kotlin
data class ClaudeSession(
    val id: String,
    val cwd: Path,                // Where it started
    val title: String,
    val lastMessage: Instant,     // Last user/assistant message, not file mtime
    val changedFiles: List<Path>,
    val prompts: List<String>,
    val brief: Brief? = null,
    val project: Path = cwd,      // Main checkout for a linked worktree, else Desktop originCwd, else cwd
    val desktop: Boolean = false, // Run by Claude Desktop
    val addedDirs: List<Path> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val artifacts: List<Artifact> = emptyList(),
    val background: List<BackgroundTask> = emptyList(),
    val skills: List<String> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val workDir: Path = cwd,      // Where it works now (follows cd and EnterWorktree)
    val startBranch: String? = null,
    val continuedIn: String? = null // Session /bg continued this one in
)
```

Status and unread are not part of `ClaudeSession`: status is `LiveSession.status`, unread is `ClaudeSessionsService.unread`.

A running session without a transcript yet is listed as a `ClaudeSession` titled "New session" in the registry entry's `cwd`, dated by its `startedAt` (`unscannedSessions`).

### 3.2 LiveSession

**Source:** Registry file `<pid>.json`; keyed by session id in `Map<String, LiveSession>`

```kotlin
enum class SessionStatus { WORKING, NEEDS_INPUT, IDLE } // busy/shell -> WORKING, waiting -> NEEDS_INPUT, idle -> IDLE

data class LiveSession(
    val pid: Long,                // The claude process (a job's worker for kind "bg")
    val entrypoint: String,       // e.g. "cli", "claude-desktop"
    val status: SessionStatus,
    val waitingFor: String?,
    val since: Instant,           // statusUpdatedAt
    val cwd: Path,
    val started: Instant,         // startedAt
    val job: String? = null,      // jobId of kind "bg"
    val parkedBy: Set<Long> = emptySet() // pids of processes /bg parked into this job (parkedJobId)
)
```

Reading rules: `kind` `interactive` or `bg` (`bg` needs `jobId`); `daemon`, `daemon-worker` and `spare` files are skipped; any other kind throws. A file without `statusUpdatedAt` is skipped. `procStart` is checked against the pid's start time, not stored.

### 3.3 BoardSession

**Source:** board.json

```kotlin
data class BoardSession(
    val id: String,
    val title: String,
    val project: String?,          // File name of ClaudeSession.project
    val status: SessionStatus,     // WORKING, NEEDS_INPUT, IDLE
    val waitingFor: String?,
    val lastMessage: Long?,        // Epoch ms
    val unread: Boolean,
    val activity: String?          // Task in progress of the brief, else its goal
)

data class RecentSession(val id: String, val title: String, val project: String, val lastMessage: Long)
```

`board.json` is `{"sessions": [BoardSession], "recent": [RecentSession]}`; `recent` holds up to 5 sessions no other flyout group lists, settled ones left out.

### 3.4 Folder Structure

**Source:** structure.json (`{"version": 4, "nextId", "folders", "unfiled"}`)

```kotlin
data class SessionFolders(
    val folders: List<Folder> = emptyList(),
    val nextId: Int = 1,                    // Next milestone/task number
    val unfiled: List<String> = emptyList() // Sessions dropped onto "sessions" by hand
)

data class Folder(
    val id: String,
    val name: String,
    val folders: List<Folder> = emptyList(), // Subfolders
    val items: List<Item> = emptyList(),     // Filed sessions (type "session")
    val dirs: List<String> = emptyList(),    // Bound directories
    val plan: Plan? = null,                  // Non-null: a plan folder
    val workdir: String? = null              // Where sessions started from it run
)

data class Plan(
    val description: String? = null,         // Markdown
    val milestones: List<Milestone> = emptyList()
)

data class Milestone(val id: String, val title: String, val done: Boolean = false,
                     val tasks: List<PlanTask> = emptyList(), val description: String? = null)

data class PlanTask(
    val id: String,                          // T<n>, unique across the file
    val title: String,
    val status: PlanStatus = PlanStatus.PENDING, // pending, in_progress, waiting, blocked, done, canceled
    val next: String? = null,
    val description: String? = null          // Markdown
)
```

A task holds no session ids: a session shows under the task its brief names (`Brief.planTask`).

---

## 4. Data Flow

### 4.1 Session Discovery Flow

```
Claude Code / Claude Desktop
        │
        ▼
┌─────────────────────────┐   ┌──────────────────────────┐
│ ~/.claude/projects/     │   │ ~/.my-air/ (structure,   │
│ ~/.claude/sessions/     │   │ pins), ~/.my-air/seen/   │
└─────────────────────────┘   └──────────────────────────┘
        │                              │
        └──── ClaudeHomeWatcher (500 ms quiet / 700 ms cap) ──┐
                                                              ▼
┌──────────────────────────────────────────────┐
│ ClaudeSessionScanner, DesktopSessionReader   │  refresh pool thread
│ LiveSessionRegistry, inProjects, Git heads   │
└──────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│ ClaudeSessionsService   │ ← EDT: structure, pins, BoardWriter
│ (holds session list)    │
└─────────────────────────┘
        │
        ├──────────────────────────────────┐
        ▼                                  ▼
┌──────────────┐                  ┌───────────────┐
│ IDE UI       │                  │ BoardWriter   │
│ (tree/list)  │                  │ board.json    │
└──────────────┘                  └───────────────┘
                                           │
                                           ▼
                                  ┌──────────────────┐
                                  │ Taskbar Widget   │
                                  └──────────────────┘
```

### 4.2 State Synchronization Flow

```
IDE Instance A               IDE Instance B            Taskbar Widget
┌──────────────────┐        ┌─────────────┐          ┌──────────────┐
│ Tab open/close   │        │             │          │              │
│ Write            │        │ Write       │          │ Read         │
│ ides/A.json      │        │ ides/B.json │          │ ides/*.json  │
│                  │        │             │          │              │
│ On open/focus:   │◄───────┤             │          │              │
│ read ides/B.json │        │             │          │              │
│                  │        │             │          │              │
│ Write board.json │        │ Write       │          │ Read         │
│                  │        │ board.json  │          │ board.json   │
└──────────────────┘        └─────────────┘          └──────────────┘
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              ▼
                    ~/.my-air/ (shared state)
```

Every IDE writes the same board content, derived from the same files, and skips the write when it is unchanged. IDEs never read `board.json`.

### 4.3 Focus Request Flow

```
User opens a session in IDE A (ClaudeSessionOpener.open)
        │
        ▼
┌─────────────────────────┐
│ Check local tabs        │ (incl. detached processes and Air's tabs)
└─────────────────────────┘
        │
        ├─ Found locally → Focus tab
        │
        ├─ Not found → Check other IDEs
        │        │
        │        ▼
        │  ┌─────────────────────────┐
        │  │ Read ides/*.json        │
        │  │ Find IDE with tab       │
        │  └─────────────────────────┘
        │        │
        │        ├─ Found → AllowSetForegroundWindow(target pid)
        │        │           Write requests/<targetPid>/<uuid>.json
        │        │           (target raises its own window)
        │        │
        │        ├─ Not found → Check registry
        │        │        │
        │        │        ├─ Running in other program (not a job)
        │        │        │   → Warning: Switch to Window / Open Anyway / Cancel
        │        │        │
        │        │        ├─ Run by a background job
        │        │        │   → New tab attaching to the job
        │        │        │
        │        │        └─ Not running
        │        │            → New tab (error dialog if cwd is gone)
        │        │
        └────────┴──────────────────────────────┘
```

---

## 5. Persistence Strategy

### 5.1 Atomic Writes

Writes to `~/.my-air/` use the atomic write pattern (`writeAtomically`):
1. Write to temporary file `<name>.<uuid>.tmp`
2. Rename it over the target with Kernel32 `SetFileInformationByHandle` (`FILE_RENAME_FLAG_REPLACE_IF_EXISTS | FILE_RENAME_FLAG_POSIX_SEMANTICS`), up to 5 attempts on access denied or sharing violation
3. Rename is atomic on same volume, and replaces a file a reader holds open with delete sharing

History snapshots are created with `CREATE_NEW`, and pasted images are written directly.

**Rationale:** Prevents corrupted state from crashes or concurrent writes.

### 5.2 Shared State

Files in `~/.my-air/` are shared across all IDE instances:
- `pins.json`, `structure.json`, `presets.json`, `seen/`, `history/`, `taskbar/board.json`
- Any IDE can read/write. `structure.json`, `pins.json` and `seen/` changes reach other IDEs because they watch `~/.my-air` and `~/.my-air/seen`. `presets.json` is re-read on every toolbar update, cached by modification time.

**Rationale:** Consistent state across all IDE windows.

### 5.3 Per-Instance State

Files specific to each IDE instance:
- `ides/<pid>.json` – Each IDE writes its own
- `requests/<pid>/` – Each IDE reads its own requests
- `prompts/<IDE pid>-<uuid>.md` – Plan prompt files of that IDE's processes

`scan-cache-<hash>.json` is per plugin jar path, so IDEs running the same installed plugin share it.

**Rationale:** Avoid conflicts between instances.

### 5.4 History and Undo

Structure changes leave snapshots:
- Each change copies the previous `structure.json` into `history/`
- Snapshots named `structure-<yyyyMMdd-HHmmss-SSS>-<source>.json`
- Only the newest 20 are kept
- Restore applies snapshot atomically
- History preserved after restore

**Rationale:** Safety net for destructive operations.

---

## 6. Data Validation

### 6.1 Strict Format Validation

- Unknown registry statuses and kinds throw exceptions
- Missing required fields throw exceptions (explicit `checkNotNull` per field; Gson itself ignores unknown fields)
- `SessionFolders` rejects broken invariants and unknown item types
- No silent skipping or misinterpretation

### 6.2 PID Validation

- Registry entry valid only if PID's start time equals `procStart`
- Prevents stale entries from killed processes
- Windows FILETIME comparison

### 6.3 Board Format

- Taskbar widget uses strict JSON parsing
- `UnmappedMemberHandling = Disallow`
- Format changes fail loudly

---

## 7. Data Lifecycle

### 7.1 Session Lifecycle

```
Created → Running → (Working/Waiting/Idle) → Completed
   │          │                              │
   │          │                              │
   ▼          ▼                              ▼
Registry   Transcripts                   Settled
created    written                       (hidden)
```

### 7.2 State File Lifecycle

| File | Created | Updated | Removed |
|------|---------|---------|---------|
| `ides/<pid>.json` | IDE startup | Tab changes | IDE dispose, or by another IDE once the process is gone |
| `pins.json` | First pin | Pin/unpin/settle | Never |
| `structure.json` | First folder | Structure changes | Never |
| `presets.json` | First preset | Preset changes | Never |
| `seen/<id>` | First seen | Tab selection, IDE activation, widget click | Never |
| `board.json` | First refresh | Session changes | Never |
| `history/*` | Structure change | Structure changes | Beyond the newest 20 |
| `prompts/*.md` | Process start in a plan | Never | Process end; files of IDEs gone when the next is written |

### 7.3 Image Pruning

- Images in `~/.my-air/images/` older than 30 days pruned
- Prevents unbounded growth

---

## 8. Traceability

| Data Element | FR Coverage |
|--------------|-------------|
| ClaudeSession | FR-SD-* |
| LiveSession | FR-SD-002, FR-SD-003 |
| BoardSession | FR-AN-008, FR-TW-* |
| Folder Structure | FR-SO-* |
| Pins/Settled | FR-SM-004, FR-SM-005 |
| Presets | FR-SC-004 |
| Seen State | FR-AN-002, FR-AN-003 |
| IDE Tab State | FR-CI-001, FR-CI-004 |
| Focus Requests | FR-CI-003, FR-CI-005 |
| History | FR-SO-007 |
