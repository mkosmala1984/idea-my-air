# Runtime Architecture – My Air System

**Related High-Level View Section:** 2.4  
**Related Functional Requirements:** FR-SD-*, FR-CI-*, FR-SM-*

---

## 1. Overview

The My Air system operates as an IntelliJ IDEA plugin with real-time monitoring and cross-IDE coordination. This document describes runtime behavior, threading model, and lifecycle management.

---

## 2. Runtime Environment

### 2.1 IntelliJ Plugin

| Aspect | Detail |
|--------|--------|
| **Host** | IntelliJ IDEA 2026.3 (build 263+, `sinceBuild = "263"`, no upper bound); compiled against the locally installed IU-263.7307.200 |
| **Language** | Kotlin |
| **Runtime** | Java 25 (JBR bundled with IDE) |
| **Build** | Gradle with IntelliJ Platform Gradle Plugin 2.19.0 |
| **API Level** | 263 |
| **Dependencies** | `com.intellij.modules.platform`, Terminal plugin and the content modules `intellij.terminal.frontend` and `intellij.platform.vcs.impl` (required); MCP Server and Branch Diff (optional) |
| **OS** | Windows only (Kernel32 and user32 through JNA, `LOCALAPPDATA`) |

### 2.2 Taskbar Widget

| Aspect | Detail |
|--------|--------|
| **Platform** | Windows 11 (`net10.0-windows10.0.26100.0`, win-x64) |
| **Language** | C# |
| **Runtime** | .NET 10 |
| **Framework** | WPF |
| **Build** | dotnet CLI |
| **Dependencies** | Deskband11Lib.Wpf 1.4.2 |

---

## 3. Threading Model

### 3.1 Plugin Threading

