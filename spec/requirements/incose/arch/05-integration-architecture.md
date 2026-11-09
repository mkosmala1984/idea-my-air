# Integration Architecture – My Air System

**Related High-Level View Section:** 4.2, 4.3  
**Related Functional Requirements:** FR-SD-*, FR-CI-*, FR-MT-*

---

## 1. Overview

The My Air system integrates with multiple external systems and components. This document describes all external integrations and their interfaces.

---

## 2. External Integrations

### 2.1 Integration Matrix

| Integration | Type | Direction | Protocol | Status |
|-------------|------|-----------|----------|--------|
| **Claude Code** | File-based + process | Reads files; starts `claude` in terminals | File System, CLI | Required |
| **Claude Desktop** | File-based | Read-only | File System | Optional |
| **IntelliJ Platform** | API-based | Bidirectional | IntelliJ Plugin API | Required |
| **Terminal Plugin** | API-based | Bidirectional | IntelliJ Plugin API | Required |
| **MCP Server** | API-based | Bidirectional | Model Context Protocol | Optional |
| **Git** | CLI-based | Bidirectional | Command Line | Optional (features off outside a repository) |
| **Windows API** | Native | Read/Write | Win32 (via JNA) | Required |
| **Branch Diff Plugin** | API-based | Bidirectional (target out, base in) | IntelliJ Plugin API | Optional |
| **Taskbar Widget** | File-based | Bidirectional | File System | Optional |

---

## 3. Claude Code Integration

### 3.1 Integration Type

- **Method:** File-based for state; process-based for running sessions
- **Direction:** Plugin reads from Claude Code's files and starts `claude` processes
- **Protocol:** File system (JSON/JSONL), command line
- **Frequency:** Real-time (file watching) + periodic (60s)

### 3.2 Data Sources

| Path | Format | Purpose |
|------|--------|---------|
| `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl` | JSONL | Session transcripts |
| `~/.claude/sessions/<pid>.json` | JSON | Live session registry |
| `%LOCALAPPDATA%/Claude-Data/*/claude-code-sessions/*/*/local_*.json` | JSON | Folder a Claude Desktop session started in |

### 3.3 Data Consumption

```
Claude Code Process
        │
        ▼
Writes files to ~/.claude/
        │
        ▼
ClaudeHomeWatcher detects changes
        │
        ▼
ClaudeSessionScanner reads transcripts
LiveSessionRegistry reads registry
        │
        ▼
ClaudeSessionsService processes data
        │
        ▼
UI updates + state publishing
```

### 3.4 Constraints

- **No IPC:** Session state comes only from files; the plugin's own `claude` invocations are terminal sessions (`claude --resume`, `--session-id`, `attach <jobId>`), `claude stop <jobId>` (Close and Settle, 5 s timeout) and `claude -p --model haiku --no-session-persistence` (branch name suggestions)
- **Read-only:** Plugin does not modify Claude's files
- **Format dependency:** Plugin depends on Claude's file formats
- **Validation:** Unknown formats fail loudly

### 3.5 Arguments and Environment of Session Processes

Every session process gets `CLAUDE_CODE_NO_FLICKER=1` and `CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL=true`. Unless it attaches to a background job, it also gets:
- `--append-system-prompt` with the My Air prompt (`appendedSystemPrompt`): the branch's base, the My Air MCP tools, the plan rules and, with briefs on, the brief instructions
- `--settings ~/.my-air/hooks/brief-reminder.json` with briefs on: a `UserPromptSubmit` hook that reminds the agent to call `my_air_set_brief` until the transcript holds such a call
- `--append-system-prompt-file ~/.my-air/prompts/<IDE pid>-<uuid>.md` for a session in a plan: the plan's, milestone's and task's names and descriptions; deleted when the process ends

---

## 4. IntelliJ Platform Integration

### 4.1 Integration Type

- **Method:** Plugin API
- **Direction:** Bidirectional
- **Protocol:** IntelliJ Plugin SDK
- **API Level:** 263

