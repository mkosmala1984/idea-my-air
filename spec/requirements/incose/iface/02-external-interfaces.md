# External Interfaces – My Air System

**Related High-Level View Section:** 4.2, 4.3  
**Related Functional Requirements:** FR-SD-*, FR-CI-*, FR-TW-*

---

## 1. Overview

The My Air system integrates with multiple external systems through well-defined interfaces. This document describes all external interfaces, their protocols, data formats, and interaction patterns. Full file schemas are in `05-file-format-interface.md`.

---

## 2. External Interface Matrix

| Interface | System | Type | Direction | Protocol | Status |
|-----------|--------|------|-----------|----------|--------|
| **EI-1** | Claude Code files | File-based | Read-only | File System (JSON/JSONL) | Required |
| **EI-2** | Git | CLI | Bidirectional | Command Line | Required for worktree, branch and change features |
| **EI-3** | Windows API | Native | Read/Write | Win32 (via JNA) | Required |
| **EI-4** | Taskbar Widget | File-based | Bidirectional | File System (JSON) | Optional |
| **EI-5** | Branch Diff Plugin | Plugin API (extension point) | Bidirectional | IntelliJ Plugin API | Optional |
| **EI-6** | Claude Code CLI | Process | Write (starts processes) | Command line, terminal | Required |
| **EI-7** | Claude Desktop | File-based | Read-only | File System (JSON) | Optional |
| **EI-8** | Skill and plugin metadata | File-based | Read-only | File System | Optional |

---

## 3. EI-1: Claude Code Interface

### 3.1 Interface Description

The My Air plugin reads session state from files written by Claude Code CLI. It never modifies files under `~/.claude/`. State is read from files only; the plugin does start and drive `claude` processes (EI-6).

### 3.2 Data Sources

| Path | Format | Purpose | Access |
|------|--------|---------|--------|
| `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl` | JSONL | Session transcripts (`ClaudeSessionScanner`) | Read |
| `~/.claude/sessions/<pid>.json` | JSON | Live session registry (`LiveSessionRegistry`) | Read |

### 3.3 Transcript Format (JSONL)

Append-only, one JSON object per line. Summary of what is read (full schema: `05-file-format-interface.md` §3.1):

- `type`: `user`, `assistant`, `system` (with `subtype` `local_command`), `custom-title`, `ai-title`, `attachment`, `queue-operation`, `continued-in` and others.
- `timestamp`: top-level ISO-8601 string, parsed with `Instant.parse`; a message line without it throws.
- `message.model`: model of the last `assistant` line outside sidechains (`<synthetic>` skipped); top-level `effort`.
- `cwd`: taken from the first line carrying a `cwd` field, with `gitBranch` as the start branch (`HEAD` read as none). The directory name encodes the cwd lossily and is not decoded.
- Session id: the file name.

**Derived fields:**
- `lastMessage` – `timestamp` of the last `user`/`assistant` line or `system` `local_command` line, outside sidechains and `isMeta`
- `title` – last `custom-title`, else brief title, else last `ai-title`, else first human prompt
- `workDir`, `additionalWorkingDirectories` – last `attachment` of type `environment`
- Status is not derived from the transcript; it comes from the registry (§3.4)

A rescan reads only complete lines after the stored offset, kept in `~/.my-air/scan-cache-<hash>.json`.

### 3.4 Registry Format (JSON)

One file per running `claude` (full schema: `05-file-format-interface.md` §3.2). Fields read:

