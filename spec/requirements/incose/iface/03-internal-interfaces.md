# Internal Interfaces – My Air System

**Related High-Level View Section:** 6.1  
**Related Functional Requirements:** All

---

## 1. Overview

The My Air plugin is organized into feature-based packages under `dev.myair`. This document describes the internal interfaces between these packages, including data flow, service dependencies, and communication patterns.

---

## 2. Internal Interface Matrix

| Interface | From Package | To Package | Type | Description |
|-----------|--------------|------------|------|-------------|
| **II-1** | claude | service | Data provider | Session discovery results |
| **II-2** | store | service | State persistence | Read/write state files |
| **II-3** | folders | service | Organization | Folder structure management |
| **II-4** | mcp | service | External interface | MCP tool execution |
| **II-5** | service | editor | State distribution | Re-targeting open tabs and their statuses after each refresh |
| **II-6** | service, editor | tabs | Coordination | Cross-IDE tab publishing and focus requests |
| **II-7** | actions | editor, tabs, service | User commands | Actions open sessions through `ClaudeSessionOpener`, `TabsSync` and `startPreset` |
| **II-8** | toolwindow | service | UI updates | Panel refresh |
| **II-9** | prompt | actions | Session creation | Composer submission |
| **II-10** | branchdiff | service | Diff target | Session branch info |
| **II-11** | toolwindow, branchdiff | service (`ActiveSession`) | Active session | Session of the active tab |

---

## 3. Package Dependencies

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Claude  │────►│ Service  │◄────│  Store   │
│ (Reader) │     │ (Core)   │     │(Persist) │
└──────────┘     │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │◄────│ Folders  │
                 │          │     │(Organize)│
                 │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │◄────│   MCP    │
                 │          │     │(External)│
                 │          │     └──────────┘
                 │          │
                 │          │     ┌──────────┐
                 │          │────►│  Editor  │
                 │          │     │  (Tabs)  │
                 │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │────►│   Tabs   │
                 │          │     │(CrossIDE)│
                 │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │◄────│ Actions  │
                 │          │     │  (Menu)  │
                 │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │◄────│ToolWindow│
                 │          │     │   (UI)   │
                 │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │◄────│  Prompt  │
                 │          │     │ (Composer)│
                 │          │     └──────────┘
                 │          │     ┌──────────┐
                 │          │◄────│BranchDiff│
                 │          │     │(Optional)│
                 └──────────┘     └──────────┘
```

Arrows point from the package that calls to the package it depends on, except Claude/Store (data into the service) and Editor/Tabs (the service re-targets tabs and publishes them).

---

## 4. II-1: Claude → Service Interface

### 4.1 Description

The `claude` package reads and interprets Claude Code's file formats, providing session data to the core service.

### 4.2 Interface Points

| Class/Function | Purpose | Data Type |
|----------------|---------|-----------|
| `ClaudeSessionScanner.scan(desktopProjects: Map<String, Path> = emptyMap())` | Scan transcript files | `List<ClaudeSession>` |
| `LiveSessionRegistry.read()` | Read registry files | `Map<String, LiveSession>` keyed by session id |
| `ClaudeHomeWatcher(dirs, projectsDir, quiet = 500.ms, maxWait = 700.ms, onChange: () -> Unit)` | Detect file changes; calls `onChange` on its own thread, without paths | `Closeable` |
| `DesktopSessionReader.read()` | Claude Desktop start folders | `Map<String, Path>` |

### 4.3 Data Flow

```
ClaudeHomeWatcher event / 60 s timer
        │
        ▼
ClaudeSessionsService.refresh() (pool thread)
        │
        ▼
ClaudeSessionScanner.scan + LiveSessionRegistry.read
        │
        ▼