### 4.2 Used APIs

| API Category | Purpose |
|--------------|---------|
| **Application Service** | Main service lifecycle (`@Service`) |
| **Editor API** | Session tabs management, editor providers, tab title/color/icon providers, empty editor state |
| **Virtual File System** | `myair-session://` tabs restored on startup |
| **Terminal API** | Process execution |
| **VCS API** | `Change`s and the diff views of a session's changes against its base (Git itself runs as CLI) |
| **Tool Window API** | Claude Sessions and Session Context panels |
| **Action API** | Menu actions |
| **Settings API** | Plugin configuration |
| **Notifications API** | User notifications |
| **Project API** | Project context, project close handler |

### 4.3 Plugin Descriptor

```xml
<!-- src/main/resources/META-INF/plugin.xml -->
<idea-plugin>
  <id>dev.myair</id>
  <name>My Air</name>
  <vendor>mkosmala</vendor>
  <description><![CDATA[Lists Claude Code sessions from ~/.claude/projects and resumes them in an editor tab.]]></description>

  <depends>com.intellij.modules.platform</depends>
  <depends>org.jetbrains.plugins.terminal</depends>
  <dependencies>
    <module name="intellij.platform.vcs.impl"/>
    <module name="intellij.terminal.frontend"/>
  </dependencies>
  <depends optional="true" config-file="my-air-mcp.xml">com.intellij.mcpServer</depends>
  <depends optional="true" config-file="my-air-branch-diff.xml">dev.kosmala.branch-diff-tree</depends>

  <extensions defaultExtensionNs="com.intellij">
    <projectCloseHandler implementation="dev.myair.editor.ProjectCloseConfirmation"/>
    <toolWindow id="Claude Sessions" anchor="right" icon="AllIcons.Toolwindows.ToolWindowMessages"
                factoryClass="dev.myair.toolwindow.SessionsToolWindowFactory"/>
    <toolWindow id="Session Context" anchor="right" secondary="true" icon="AllIcons.Actions.Preview"
                factoryClass="dev.myair.toolwindow.SessionContextToolWindowFactory"/>
    <fileEditorProvider implementation="dev.myair.editor.ClaudeSessionEditorProvider"/>
    <virtualFileSystem key="myair-session" implementationClass="dev.myair.editor.ClaudeSessionFileSystem"/>
    <fileIconProvider order="first" implementation="dev.myair.editor.ClaudeSessionFileIconProvider"/>
    <applicationConfigurable parentId="tools" instance="dev.myair.MyAirConfigurable" id="dev.myair.settings" displayName="My Air"/>
    <editorTabTitleProvider implementation="dev.myair.editor.ClaudeSessionTabTitleProvider"/>
    <editorTabColorProvider implementation="dev.myair.editor.ClaudeSessionTabColorProvider"/>
    <postStartupActivity implementation="dev.myair.tabs.TabsSyncStartup"/>
    <editorEmptyStateComponentProvider implementation="dev.myair.prompt.PromptEmptyState"/>
    <notificationGroup id="My Air" displayType="BALLOON"/>
  </extensions>

  <applicationListeners>
    <listener class="dev.myair.tabs.SeenOnActivation" topic="com.intellij.openapi.application.ApplicationActivationListener"/>
    <listener class="dev.myair.editor.SessionTerminals$ProjectClose" topic="com.intellij.openapi.project.ProjectCloseListener"/>
  </applicationListeners>

  <projectListeners>
    <listener class="dev.myair.tabs.TabsSyncListener" topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
  </projectListeners>

  <actions>
    <action id="dev.myair.NewClaudeSession" class="dev.myair.actions.NewClaudeSessionAction" .../>
    <action id="dev.myair.NewClaudeSessionInWorktree" class="dev.myair.actions.NewClaudeSessionInWorktreeAction" .../>
    <action id="dev.myair.NewSessionWithPrompt" class="dev.myair.prompt.NewSessionWithPromptAction" .../>
    <action id="dev.myair.FocusClaudeSession" class="dev.myair.actions.FocusClaudeSessionAction" .../>
    <action id="dev.myair.NextSessionNeedingMe" class="dev.myair.actions.NextSessionNeedingMeAction" ...>
      <keyboard-shortcut keymap="$default" first-keystroke="alt shift K"/>
    </action>
    <action id="dev.myair.InstallMyAirBuild" class="dev.myair.actions.InstallMyAirBuildAction" .../>
    <action id="dev.myair.CloseAndSettle" class="dev.myair.editor.CloseAndSettleAction" ...>
      <add-to-group group-id="CloseEditorsGroup" anchor="after" relative-to-action="CloseContent"/>
    </action>
    <group id="dev.myair.MainToolbar">
      <reference ref="dev.myair.NewSessionWithPrompt"/>
      <reference ref="dev.myair.NewClaudeSession"/>
      <reference ref="dev.myair.NewClaudeSessionInWorktree"/>
      <group id="dev.myair.Presets" class="dev.myair.actions.PresetsGroup"/>
      <separator/>
      <add-to-group group-id="MainToolbarRight" anchor="first"/>
    </group>
  </actions>
</idea-plugin>
```