| Field | Required | Meaning |
|-------|----------|---------|
| `pid` | yes | Process id |
| `sessionId` | yes | Session the process runs |
| `status` | yes | `busy` / `shell` (working), `waiting` (needs input), `idle`; any other value throws |
| `waitingFor` | no | What it waits for |
| `entrypoint` | yes | What started it, e.g. `cli`, `claude-desktop` |
| `procStart` | yes | Process creation time as a Windows FILETIME (100 ns ticks since 1601), stored as a string |
| `cwd` | yes | Working directory |
| `startedAt` | yes | Epoch ms |
| `statusUpdatedAt` | no | Epoch ms; a file without it belongs to a `claude` still starting and is skipped |
| `kind` | yes | `interactive` or `bg`; `daemon` and `daemon-worker` are skipped; any other value throws |
| `jobId` | for `bg` | Background job id |
| `parkedJobId` | no | `/bg` parked this process into that job |
| `spare` | no | `true`: a worker started ahead, skipped |

An entry counts only while the pid's start time equals `procStart`.

### 3.5 Interface Behavior

| Aspect | Description |
|--------|-------------|
| **Polling** | File watching (500 ms quiet / 700 ms max debounce, `ClaudeHomeWatcher`) + periodic refresh (60 s) |
| **Validation** | Unknown formats and missing required fields throw exceptions (fail loudly) |
| **Error Handling** | Any exception aborts the whole refresh; it is logged as an IDE error and the previous state stays shown |
| **Concurrency** | Multiple IDEs can read simultaneously |

### 3.6 Constraints

- **Read-only:** Plugin never writes to `~/.claude/`
- **Format dependency:** Plugin depends on Claude Code file format stability
- **State from files:** Session state is read only from files, never asked of a process
- **Format changes:** A changed format fails loudly rather than being tolerated

---

## 4. EI-2: Git Interface

### 4.1 Interface Description

The plugin runs the `git` on PATH (`Git` object, `actions/Git.kt`) for worktree management, branches, base branches and changes against a base.

### 4.2 Commands Used

| Command | Purpose | Frequency |
|---------|---------|-----------|
| `git worktree add <path> <branch>` | Check out an existing local branch in a new worktree | On demand |
| `git worktree add --track -b <local> <path> <remote>` | Worktree on a new local branch tracking a remote pick | On demand |
| `git worktree add --no-track -b <branch> <path> <base>` | Worktree on a new branch off a base | On demand |
| `git worktree list --porcelain`, `prune`, `remove` | Find and remove a clean linked worktree holding the branch | On demand |
| `git branch --show-current` | Current branch | On demand |
| `git status --porcelain --untracked-files=all` | Check a worktree is clean | On demand |
| `git diff --no-renames --raw --numstat -z <merge-base>` | Changed files against the base | On demand |
| `git merge-base`, `git show` | Merge base, file contents for diffs | On demand |
| `git rev-parse` | Resolve refs, repository root, common dir | On demand |
| `git for-each-ref` | List branches, contained bases | On demand |
| `git config` | Read/write `branch.<branch>.myAirBase` | On demand |
| `git ls-files`, `git check-ignore` | Untracked files; `.worktreeinclude` copying | On demand |
| `git remote` | Default branch detection | On demand |

### 4.3 Worktree Creation Flow

```
User requests session in worktree
        │
        ▼
Plugin picks local branch (remote pick: local namesake, created tracking it)
        │
        ▼
Plugin computes path <worktreesDir>/<name>
(worktreesDir: setting, default .claude/worktrees relative to the main checkout)
        │
        ▼
git worktree add (existing branch, or -b new branch off the base)
        │
        ├─ Success → copy .worktreeinclude files, continue session creation
        └─ Failure → GitFailure with stderr reported to user (or to the MCP agent)
```

### 4.4 Interface Behavior

| Aspect | Description |
|--------|-------------|
| **Execution** | `ProcessBuilder`, blocking until exit (`waitFor`); callers run it off the EDT |
| **Timeout** | None |
| **Output** | Captured stdout/stderr |
| **Error Handling** | Exit code outside the expected set throws `GitFailure` carrying stderr |

### 4.5 Constraints

- **Git required:** Must be installed and on PATH
- **Repository required:** For worktree, branch and change features; a plain session starts in any directory
- **Branch validation:** Branch names must be valid
- **Worktree conflicts:** A linked worktree holding the branch is removed when clean, unlocked and unused; otherwise the pick becomes the base of a new branch `worktree-<name>`