```
┌─────────────────────────────────────────────────────────────┐
│                    Plugin Threading                           │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Thread "Claude home watcher" (daemon, ClaudeHomeWatcher)   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Watches ~/.claude/sessions/, projects/ and each   │    │
│  │   projects/<dir>/, ~/.my-air/, ~/.my-air/seen/      │    │
│  │ - Debounced: 500ms quiet, 700ms cap                 │    │
│  │ - Calls ClaudeSessionsService.refresh()             │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  App scheduled executor                                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - refresh() every 60 s (fixed delay), always        │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  Pool "Claude sessions refresh" (bounded, 1 thread)          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Scans transcripts + Desktop sessions              │    │
│  │ - Reads registry (PID check inline)                 │    │
│  │ - inProjects, unscanned sessions                    │    │
│  │ - Reads HEAD files of every work tree (Git.head)    │    │
│  │ - Hands the result to the EDT (invokeLater)         │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  EDT (Event Dispatch Thread)                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Reads structure.json; prunes and reads pins.json  │    │
│  │ - BoardWriter: seen marks, unread, board.json write │    │
│  │ - SessionTerminals.retireIdle                       │    │
│  │ - Compares state; on change: retarget tabs          │    │
│  │   (publishes ides/<pid>.json if needed), notify     │    │
│  │   listeners (tree/list, tabs, context window)       │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  Thread "My Air focus requests" (daemon, TabStore)           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Watches requests/<own pid>/, hands ids to EDT     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

`ClaudeSessionsService`'s state and listeners are touched on the EDT only. One refresh runs at a time, so an older result never lands after a newer one.

### 3.2 Taskbar Widget Threading

```
┌─────────────────────────────────────────────────────────────┐
│                  Taskbar Widget Threading                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  FileSystemWatcher threads                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Events on taskbar/ and ides/ → BeginInvoke(Reload)│    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  UI Thread (WPF dispatcher)                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ BoardWatcher.Reload                                 │    │
│  │ - Reads board.json and live ides/*.json             │    │
│  │ - Also every 5 s (DispatcherTimer)                  │    │
│  │ - Arrival detection (Board.Arrivals)                │    │
│  │ - Raises Changed / Arrived                          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ UI Updates                                          │    │
│  │ - Count badges                                      │    │
│  │ - Flyout list                                       │    │
│  │ - Bubble notifications                              │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Lifecycle Management

### 4.1 Plugin Lifecycle

```
IDE Startup
    │
    ├─ First project opened → TabsSyncStartup
    │     └─ TabsSync created: publishes ides/<pid>.json,
    │        starts receiving requests/<pid>/
    │
    ▼
ClaudeSessionsService created on first getInstance()
(@Service APP: tool window, opener, TabsSync…)
    │
    ├─ Initialize watcher (ClaudeHomeWatcher)
    ├─ Initialize periodic refresh (60s)
    └─ Initial refresh (reads pins and structure on the EDT)
    │
    ▼
Running State
    │
    ├─ On file event → refresh
    ├─ On timer tick → refresh
    ├─ On tab open/close → TabsSync publishes state
    ├─ On focus request → TabsSync handles
    └─ On tab selection / IDE activation → update seen
    │
    ▼
IDE Shutdown
    │
    ├─ Exit asks first when a process it would end works (ExitConfirmation)
    ├─ ClaudeSessionsService.dispose
    │   ├─ Stop timer
    │   ├─ Stop watcher
    │   └─ Save scan cache
    └─ TabsSync.dispose
        ├─ Stop request watcher
        └─ Withdraw IDE state file
```

### 4.2 Session Tab Lifecycle

```
Tab Created (new session, opened session, or restored from myair-session://)
    │
    ▼
Tab Selected (first time; deferSessionStartUntilUiShown)
    │
    ▼
SessionTerminals.start: one claude process per tab file
    │
    ├─ Session run by a background job → claude attach <jobId>
    └─ Otherwise → claude --append-system-prompt <prompt>
                    [--settings ~/.my-air/hooks/brief-reminder.json]
                    [--append-system-prompt-file ~/.my-air/prompts/<pid>-<uuid>.md]
                    --resume <id> | --session-id <uuid>
    │
    ▼
Running State
    │
    ├─ Registry refreshes give status; retargeting follows /clear, /resume, /bg
    ├─ Update tab title/icon
    └─ Publish state changes
    │
    ▼
Tab Closed (SessionTerminals.release)
    │
    ├─ Process never started or ended → close it
    ├─ Platform reopening the tab, or keepDetached on (default) and not a job view
    │     → runs on detached; ended by retireIdle once detached ≥ 60 s,
    │       idle, and no background task it started still runs
    └─ Otherwise (keepDetached off, or a view of a job) → end the process
```

A restored tab's URL `myair-session://<id>?cwd=…&project=…` resolves into a tab with `--resume <id>` when a transcript exists, else `--session-id <id>`. A second tab of the same file takes the process over; the first shows Show Here. Close and Settle, the process ending by itself and closing its project end it. Closing a project asks first when a process it would end works (`ProjectCloseConfirmation`).

### 4.3 Taskbar Widget Lifecycle

```
Widget Started
    │
    ▼
Initialize
    │
    ├─ Single-instance mutex Local\MyAir.Taskbar (second copy exits)
    ├─ BoardWatcher: read board.json, read ides/*.json,
    │  start watchers and 5 s timer
    └─ Attach MainWindow to the taskbar
    │
    ▼
Running State
    │
    ├─ On board change → update UI
    ├─ On hover → show flyout
    ├─ On arrival → show bubble
    ├─ On click → send focus request, write seen/<id>
    ├─ On taskbar recreation (Explorer restart, DPI) → re-attach a new window after 1 s
    └─ On unhandled exception → MessageBox, Shutdown(1)
```

---

## 5. Refresh Mechanism

### 5.1 Trigger Sources

| Trigger | Source | Debounce |
|---------|--------|----------|
| File event | `~/.claude/sessions/`, `projects/<dir>/`, `~/.my-air/`, `~/.my-air/seen/` | 500ms/700ms |
| Periodic | Timer | 60s |
| Manual | Refresh action, Settings apply | Immediate |

### 5.2 Refresh Steps

1. **Scan transcripts** – Read `.jsonl` files for session metadata (pool thread)
2. **Read registry** – Parse `<pid>.json` files for live status (pool thread)
3. **Validate PIDs** – Check start time against `procStart` (pool thread)
4. **Merge state** – Projects through worktrees (`inProjects`), unscanned sessions, work tree heads (pool thread)
5. **Update seen** – Mark sessions first seen and those whose tab is visible in the active window (EDT, `BoardWriter.update`)
6. **Compute unread** – Compare last message with seen timestamp (EDT)
7. **Write board** – Update `taskbar/board.json` when its content changed (EDT)
8. **Retire detached processes** – `SessionTerminals.retireIdle` (EDT)
9. **Retarget tabs** – Point tabs at the session their process now shows; publish `ides/<pid>.json` when a tab's session or title changed (EDT)
10. **Notify UI** – Update tree/list/tabs on EDT

Steps 9 and 10 run only when the state or unread set changed.

### 5.3 Debouncing

- **Quiet period:** 500ms – wait for file events to settle
- **Cap:** 700ms – maximum wait time
- **Purpose:** Avoid excessive refreshes during rapid changes

---

## 6. Cross-IDE Coordination

### 6.1 State Publishing

Each IDE instance publishes its state to `ides/<pid>.json`:
- **On:** Tab open, tab close, project startup, re-targeting, a detached process retired
- **Format:** `{ pid, started, tabs: [{ sessionIds: [...], title, project }] }`
- **Withdrawn:** On IDE dispose

### 6.2 State Reading

Each IDE reads other IDEs' state on demand:
- **On:** Opening a session (`TabsSync.focusElsewhere`) and Focus Claude Session
- **Purpose:** Detect sessions open elsewhere
- **Handling:** Focus request to the IDE with the tab

### 6.3 Focus Request Handling

```
Sender: AllowSetForegroundWindow(target pid), write requests/<pid>/<uuid>.json
    │
    ▼
Receiver thread "My Air focus requests" (also drains waiting requests at start)
    │
    ├─ Parse request, delete file
    │
    ▼
EDT
    ├─ Find session in local tabs
    │   └─ Focus tab
    │
    └─ Session not in tabs
        ├─ No transcript yet → log warning
        └─ Focus the project window holding its cwd (else the first),
           ClaudeSessionOpener.open
```

### 6.4 Window Detection

- **Purpose:** Find window of program running a session
- **Method:** Java `ProcessHandle` for the ancestry, user32 via JNA for windows
- **Process:**
  1. Walk the `claude` process's ancestors (`ProcessHandle.parent()`)
  2. Find nearest ancestor with visible titled window (`EnumWindows`); among several, the one titled with the session folder
  3. Return window handle
- **Use:** Warning dialog for sessions in other programs; Switch to Window brings it to the front

---

## 7. Error Handling

### 7.1 Format Errors

- **Behavior:** Throw exception, fail loudly
- **Scope:** Unknown registry statuses, missing required fields
- **Rationale:** Claude Code format changes must be detected

### 7.2 File Access Errors

- **Behavior:** A failing refresh is logged as an IDE error and dropped; the next refresh tries again. A broken `structure.json` is shown in Folders mode, which then allows no changes; a broken `pins.json` throws.
- **Tolerated:** A registry or IDE file deleted between listing and reading
- **Rationale:** Problems must be visible, not skipped

### 7.3 Cross-IDE Errors

- **Behavior:** Throw (`check`) when Windows refuses `AllowSetForegroundWindow` or `SetForegroundWindow`
- **Retry:** Only `writeAtomically`'s rename, up to 5 attempts on access denied or sharing violation
- **Dead IDEs:** Their `ides/<pid>.json` is ignored and removed

### 7.4 Taskbar Widget Errors

- **Behavior:** An unhandled exception shows a MessageBox with it, then the app shuts down with exit code 1
- **Scope:** Board parse errors (strict), focus request failures
- **Rationale:** A widget that vanishes silently looks like a hung taskbar

---

## 8. Performance Considerations

### 8.1 Scan Optimization

- **Incremental scans:** Read only complete lines appended after the stored offset; the scan cache is saved by the first scan, then at most once a minute and on exit
- **Debounce:** Avoid excessive refreshes
- **Periodic fallback:** Keep state current without constant polling

### 8.2 Memory Management

- **Session list:** In-memory, refreshed each cycle
- **History:** Bounded to the newest 20 snapshots, pruned on each snapshot

### 8.3 UI Responsiveness

- **EDT isolation:** Scans, registry reads and HEAD reads run on the refresh pool; Session Context's git reads on a background thread. The small `~/.my-air` reads and writes run on the EDT.
- **Lazy loading:** Tabs start on selection

---

## 9. Traceability

| Runtime Aspect | FR Coverage |
|----------------|-------------|
| Threading | FR-SD-003, FR-SD-008 |
| Lifecycle | FR-SM-003, FR-CI-010 |
| Refresh | FR-SD-003, FR-SD-008 |
| Cross-IDE | FR-CI-* |
| Error Handling | FR-SD-010 |
| Performance | NFR-004 |