State applied on the EDT, listeners notified
```

### 4.4 Key Behaviors

- **Debounced scanning:** One debounce for every watched directory: 500 ms after the last event, at most 700 ms after the first
- **Format validation:** Unknown formats throw exceptions
- **Incremental updates:** Only lines appended after the stored offset are read
- **Error propagation:** An exception from any file aborts the whole refresh; it is logged as an IDE error and the previous state stays

---

## 5. II-2: Store → Service Interface

### 5.1 Description

The `store` package manages shared state files in `~/.my-air/`, providing persistence to the service.

### 5.2 Interface Points

| Class | Purpose | Operations |
|-------|---------|------------|
| `TabStore` | IDE tab state and focus requests | `publish`, `all`, `withdraw`, `requestFocus`, `receiveRequests` |
| `PinStore` / `Pins` | Pin/settle state (`pins.json`) | `PinStore.read`, `PinStore.update(op)`; `Pins.pin`, `unpin`, `remove`, `settle`, `unsettle`, `prune` |
| `PresetStore` | Session configs (`presets.json`) | `read(): Result<List<Preset>>`, `write` |
| `SeenStore` | Last seen times (`seen/<id>`) | `read(): Map<String, Long>`, `mark(sessionId, at)` |
| `AtomicFiles.kt` | Write utility | `writeAtomically(target, text)` |

### 5.3 Data Flow

```
Service state change
        │
        ▼
Store.write()
        │
        ▼
Atomic write (temp file + move)
        │
        ▼
File updated in ~/.my-air/
```

### 5.4 Key Behaviors

- **Atomic writes:** Prevents corrupted state
- **Shared access:** All IDEs can read/write
- **Thread safety:** `PinStore.update` and `PresetStore` are `@Synchronized`; across IDEs each change re-reads the file before applying it
- **Error handling:** A failed rename is retried up to 5 times on access-denied or sharing violation, then throws

---

## 6. II-3: Folders → Service Interface

### 6.1 Description

The `folders` package manages folder structure, plans, and session filing.

### 6.2 Interface Points

| Class/Function | Purpose | Data Type |
|----------------|---------|-----------|
| `SessionFolders` | Folder model | Folder tree |
| `StructureStore` | Persistence | Read/write structure |
| `StructureHistory` | Undo support | Snapshot management |
| `FolderChanges` | Change operations | Apply changes |
| `PlanChanges` | Plan operations | Modify plans |

### 6.3 Data Flow

```
User/Agent request
        │
        ▼
Service.updateFolders(source: ChangeSource, op: (SessionFolders) -> SessionFolders)
        │
        ▼
Create history snapshot
        │
        ▼
Apply changes atomically
        │
        ▼
Update UI + notify listeners
```

### 6.4 Key Behaviors

- **All-or-nothing:** Changes applied atomically
- **History:** Each change leaves snapshot
- **Source tracking:** `ChangeSource` UI, AUTO, MCP, BRIEF, RESTORE
- **Validation:** Invalid operations rejected

---

## 7. II-4: MCP → Service Interface

### 7.1 Description

The `mcp` package exposes tools via the IDE's MCP server for external agents. `FoldersMcpToolset` and `BriefMcpToolset` are registered as `mcpToolset` in `my-air-mcp.xml`, loaded by an optional dependency on `com.intellij.mcpServer`.

### 7.2 Interface Points

| Tool | Purpose | Service Methods Used |
|------|---------|---------------------|
| `my_air_sessions_find` | Search sessions | `service.sessions`, `service.folders`, `service.branchOf` |
| `my_air_session_start` | Create session | `service.folders`, `createWorktree`, `ClaudeSessionOpener` |
| `my_air_folders_get` | Read folders | `service.folders` |
| `my_air_folders_apply` | Modify folders | `service.updateFolders` |
| `my_air_plan_get` | Read plans | `service.folders` |
| `my_air_plan_apply` | Modify plans | `service.updateFolders` |
| `my_air_history_list` | List snapshots | `service.history` |
| `my_air_history_restore` | Restore snapshot | `service.updateFolders(ChangeSource.RESTORE)` |
| `my_air_set_brief` | Set brief | `service.folders`, `service.updateFolders(ChangeSource.BRIEF)` |

### 7.3 Data Flow

```
External Agent
        │
        ▼
MCP Server (IDE)
        │
        ▼
McpToolset tool method (suspend)
        │
        ▼
Service method (on EDT); git work on Dispatchers.IO
        │
        ▼
Result returned to agent
```

### 7.4 Key Behaviors

- **EDT execution:** Service access runs on the EDT (`withContext(Dispatchers.EDT)`); repository and worktree creation run on `Dispatchers.IO`
- **Error handling:** Errors returned to agent (`McpExpectedError`), no dialogs
- **Input validation:** Parameters validated before execution
- **All-or-nothing:** Multi-step operations atomic

---

## 8. II-5: Service → Editor Interface

### 8.1 Description

The service provides session state to the editor package for tab management.

### 8.2 Interface Points

| Class/Function | Purpose | Data Type |
|----------------|---------|-----------|
| `ClaudeSessionEditor` | Tab editor | Session tab |
| `ClaudeSessionOpener.open(project, session, switchToWindow = false)` | Open session | Priority chain |
| `SessionTerminals` | Terminal mgmt | Terminal process |

### 8.3 Data Flow

```
User selects session
        │
        ▼