---

## 5. EI-3: Windows API Interface

### 5.1 Interface Description

The plugin uses Win32 API via Java Native Access (JNA) for window operations and atomic file replacement. Process start times come from Java `ProcessHandle`.

### 5.2 API Functions Used

| API Function | Purpose | Module |
|--------------|---------|--------|
| `CreateFile`, `SetFileInformationByHandle` (`FILE_RENAME_INFO_EX`, replace + POSIX semantics), `CloseHandle`, `GetLastError` | Atomic replace of a file (`writeAtomically`) | kernel32 |
| `EnumWindows`, `IsWindowVisible`, `GetWindowText` | Find visible titled windows of a process | user32 |
| `GetWindowThreadProcessId` | Get process ID of window | user32 |
| `GetWindowLong`, `ShowWindow` | Restore a minimized window | user32 |
| `SetForegroundWindow` | Bring the window hosting a `claude` run outside the IDE to the foreground | user32 |
| `AllowSetForegroundWindow` | Allow focus transfer to another IDE | user32 |

### 5.3 Process Validation Flow

```
Registry entry has PID
        │
        ▼
ProcessHandle.of(pid).info().startInstant()
        │
        ▼
Convert procStart FILETIME to epoch ms
        │
        ▼
Compare with process start time
        │
        ├─ Match → Entry is valid
        └─ Mismatch / no process → Entry is stale, ignored
```

### 5.4 Focus Request Flow

```
User opens a session whose tab is in another IDE
        │
        ▼
AllowSetForegroundWindow(targetIdePid)
        │
        ▼
Write focus request to requests/<targetPid>/<uuid>.json
        │
        ▼
Target IDE (watching its inbox) focuses its project window
(ProjectUtil.focusProjectWindow) and opens the session tab
```

### 5.5 Interface Behavior

| Aspect | Description |
|--------|-------------|
| **Platform** | Windows only |
| **Access** | Via JNA (Java Native Access) |
| **Permissions** | Standard user privileges |
| **Error Handling** | A refused `AllowSetForegroundWindow`/`SetForegroundWindow` throws; a failed rename is retried up to 5 times on access-denied or sharing violation, then throws |

### 5.6 Constraints

- **Windows-only:** Not available on Linux/macOS
- **JNA dependency:** Requires JNA library
- **Process permissions:** May fail without proper permissions
- **Focus restrictions:** Windows may block foreground changes

---

## 6. EI-4: Taskbar Widget Interface

### 6.1 Interface Description

The taskbar widget (separate WPF application) communicates with the plugin via shared files in `~/.my-air/`.

### 6.2 Data Exchange

| Direction | Data | Path | Format |
|-----------|------|------|--------|
| **Plugin → Widget** | Board data | `~/.my-air/taskbar/board.json` | JSON |
| **Plugin → Widget** | IDE tabs (widget reads only) | `~/.my-air/ides/<pid>.json` | JSON |
| **Widget → Plugin** | Focus requests | `~/.my-air/requests/<pid>/<uuid>.json` | JSON |
| **Widget ↔ Plugin** | Last seen time per session | `~/.my-air/seen/<sessionId>` | Epoch ms as text |

### 6.3 Board Format

Written by every IDE when a refresh changes it (`boardJson`). Summary (full schema: `05-file-format-interface.md` §4.8):

```json
{
  "sessions": [
    { "id": "...", "title": "...", "project": "<folder name>", "status": "WORKING" | "NEEDS_INPUT" | "IDLE",
      "waitingFor": "...", "lastMessage": 1794070960710, "unread": true, "activity": "..." }
  ],
  "recent": [
    { "id": "...", "title": "...", "project": "<folder name>", "lastMessage": 1794070960710 }
  ]
}
```