`...` stands for the `text`, `description`, `icon` and `<add-to-group group-id="ToolsMenu" anchor="last"/>` of each action. Services (`ClaudeSessionsService`, `SessionTerminals`, `TabsSync`, `MyAirSettings`, `ActiveSession`) are registered by their `@Service` annotation, not in the descriptor.

---

## 5. Terminal Plugin Integration

### 5.1 Integration Type

- **Method:** Plugin API (internal `TransferableTerminalSessionFactory` of `intellij.terminal.frontend`)
- **Direction:** Bidirectional
- **Protocol:** IntelliJ Terminal API

### 5.2 Usage

| Feature | Purpose |
|---------|---------|
| **Process execution** | Run `claude` as a non-shell process with the Ghostty emulator (`ShellStartupOptions`) |
| **Terminal output** | Display session output in a view bound to the tab, detachable from it |
| **Process control** | Start/stop sessions; learn the pid (`processId`); type text (`sendText`) |
| **Terminal tabs** | Open in Terminal and Plannotator add tabs to the Terminal tool window |

### 5.3 Constraints

- **Required dependency:** Terminal plugin must be enabled
- **Process lifecycle:** `SessionTerminals` owns the `claude` processes, one per tab file, and may keep them running detached after their tab closes
- **No output capture:** The plugin does not read terminal output; it watches only the session state for `Terminated`

---

## 6. MCP Server Integration

### 6.1 Integration Type

- **Method:** MCP Protocol
- **Direction:** Bidirectional
- **Protocol:** Model Context Protocol
- **Status:** Optional

### 6.2 Tools Exposed

| Tool | Purpose |
|------|---------|
| `my_air_sessions_find` | Search sessions |
| `my_air_folders_get` | Read folder structure |
| `my_air_folders_apply` | Modify folder structure |
| `my_air_plan_get` | Read plans |
| `my_air_plan_apply` | Modify plans |
| `my_air_history_list` | View history |
| `my_air_history_restore` | Restore snapshot |
| `my_air_set_brief` | Set session brief |
| `my_air_session_start` | Create session |

### 6.3 Registration

```xml
<!-- src/main/resources/META-INF/my-air-mcp.xml -->
<idea-plugin>
  <extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="dev.myair.mcp.FoldersMcpToolset"/>
    <mcpToolset implementation="dev.myair.mcp.BriefMcpToolset"/>
  </extensions>
</idea-plugin>
```

Each tool is an `@McpTool` method: `FoldersMcpToolset` holds the first seven and `my_air_session_start`, `BriefMcpToolset` holds `my_air_set_brief`. Tools run their work on the EDT against `ClaudeSessionsService`.

### 6.4 Constraints

- **Optional dependency:** MCP Server plugin may not be installed
- **Tool availability:** Tools only available when MCP enabled
- **Error handling:** Errors returned to agent, no dialogs

