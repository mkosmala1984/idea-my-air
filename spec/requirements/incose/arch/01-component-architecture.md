# Component Architecture – My Air System

**Related High-Level View Section:** 6.1  
**Related Functional Requirements:** All

---

## 1. Overview

The My Air system consists of two main components:
1. **IntelliJ Plugin** (primary) – Kotlin-based IDE extension
2. **Taskbar Widget** (secondary) – Windows 11 taskbar application (WPF, .NET 10)

The plugin is further decomposed into feature-based packages under `dev.myair`. Many components are Kotlin files of top-level functions rather than classes; the tables name the file in that case.

---

## 2. IntelliJ Plugin Components

### 2.1 Package Structure

| Package | Responsibility | Key Classes / Files |
|---------|----------------|-------------|
| `dev.myair` | Application settings | `MyAirSettings` (persistent state), `MyAirConfigurable` (Settings › Tools › My Air) |
| `dev.myair.claude` | Reading Claude's files | `ClaudeSession`, `ClaudeSessionScanner`, `LiveSessionRegistry`, `ClaudeHomeWatcher`, `DesktopSessionReader` |
| `dev.myair.store` | Files in `~/.my-air` and atomic writes | `TabStore`, `PinStore`/`Pins`, `PresetStore`/`Preset`, `SeenStore`, `PromptFiles`, `writeAtomically` (`AtomicFiles.kt`) |
| `dev.myair.folders` | Folder model, structure store, history | `SessionFolders`, `StructureStore`, `StructureHistory`, `Plan` |
| `dev.myair.mcp` | MCP toolset and queries | `FoldersMcpToolset`, `BriefMcpToolset`, `SessionSearch.kt`, `SessionStart.kt`, `McpJson.kt` |
| `dev.myair.service` | Main service, taskbar board, tab re-targeting, active session | `ClaudeSessionsService`, `BoardWriter`, `TaskbarBoard.kt`, `TabRetargeting.kt`, `ActiveSession` |
| `dev.myair.editor` | Session tabs, their processes and opener | `ClaudeSessionEditor`, `ClaudeSessionOpener`, `SessionTerminals`, `ClaudeSessionFileSystem` |
| `dev.myair.tabs` | Finding tabs and windows, cross-IDE sync | `TabsSync`, `SessionHosts` |
| `dev.myair.actions` | Menu actions, worktrees, Git | `NewClaudeSessionAction`, `Worktrees.kt`, `Git` |
| `dev.myair.toolwindow` | Claude Sessions and Session Context panels | `SessionsPanel`, `SessionContextPanel` |
| `dev.myair.prompt` | New-session composer | `PromptPanel`, `Composer.kt` |
| `dev.myair.branchdiff` | Branch Diff integration | `SessionDiffTargetProvider` |

---

## 3. Component Responsibilities

### 3.1 Claude Package (Session Discovery)

**Responsibility:** Read and interpret Claude Code's file formats.

**Components:**
- `ClaudeSession` – Data model for a session
- `ClaudeSessionScanner` – Scans transcript files from `~/.claude/projects/`, incrementally from a saved offset per transcript
- `LiveSessionRegistry` – Reads registry files from `~/.claude/sessions/`
- `ClaudeHomeWatcher` – Watches file system changes on its own daemon thread, debounced 500 ms quiet / 700 ms cap
- `DesktopSessionReader` – Reads Claude Desktop's `%LOCALAPPDATA%/Claude-Data/*/claude-code-sessions/*/*/local_*.json` for the folder a Desktop session was started in (`originCwd`)
- `Brief` – Session brief model
- `SkillFiles`, `SkillOrigin` – Skill discovery

**Key Behavior:**
- Scans transcripts to extract session metadata
- Reads live registry for running sessions
- Detects format changes and fails loudly
- Watches for file changes to trigger refreshes

---

### 3.2 Store Package (State Persistence)

**Responsibility:** Manage shared state files in `~/.my-air/`.