`sessions` lists every session with a live process, needing input first, then idle, then working; a null field is left out. `recent` holds up to 5 newest sessions no other group lists. Counts are computed by the widget.

### 6.4 Focus Request Format

```json
{ "sessionId": "<session-id>" }
```

Written as a temp file and renamed to `<uuid>.json`; the IDE reads and deletes it (`05-file-format-interface.md` §4.2).

### 6.5 Interface Behavior

| Aspect | Description |
|--------|-------------|
| **Protocol** | File-based (no network) |
| **Frequency** | Widget watches the board and `ides/` directories with `FileSystemWatcher` and reloads every 5 s |
| **Validation** | Widget uses strict JSON parsing |
| **Error Handling** | An invalid board or unknown status throws |

### 6.6 Constraints

- **Optional component:** Widget may not be installed
- **File-based:** No direct communication
- **Format strict:** Widget validates board format strictly
- **Activation:** Widget activates itself and calls `AllowSetForegroundWindow` for the IDE before writing a focus request

---

## 7. EI-5: Branch Diff Plugin Interface

### 7.1 Interface Description

Optional integration with Branch Diff (`dev.kosmala.branch-diff-tree`): My Air registers `SessionDiffTargetProvider` on its `diffTargetProvider` extension point (`my-air-branch-diff.xml`, loaded by an optional `<depends>`).

### 7.2 Interface Points

| Interface | Purpose | Direction |
|-----------|---------|-----------|
| `target(project): DiffTarget?` | Work tree root of the active session's `workDir` (a new tab: its `cwd`), base `Git.baseOf(root)`, session title | Read |
| `subscribe(project, parent, onChange)` | Delegates to `ActiveSession.subscribe` | Read |
| `setBase(project, target, base)` | Stores the base with `Git.setBase` (`branch.<branch>.myAirBase`) and calls `ActiveSession.baseChanged` | Write |

### 7.3 Integration Behavior

| Aspect | Description |
|--------|-------------|
| **Dependency** | Optional (plugin works without it) |
| **Follows active session** | Updates when session changes |
| **Data source** | Work tree and base of the active session; Branch Diff computes the diff. A missing folder or a folder outside a repository throws, shown in Branch Diff |

### 7.4 Constraints

- **Optional dependency:** Branch Diff may not be installed
- **Base writes:** A base chosen in Branch Diff is written to the repository's git config
- **No impact:** My Air functions normally without Branch Diff

---

## 8. Interface Error Handling

### 8.1 General Principles

| Principle | Description |
|-----------|-------------|
| **Fail loudly** | Unknown formats throw exceptions |
| **Clear messages** | Error messages include details |
| **Graceful degradation** | Optional interfaces failure doesn't break plugin |
| **No silent errors** | All errors logged and reported |

### 8.2 Interface-Specific Handling

| Interface | Error Scenario | Handling |
|-----------|----------------|----------|
| Claude Code | Invalid JSON or missing field | Throw; refresh aborted, logged as IDE error |
| Claude Code | Unknown status | Throw exception |
| Git | Command failure | `GitFailure` with stderr reported to user |
| Windows API | Process not found | Ignore stale entry |
| Taskbar Widget | Invalid board | Throw |
| Branch Diff | Plugin not installed | Feature not loaded (optional dependency) |

---

## 9. Traceability

| Interface | FR Coverage | Architecture Doc |
|-----------|-------------|------------------|
| EI-1 (Claude Code) | FR-SD-* | arch/05-integration-architecture.md §3 |
| EI-2 (Git) | FR-SC-002, FR-SC-007 | arch/05-integration-architecture.md §7 |
| EI-3 (Windows API) | FR-CI-005, FR-CI-008 | arch/05-integration-architecture.md §8 |
| EI-4 (Taskbar Widget) | FR-TW-*, FR-AN-* | arch/05-integration-architecture.md §10 |
| EI-5 (Branch Diff) | FR-CD-005 | arch/05-integration-architecture.md §9 |

---