---

## 7. Git Integration

### 7.1 Integration Type

- **Method:** CLI (Command Line), the `git` on PATH (`Git.run`)
- **Direction:** Bidirectional
- **Protocol:** Git commands
- **Status:** Optional; HEAD and branch names are also read straight from HEAD files

### 7.2 Operations

| Operation | Purpose |
|-----------|---------|
| `git worktree add` / `remove` / `prune` / `list --porcelain` | Create, replace and list worktrees |
| `git branch --show-current`, `for-each-ref`, `rev-parse` | Branch, repository root and ref lookups |
| `git status --porcelain --untracked-files=all` | Whether a worktree is clean before removing it |
| `git merge-base`, `git diff --raw --numstat`, `git show` | Changes against the base and file contents for diffs |
| `git config branch.<branch>.myAirBase` | Store and read a branch's base |

### 7.3 Worktree Management

```
Create worktree for a picked branch
    │
    ├─ A clean, unlocked linked worktree holding it, no running session in it
    │     → git worktree remove <path>
    │
    ├─ Branch can be checked out
    │     → git worktree add <path> <local>
    │       (remote pick without local namesake: git worktree add --track -b <local> <path> <remote>)
    │
    └─ Main checkout or an unremovable worktree holds it
          → git worktree add --no-track -b worktree-<name> <path> <base>
            git config branch.<branch>.myAirBase <base>
    │
    ├─ Copy gitignored files matching .worktreeinclude
    ├─ Success → Continue
    └─ Failure → GitFailure with git's stderr
```

### 7.4 Constraints

- **Git on PATH:** Needed for the Git features
- **Repository:** Not required; outside a repository the Git features are disabled or show none
- **Branch names:** Not validated by the plugin; `git` rejects bad names, which surfaces as `GitFailure`
- **Error handling:** Git failures reported clearly (dialog in the UI, error text to an MCP agent)

---

## 8. Windows API Integration

### 8.1 Integration Type

- **Method:** Native API (via JNA)
- **Direction:** Read/Write
- **Protocol:** Win32 API
- **Status:** Required

### 8.2 Used APIs

| API | Purpose |
|-----|---------|
| **Kernel32 `CreateFile` / `SetFileInformationByHandle`** | POSIX-semantics rename in `writeAtomically` |
| **user32 `EnumWindows`, `GetWindowThreadProcessId`, `IsWindowVisible`, `GetWindowText`** | Window detection |
| **user32 `ShowWindow`, `SetForegroundWindow`** | Bring a host window to the front |
| **user32 `AllowSetForegroundWindow`** | Let the target IDE take the foreground before a focus request |

Process start times (registry `procStart`, a FILETIME, and `ides/<pid>.json` `started`) are compared through Java's `ProcessHandle`, not Win32.

### 8.3 Usage

```
Process Validation (ProcessHandle)
    │
    ├─ Get process start time
    ├─ Compare with registry procStart
    └─ Validate PID

Window Management (JNA user32)
    │
    ├─ Find process window
    ├─ Bring window to foreground
    └─ Allow focus transfer
```

### 8.4 Constraints

- **Windows only:** Win32 API not available on other platforms
- **JNA dependency:** JNA as shipped with the IDE
- **Error handling:** A refused call throws (`check`)

---

## 9. Branch Diff Plugin Integration

### 9.1 Integration Type

- **Method:** Plugin API (`diffTargetProvider` extension point of `dev.kosmala.branch-diff-tree`, registered in `my-air-branch-diff.xml`)
- **Direction:** Bidirectional
- **Protocol:** IntelliJ Plugin API
- **Status:** Optional

### 9.2 Usage

| Feature | Purpose |
|---------|---------|
| **Diff target** (`target`) | Work tree of the active session's `workDir` (a tab no scan has seen: its `cwd`) against `Git.baseOf`, labelled with the session's title; a folder gone or outside a repository throws |
| **Change events** (`subscribe`) | `ActiveSession.subscribe` |
| **Base** (`setBase`) | A base chosen in Branch Diff is stored with `Git.setBase`, then `ActiveSession.baseChanged` makes Session Context re-read it |