**Components:**
- `TabStore` – Manages `ides/<pid>.json` (IDE tab state) and `requests/<pid>/` (focus requests)
- `PinStore` – Manages `pins.json`, holding a `Pins` value (pinned, dismissed and settled sessions)
- `PresetStore` – Manages `presets.json`, a list of `Preset` (session configurations)
- `SeenStore` – Manages `seen/<id>` (last seen timestamps)
- `PromptFiles` – Writes plan prompt files `prompts/<IDE pid>-<uuid>.md`
- `writeAtomically` – The shared atomic write (see 6.3)

**Key Behavior:**
- Atomic writes (temp file + POSIX-semantics rename)
- Shared across all IDE instances

---

### 3.3 Folders Package (Session Organization)

**Responsibility:** Manage folder structure, plans, and session filing.

**Components:**
- `SessionFolders` – Folder model and operations
- `StructureStore` – Persistence of folder structure (`structure.json`)
- `StructureHistory` – Copies of `structure.json` taken before each change (`history/`, newest 20 kept)
- `Plan` – Plan folder model (milestones, tasks)
- `FolderChanges`, `PlanChanges` – Change lists for MCP

**Key Behavior:**
- Custom folder hierarchy
- Directory binding for auto-filing
- Plan folders with milestones/tasks
- History and undo support

---

### 3.4 MCP Package (External Agent Interface)

**Responsibility:** Expose tools via IDE's MCP server.

**Components:**
- `FoldersMcpToolset` – Folder, plan, history and session tools
- `BriefMcpToolset` – Session brief tool
- `SessionSearch.kt` – Session search (`findSessions`)
- `SessionStart.kt` – Session creation from tool inputs (`sessionStartPreset`)
- `McpJson.kt` – JSON rendering of tool results

**Key Behavior:**
- Tools registered via `my-air-mcp.xml`
- All-or-nothing operations
- Error handling without dialogs

---

### 3.5 Service Package (Core Coordination)

**Responsibility:** Central coordination and state management.

**Components:**
- `ClaudeSessionsService` – Application service, main entry point
- `BoardWriter` – Writes `taskbar/board.json` and marks sessions seen; the board content comes from `boardSessions`/`recentSessions` (`TaskbarBoard.kt`)
- `TabRetargeting.kt` – Points every open tab, and every detached process, at the session its process (pid) or background job now shows, after `/clear`, `/resume` or `/bg`
- `ActiveSession` – Project service: the selected session tab, else the last one while it stays open; notifies when the tab, its session, that session's head or live entry changes
- `SessionProjects.kt` (`inProjects`) – Gives a session in a linked worktree its main checkout as project
- `UnscannedSessions.kt` – Running sessions without a transcript yet, listed as "New session"

**Key Behavior:**
- Refreshes on file events + every 60 s
- Holds session list and live statuses
- Re-targets open tabs by PID
- Notifies subscribed panels

---

### 3.6 Editor Package (Session Tabs)

**Responsibility:** Manage session tabs in the editor and the `claude` processes behind them.

**Components:**
- `ClaudeSessionEditor` – Editor for session tabs
- `ClaudeSessionOpener` – Opens sessions with priority chain
- `SessionTerminals` – Application service owning one `claude` process per tab file, apart from the tab
- `ClaudeSessionVirtualFile` – Virtual file for sessions
- `ClaudeSessionFileSystem` – The `myair-session://` file system that lets tabs survive a restart
- `SessionIcons.kt` – Status icons (`sessionIcon`)
- `CloseAndSettleAction` – Close and settle
- `ProjectCloseConfirmation`, `ExitConfirmation` – Ask before closing a project or exiting ends a working session

**Key Behavior:**
- Runs `claude --resume <id>`, `claude --session-id <uuid>` or `claude attach <jobId>` in a terminal
- Priority chain for opening
- Lazy start on selection

---

### 3.7 Tabs Package (Cross-IDE Coordination)