ClaudeSessionOpener.open()
        │
        ▼
Check priority chain
        │
        ├─ Local tab exists → Select it
        ├─ Other IDE has it → Focus that IDE
        ├─ Running elsewhere → Show warning
        └─ Not running → Create new tab
```

### 8.4 Key Behaviors

- **Priority chain:** Local > Other IDE > Running > New
- **Lazy start:** A tab restored at IDE start starts its process when first selected
- **Status tracking:** Tab follows session status
- **Cross-IDE:** Coordinates with other instances

---

## 9. II-6: Service → Tabs Interface

### 9.1 Description

The tabs package handles cross-IDE coordination and state synchronization.

### 9.2 Interface Points

| Class/Function | Purpose | Data Type |
|----------------|---------|-----------|
| `TabsSync.publish()` | Publish tab state | `ides/<pid>.json` |
| `TabsSync.allIdes()` | Read all IDEs' tabs on demand, this one first | `List<IdeTabs>` from `ides/*.json` |
| `TabsSync.focusElsewhere(sessionId)` / `focusIn(ide, sessionId)` | Ask another IDE to focus a session | `requests/<pid>/` |
| `SessionHosts.findTab(sessionId)` | Find this IDE's tab of a session (also Air's tabs) | `SessionTab?` |
| `SessionHosts.hostWindow(pid, cwd)` | Window hosting a `claude` run outside the IDE | `HostWindow?` |

### 9.3 Data Flow

```
Tab state change
        │
        ▼
TabsSync.publish()
        │
        ▼
Write ides/<pid>.json
        │
        ▼
Other IDEs and the taskbar widget read it when they need it
(focusing a session, Focus Claude Session..., widget reload)
```

### 9.4 Key Behaviors

- **Publish events:** Tab open/close, startup, retargeting
- **Read on demand:** Other IDEs' state read when needed
- **Focus requests:** Write to requests/<pid>/
- **Window detection:** Via Win32 API

---

## 10. II-7: Actions → Service Interface

### 10.1 Description

The actions package provides menu actions that interact with the service.

### 10.2 Interface Points

| Action | Purpose | Methods Used |
|--------|---------|-----------------|
| `NewClaudeSessionAction` | Create session | `ClaudeSessionOpener.startNew(project)` |
| `FocusClaudeSessionAction` | Focus session | `TabsSync.allIdes()`, `TabsSync.focusIn` |
| `NextSessionNeedingMeAction` | Cycle attention | `attentionQueue(sessions, live, unread)`, `nextInQueue`, `ClaudeSessionOpener.open(..., switchToWindow = true)` |
| `NewClaudeSessionInWorktreeAction` | Worktree session | `Git.addWorktree` / `addBranchWorktree`, pure helpers in `Worktrees.kt` |

### 10.3 Key Behaviors

- **User triggered:** Menu actions, shortcuts
- **Service interaction:** Actions read service state and call the opener
- **Feedback:** Errors shown in dialogs and popups; balloon notifications (group "My Air") from `SessionTerminals`
- **State changes:** Actions may modify session state

---

## 11. II-8: ToolWindow → Service Interface

### 11.1 Description

The toolwindow package provides the main session panel UI.

### 11.2 Interface Points

| Component | Purpose | Service Methods |
|-----------|---------|-----------------|
| `SessionsPanel` | Main panel | `ClaudeSessionsService.subscribe(parent, listener)` |
| `SessionContextPanel` | Session Context pane | `ActiveSession.subscribe`, `ClaudeSessionsService.subscribe` |
| `TreeState` | View state | Persistence (`TreeStates.kt`) |

### 11.3 Data Flow

```
Service state change
        │
        ▼
Listener notification
        │
        ▼
Panel refresh
        │
        ▼
UI updated
```

### 11.4 Key Behaviors

- **Subscribe:** Panel subscribes to service updates
- **View modes:** Projects, Folders
- **Search:** Filter sessions
- **State persistence:** Tree expansion saved

---

## 12. II-9: Prompt → Actions Interface

### 12.1 Description

The prompt package provides the session composer for creating new sessions.

### 12.2 Interface Points

| Class | Purpose | Output |
|-------|---------|--------|
| `PromptPanel` | Composer UI | Session parameters |
| `Composer.kt` | Shared look: card, bar, chips, send button | UI parts |
| `PromptChoices.kt` | Last choices and drafts | Restored choices |

### 12.3 Data Flow

```
User fills composer
        │
        ▼
PromptPanel builds a Preset from the choices
        │
        ▼
startPreset(project, preset, where, prompt, branch)
        │
        ▼
ClaudeSessionOpener starts the session in a tab
```

### 12.4 Key Behaviors

- **Parameter collection:** Model, effort, directory, etc.
- **Validation:** Required fields checked
- **Image support:** Paste images as @path
- **Session creation:** Submits to `startPreset` (actions)

---

## 13. II-10: BranchDiff → Service Interface

### 13.1 Description

Optional integration with Branch Diff plugin.

### 13.2 Interface Points

| Class | Purpose | Data Type |
|-------|---------|-----------|
| `SessionDiffTargetProvider` | Diff target | Session branch |

### 13.3 Key Behaviors

- **Follows active session:** Updates on session change
- **Optional:** Feature disabled if plugin not installed
- **Base writes:** `setBase` stores the base in git config and calls `ActiveSession.baseChanged`

---

## 14. Thread Safety

### 14.1 EDT Requirements

| Package | EDT Required | Reason |
|---------|--------------|--------|
| service | State applied on EDT; refresh reads on a pool thread | Holds main state |
| mcp | Service access on EDT; git on `Dispatchers.IO` | Calls service methods |
| editor | Yes | UI operations |
| tabs | UI on EDT; focus requests received on thread "My Air focus requests" | UI operations |
| actions | `actionPerformed` on EDT; several `update` on BGT | User interaction |
| toolwindow | Yes | UI updates |
| prompt | Yes | UI operations |

### 14.2 Background Threads

| Package | Background Use | Reason |
|---------|----------------|--------|
| claude | Yes | File scanning, parsing |
| store | Yes | File I/O |
| folders | `updateFolders` on EDT | Service state |
| branchdiff | Yes | Git operations |

---

## 15. Error Propagation

### 15.1 Error Flow

```
Lower layer error
        │
        ▼
Service layer (logs, may rethrow)
        │
        ▼
UI layer (shows notification)
```

### 15.2 Error Types

| Layer | Error Type | Handling |
|-------|------------|----------|
| claude | Format error | Throw; refresh aborted, logged as IDE error |
| store | I/O error | Rename retried up to 5 times on transient errors, then throw |
| folders | Validation error | Reject operation |
| mcp | Execution error | Return to agent |
| actions | User error | Dialog or popup |
| toolwindow | Render error | Log, show placeholder |

---

## 16. Traceability

| Interface | FR Coverage | Component Doc |
|-----------|-------------|---------------|
| II-1 (Claude→Service) | FR-SD-* | arch/01-component-architecture.md §3.1 |
| II-2 (Store→Service) | FR-SM-*, FR-SO-* | arch/01-component-architecture.md §3.2 |
| II-3 (Folders→Service) | FR-SO-* | arch/01-component-architecture.md §3.3 |
| II-4 (MCP→Service) | FR-MT-* | arch/01-component-architecture.md §3.4 |
| II-5 (Service→Editor) | FR-SM-* | arch/01-component-architecture.md §3.6 |
| II-6 (Service→Tabs) | FR-CI-* | arch/01-component-architecture.md §3.7 |
| II-7 (Actions→Service) | FR-SC-* | arch/01-component-architecture.md §3.8 |
| II-8 (ToolWindow→Service) | FR-SM-*, FR-SO-* | arch/01-component-architecture.md §3.9 |
| II-9 (Prompt→Actions) | FR-SC-* | arch/01-component-architecture.md §3.10 |
| II-10 (BranchDiff→Service) | FR-CD-005 | arch/01-component-architecture.md §3.11 |

---

## 17. II-11: ActiveSession Interface

`ActiveSession` (`service/ActiveSession.kt`) is a project service holding the session tab Session Context and Branch Diff follow.

| Member | Purpose |
|--------|---------|
| `tab: ClaudeSessionVirtualFile?` (`@Volatile`) | The selected session tab, else the last one while it stays open |
| `subscribe(parent: Disposable, listener: () -> Unit)` | Notified when the tab, its session, that session's head or live entry changes |
| `baseChanged()` | Called after a base is stored (Session Context's base popup, Branch Diff `setBase`), so both panes re-read it |
| `getInstance(project)` | Service lookup |

---

## 18. Session Tab Virtual File System

| Element | Description |
|---------|-------------|
| `ClaudeSessionFileSystem` | `virtualFileSystem` with key `myair-session` |
| URL | `myair-session://<id>?cwd=…&project=…`, computed from the session the tab shows now (`SessionTabUrl.kt`) |
| Restore | The platform saves the URL with the open tabs and resolves it on startup as a new tab, never into Recent Files; it gets `--resume`, or `--session-id` when the session has no transcript yet |
| `ClaudeSessionVirtualFile` | Carries session id, cwd, project, title, `claude` arguments, job and plan start |

---

## 19. Platform Extension Points and Actions

### 19.1 Extensions (`plugin.xml`)

| Extension | Implementation |
|-----------|----------------|
| `toolWindow` `Claude Sessions` (right) | `SessionsToolWindowFactory` |
| `toolWindow` `Session Context` (right, secondary) | `SessionContextToolWindowFactory` |
| `fileEditorProvider` | `ClaudeSessionEditorProvider` |
| `virtualFileSystem` `myair-session` | `ClaudeSessionFileSystem` |
| `fileIconProvider` (first) | `ClaudeSessionFileIconProvider` |
| `editorTabTitleProvider` | `ClaudeSessionTabTitleProvider` |
| `editorTabColorProvider` | `ClaudeSessionTabColorProvider` |
| `editorEmptyStateComponentProvider` | `PromptEmptyState` (internal API) |
| `projectCloseHandler` | `ProjectCloseConfirmation` |
| `applicationConfigurable` (parent `tools`) | `MyAirConfigurable` |
| `postStartupActivity` | `TabsSyncStartup` |
| `notificationGroup` | `My Air` (balloon) |
| Application listeners | `SeenOnActivation` (`ApplicationActivationListener`), `SessionTerminals$ProjectClose` (`ProjectCloseListener`); `ExitConfirmation` (`ApplicationListener`) registered by `SessionTerminals` at runtime |
| Project listener | `TabsSyncListener` (`FileEditorManagerListener`) |
| Optional `com.intellij.mcpServer` | `my-air-mcp.xml`: `mcpToolset` `FoldersMcpToolset`, `BriefMcpToolset` |
| Optional `dev.kosmala.branch-diff-tree` | `my-air-branch-diff.xml`: `diffTargetProvider` `SessionDiffTargetProvider` |

### 19.2 Actions

| Id | Text | Placement |
|----|------|-----------|
| `dev.myair.NewClaudeSession` | New Claude Session | Tools menu, main toolbar |
| `dev.myair.NewClaudeSessionInWorktree` | New Claude Session in Worktree... | Tools menu, main toolbar |
| `dev.myair.NewSessionWithPrompt` | New Claude Session with Prompt... | Tools menu, main toolbar (first) |
| `dev.myair.FocusClaudeSession` | Focus Claude Session... | Tools menu |
| `dev.myair.NextSessionNeedingMe` | Next Claude Session Needing Me | Tools menu, Alt+Shift+K |
| `dev.myair.InstallMyAirBuild` | Install My Air Build and Restart | Tools menu, only in a project with `build/distributions/my-air-*.zip` |
| `dev.myair.CloseAndSettle` | Close and Settle | `CloseEditorsGroup` after Close |
| `dev.myair.MainToolbar` | Group with the three new-session actions and `PresetsGroup` | `MainToolbarRight`, first |

---

## 20. Terminal and Air Integration

| Element | Description |
|---------|-------------|
| `TransferableTerminalSessionFactory.create(project, options, null, deferSessionStartUntilUiShown = true)` | Internal terminal API (content module `intellij.terminal.frontend`) that runs `claude` apart from the tab; one process per tab file, views bound with `attach`/`bind` |
| `TransferableTerminalSession.processId` | Pid of the `claude` process |
| `intellij.platform.vcs.impl` | Content module dependency for the change views of Session Context |
| JetBrains Air tabs | Read by reflection: a file of Air's tab class exposes `getThreadId` and `getSessionId`, used by `SessionHosts.findTab` |