```xml
<!-- src/main/resources/META-INF/my-air-branch-diff.xml -->
<idea-plugin>
  <extensions defaultExtensionNs="dev.kosmala.branch-diff-tree">
    <diffTargetProvider implementation="dev.myair.branchdiff.SessionDiffTargetProvider"/>
  </extensions>
</idea-plugin>
```

### 9.3 Constraints

- **Optional dependency:** Branch Diff may not be installed
- **Follows active session:** Updates when session changes
- **No impact:** Plugin works without Branch Diff
- **Build:** Gradle compiles against Branch Diff's built zip (`localPlugin`)

---

## 10. Taskbar Widget Integration

### 10.1 Integration Type

- **Method:** File-based
- **Direction:** Bidirectional
- **Protocol:** File system (JSON)
- **Status:** Optional

### 10.2 Data Exchange

| Direction | Data | Path |
|-----------|------|------|
| **Plugin → Widget** | Board data | `~/.my-air/taskbar/board.json` |
| **Plugin → Widget** | IDE state | `~/.my-air/ides/<pid>.json` |
| **Widget → Plugin** | Focus requests | `~/.my-air/requests/<pid>/` |
| **Widget → Plugin** | Seen marks | `~/.my-air/seen/<id>` |

### 10.3 Integration Flow

```
Plugin writes board.json
        │
        ▼
Widget reads board.json
        │
        ▼
Widget displays counts/flyout
        │
        ▼
User clicks session
        │
        ▼
Widget calls AllowSetForegroundWindow(IDE pid),
writes requests/<pid>/<uuid>.json (IDE with the tab, else any)
and seen/<id>
        │
        ▼
Plugin reads request
        │
        ▼
Plugin opens session in IDE
```

### 10.4 Constraints

- **Optional component:** Widget may not be installed
- **File-based:** No direct communication
- **Format strict:** Widget validates board format
- **Focus activation:** Widget activates before focus request

---

## 11. Integration Architecture Diagram

```
                ┌──────────────────────┐        ┌──────────────────┐
                │ Claude Code          │        │ Claude Desktop   │
                │ ~/.claude (files)    │        │ Claude-Data files│
                │ claude (CLI)         │        └────────┬─────────┘
                └───▲──────────┬───────┘                 │ read
     start claude   │          │ read                    │
     (via Terminal) │          ▼                         ▼
┌───────────────────┴────────────────────────────────────────────────┐
│                         My Air Plugin                               │
└──┬──────────┬───────────┬───────────┬───────────┬───────────┬──────┘
   │          │           │           │           │           │
   ▼          ▼           ▼           ▲           ▼           ▼▲
┌────────┐ ┌────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐
│IntelliJ│ │Terminal│ │  Git   │ │MCP Server│ │Windows   │ │ Branch    │
│Platform│ │Plugin  │ │ (CLI)  │ │ (calls   │ │API (JNA) │ │ Diff      │
│        │ │        │ │        │ │ toolsets)│ │          │ │ (target / │
└────────┘ └────────┘ └────────┘ └────▲─────┘ └──────────┘ │  setBase) │
                                      │                    └───────────┘
                                 External Agents
                     ~/.my-air files
   My Air Plugin ◄──────────────────────────► Taskbar Widget
   (board.json, ides/ →)            (← requests/, seen/)
```

---

## 12. Traceability

| Integration | FR Coverage |
|-------------|-------------|
| Claude Code | FR-SD-* |
| IntelliJ Platform | All |
| Terminal Plugin | FR-SM-001, FR-SM-003 |
| MCP Server | FR-MT-* |
| Git | FR-SC-002, FR-SC-007 |
| Windows API | FR-CI-005, FR-CI-008 |
| Branch Diff | FR-CD-005 |
| Taskbar Widget | FR-TW-*, FR-AN-* |