## 10. Interface Testing

### 10.1 Test Strategy

| Interface | Test Type | Coverage |
|-----------|-----------|----------|
| Claude Code | Unit tests | Format parsing, validation (`ClaudeSessionScannerTest`, `LiveSessionRegistryTest`) |
| Git | Integration tests | Worktree creation, branch ops (`GitTest`, real `git` in a temp dir) |
| Windows API | Unit tests | Process validation with an injected `isSameProcess` |
| Taskbar Widget | Unit tests | Board format (`TaskbarBoardTest`, `taskbar/tests/BoardTest.cs` parse the same JSON literal) |
| Branch Diff | Manual testing | Feature availability |

### 10.2 Test Data

- **Claude Code:** Sample transcripts and registry files
- **Git:** Test repositories with branches
- **Windows API:** Injected process check
- **Taskbar Widget:** Sample board.json files

---

## 11. EI-6: Claude Code CLI Invocation

### 11.1 Interface Description

The plugin starts `claude` in session tabs through a reworked terminal (`SessionTerminals`) and runs it for a few helper commands. It learns the process pid from the terminal session and sends typed text through the terminal view.

### 11.2 Session Commands

| Case | Command |
|------|---------|
| Resume | `claude --append-system-prompt <prompt> [--settings ~/.my-air/hooks/brief-reminder.json] [--append-system-prompt-file ~/.my-air/prompts/<ide-pid>-<uuid>.md] --resume <id>` |
| New session | Same flags, `--session-id <new uuid>` plus a configuration's `--model`, `--effort`, `--add-dir ...`, then `-- <prompt>` |
| Background job | `claude attach <jobId>` with nothing else |

- `--settings` is passed only with briefs on (a `UserPromptSubmit` hook reminding the agent to call `my_air_set_brief`).
- `--append-system-prompt-file` is passed only for a session in a plan.
- Environment: `CLAUDE_CODE_NO_FLICKER=1`, `CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL=true`.
- `--resume` gets none of a configuration's flags.

### 11.3 Helper Commands

| Command | Purpose |
|---------|---------|
| `claude stop <jobId>` | Close and Settle of a tab showing a job; 5 s timeout, a timeout or non-zero exit is logged as an error |
| `claude -p --model haiku --no-session-persistence --output-format text [--allowedTools <jira tools>] -- <prompt>` | Suggest a branch name (`suggestBranchName`) |
| `plannotator review --git --base <base> --diff-type since-base` | Review of changes in a Terminal tab, only with `plannotator.exe` on PATH |

---

## 12. EI-7: Claude Desktop Session Files

`DesktopSessionReader` reads `%LOCALAPPDATA%/Claude-Data/<profile>/claude-code-sessions/<account>/<org>/local_<id>.json`. Fields read: `cliSessionId` (key, the `claude` session id) and `originCwd` (the folder the session was started in). A session found there gets `originCwd` as its project and is marked "Desktop". A file is parsed again only when its mtime changes; no data directory means Claude Desktop is not installed.

---

## 13. EI-8: Skill and Plugin Metadata

Read off the EDT for the Skills card (`skillFiles`, `SkillFiles.kt`; `SkillOrigin.kt`), from local files only:

| Path | Purpose |
|------|---------|
| `~/.claude/skills`, `<cwd>/.claude/skills`, `commands/` of both | User and project skills and commands |
| `~/.claude/plugins/installed_plugins.json` (`installPath`, version, `gitCommitSha`) | Installed plugin skills and versions |
| `~/.claude/plugins/synced/*/<plugin>~*`, `~/.claude/skills/synced/*` | Plugins and skills synced from claude.ai |
| `known_marketplaces.json` (`lastUpdated`), marketplace clone `plugin.json` | Marketplace version for the update state |
| `~/.agents/.skill-lock.json` | Repository and folder of skills linked from `~/.agents/skills` |

A skill found nowhere is built into `claude`; a skill found twice throws.