**Responsibility:** Find tabs/windows and sync across IDEs.

**Components:**
- `TabsSync` – Publishes this IDE's tabs and receives focus requests
- `SessionHosts` – Finds tabs and host windows

**Key Behavior:**
- Publishes on tab open/close, startup, re-targeting
- Reads other IDEs' state on demand
- Focus request handling
- Window detection via Win32

---

### 3.8 Actions Package (User Actions)

**Responsibility:** Menu actions, worktrees, Git operations.

**Components:**
- `NewClaudeSessionAction` – Create new session
- `NewClaudeSessionInWorktreeAction` – Create in worktree
- `Worktrees.kt` – Pure worktree and base-branch logic (`baseChoices`, `pushRecent`, `defaultBranch`, `worktreePath`)
- `Git` – Git operations through the `git` CLI
- `FocusClaudeSessionAction` – Focus session
- `NextSessionNeedingMeAction` – Cycle attention
- `PresetActions.kt` – `PresetAction`, `PresetsGroup`, `SameSettingsAction`, `SaveAsPresetAction`
- `InstallMyAirBuildAction` – Install build

**Key Behavior:**
- Session creation flows
- Worktree setup
- Git integration
- Cross-IDE focus

---

### 3.9 ToolWindow Package (Session Panels)

**Responsibility:** Claude Sessions and Session Context tool windows.

**Components:**
- `SessionsPanel` – Claude Sessions panel (`SessionsToolWindowFactory`)
- `TreeStates.kt` – Persists the platform's `TreeState` per mode
- `SessionContextPanel` – Session Context panel (`SessionContextToolWindowFactory`): the active session's model, environment, branch and changes against its base, brief, files, outputs, artifacts, background tasks, MCP servers, skills and plan

**Key Behavior:**
- Session tree/list display
- Search functionality
- View options (density, font, age filter)
- Tree state persistence
- Session Context follows `ActiveSession`

---

### 3.10 Prompt Package (Session Composer)

**Responsibility:** New-session composer UI.

**Components:**
- `PromptPanel` – Composer panel
- `Composer.kt` – Shared composer widgets (`Chip`, `Rounded`, `SendButton`)
- `NewBranchPanel` – Popup taking a new branch name, typed or suggested
- `PastedImages.kt` – Image and file pasting

**Key Behavior:**
- Air-style composer popup
- Fills empty editor area
- Image/file pasting as `@path`
- Session creation with parameters

---

### 3.11 BranchDiff Package (Optional Integration)

**Responsibility:** Integration with Branch Diff plugin.

**Components:**
- `SessionDiffTargetProvider` – Diff target provider

**Key Behavior:**
- Follows active session
- Shows diff against base branch
- Stores a base chosen in Branch Diff
- Optional dependency

---

## 4. Taskbar Widget Components

### 4.1 Component Structure

| Component | Responsibility |
|-----------|----------------|
| `Board` | Parse board/IDE data, group sessions, detect arrivals |
| `BoardWatcher` | Watch `taskbar/` and `ides/`, re-read every 5 s, send focus requests, write `seen/<id>` |
| `Flyout` | Hover flyout UI |
| `Bubble` | Notification bubble UI |
| `MainWindow` | Taskbar-hosted window |
| `Popup` | Rows and headers shared by flyout and bubble |
| `Theme` | Theme handling |
| `App` | Single-instance mutex, re-attaches the window when the taskbar is recreated |

### 4.2 Key Behavior

- Hosted in Windows 11 taskbar via `Deskband11Lib.Wpf`
- Reads `board.json` and `ides/*.json`
- Sends focus requests to IDEs
- Shows counts, flyout, and bubbles
- Activates itself before focus requests

---

## 5. Component Interaction Diagram

Arrows point from a package to a package it calls. Service, Editor and Tabs call each other in both directions. The diagram shows the main dependencies only: most packages also use Claude, Store and Folders directly.

```
┌──────────────────────────────────────────────────────────────────┐
│                       IntelliJ Plugin                             │
├──────────────────────────────────────────────────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                        │
│  │  Claude  │  │  Store   │  │ Folders  │                        │
│  │ (Reader) │  │(Persist) │  │(Organize)│                        │
│  └────▲─────┘  └────▲─────┘  └────▲─────┘                        │
│       │             │             │                               │
│  ┌────┴─────────────┴─────────────┴────┐                          │
│  │          Service (Core)             │◄──────────┐              │
│  │    ClaudeSessionsService            │           │              │
│  └────▲─────────────▲──────────────────┘           │              │
│       │ ▼           │ ▼                            │              │
│  ┌────┴────┐  ┌─────┴──────┐               ┌───────┴──────────┐   │
│  │  Editor │◄─┤   Tabs     │               │ User Interface   │   │
│  │ (Tabs)  │  │(Cross-IDE) │◄──────────────┤ ToolWindow │     │   │
│  └────▲────┘  └────────────┘               │ Prompt │ Actions │   │
│       └────────────────────────────────────┤                  │   │
│                                            └──────────────────┘   │
│  ┌──────────┐                                                     │
│  │    MCP   │ ──► Service, Folders, Editor, Actions, Claude, Store│
│  └────▲─────┘                                                     │
│       └── External Agents                                         │
└──────────────────────────────────────────────────────────────────┘
              │  ▲
   board.json │  │ requests/<pid>/, seen/<id>
   ides/*.json▼  │
┌──────────────────────────────────────────────────────────────────┐
│                   Taskbar Widget                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐      │
│  │  Board   │  │ Flyout   │  │  Bubble  │  │ BoardWatcher │      │
│  │ (Parser) │  │  (UI)    │  │  (UI)    │  │ (files)      │      │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘      │
└──────────────────────────────────────────────────────────────────┘
```

---

## 6. Design Decisions

### 6.1 File-Based Communication

**Decision:** State is read from and exchanged through files; the plugin has no IPC channel to `claude`.

**Rationale:**
- Session state comes only from the files `claude` writes
- Shared state across IDEs
- Simple, reliable, debuggable

The plugin does start and own `claude` processes in terminals, types text into them, and runs `claude stop <jobId>` (Close and Settle) and `claude -p` (branch name suggestions).

### 6.2 Feature-Based Packages

**Decision:** Organize code by feature, not by layer.

**Rationale:**
- Clear ownership
- Easy to find related code
- Tests sit in same package

### 6.3 Atomic Writes

**Decision:** State files are written through `writeAtomically`: a temp file `<name>.<uuid>.tmp`, then a Kernel32 `SetFileInformationByHandle` rename with `FILE_RENAME_FLAG_REPLACE_IF_EXISTS | FILE_RENAME_FLAG_POSIX_SEMANTICS`, retried up to 5 times on access denied or sharing violation. History snapshots (`CREATE_NEW`) and pasted images are written directly.

**Rationale:**
- Prevents corrupted state
- `Files.move` refuses to replace a file another IDE or the widget holds open; the POSIX-semantics rename replaces it anyway
- Crash-safe

### 6.4 Strict Format Validation

**Decision:** Fail loudly on unknown formats.

**Rationale:**
- Claude Code format changes should be detected
- Silent misinterpretation worse than crash
- Clear error messages

---

## 7. Traceability

| Component | FR Coverage |
|-----------|-------------|
| Claude | FR-SD-* |
| Store | FR-SM-*, FR-SO-* |
| Folders | FR-SO-* |
| MCP | FR-MT-* |
| Service | FR-SD-*, FR-CI-* |
| Editor | FR-SM-* |
| Tabs | FR-CI-* |
| Actions | FR-SC-* |
| ToolWindow | FR-SM-*, FR-SO-* |
| Prompt | FR-SC-* |
| Taskbar Widget | FR-TW-*, FR-AN-* |
