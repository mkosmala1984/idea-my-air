# My Air – High-Level System View (INCOSE)

**Version:** 1.1  
**Date:** 2026-11-08  
**Author:** Kosmala Development  
**Status:** Draft

---

## 1. Introduction

### 1.1 Purpose

This document provides a high-level view of the **My Air** system, describing its purpose, scope, key stakeholders, and main capabilities. It follows the INCOSE systems engineering approach to provide a structured foundation for detailed requirements development.

### 1.2 Scope

**My Air** is an IntelliJ IDEA plugin that integrates Claude Code sessions into the IDE environment. It provides visibility, management, and cross-IDE coordination of Claude Code sessions. The system reads all session state from files written by Claude Code and never queries a `claude` process for it; it starts `claude` in its own session tabs and types into their prompts. It provides a unified interface for developers to interact with their sessions.

### 1.3 Intended Audience

- Software developers using IntelliJ IDEA with Claude Code
- System engineers and architects
- Quality assurance teams
- Project managers and stakeholders

### 1.4 Definitions and Acronyms

| Term | Definition |
|------|------------|
| **Claude Code** | Anthropic's AI coding assistant CLI tool |
| **IDE** | Integrated Development Environment |
| **Session** | A single Claude Code conversation instance |
| **Worktree** | A Git worktree – a separate working directory for a branch |
| **MCP** | Model Context Protocol |
| **INCOSE** | International Council on Systems Engineering |
| **SRS** | System Requirements Specification |

---

## 2. Overall Description

### 2.1 System Context

The My Air system operates within the following context:

- **Platform:** Windows (Win32 calls through JNA); the Taskbar Widget requires the Windows 11 taskbar
- **Host Application:** IntelliJ IDEA 2026.3+ (build 263+)
- **Dependency:** Claude Code CLI installed and on PATH
- **Integration:** Terminal plugin, VCS platform modules, MCP Server plugin (optional), Branch Diff plugin (optional)
- **Companion Application:** My Air Taskbar Widget (WPF, .NET 10)

### 2.2 System Perspective

My Air is a **plugin system** consisting of:
1. **IntelliJ Plugin** (primary component) – Kotlin-based IDE extension
2. **Taskbar Widget** (secondary component) – Windows 11 taskbar application
3. **MCP Tools** (service component) – IDE MCP server tools for external agents

The plugin acts as an **observer and coordinator** of Claude Code sessions, reading their state from the file system and providing a unified interface for session management.

### 2.3 User Classes and Characteristics

| User Class | Description | Primary Needs |
|------------|-------------|---------------|
| **Developer** | Primary user of the IDE and Claude Code | Session visibility, quick access, status monitoring |
| **Team Lead** | Oversees multiple sessions across projects | Session organization, progress tracking |
| **External Agent** | AI agents using MCP tools | Session discovery, management, and coordination |

### 2.4 Operating Environment

- **Operating System:** Windows; the Taskbar Widget runs only in the Windows 11 taskbar
- **IDE Version:** IntelliJ IDEA 2026.3+ (build 263+)
- **Runtime:** Java 25 (JBR from IDE)
- **Taskbar Widget:** .NET 10 runtime
- **Network:** The plugin makes no network calls of its own; branch-name suggestion runs `claude -p`, which uses the Claude API

### 2.5 Design and Implementation Constraints

1. **File-based state:** All session state is read from files written by Claude Code; the plugin never queries a `claude` process for it. It does start `claude` (sessions, `attach`, `stop`, `-p`) and types into the prompts of its session tabs
2. **Windows-only in practice:** Win32 calls go through JNA
3. **Shared state:** Each IDE reads session data from `~/.claude/` itself; tabs, focus requests, pins, folders, presets, seen times and the taskbar board are shared across IDE instances via `~/.my-air/`
4. **Atomic writes:** Files in `~/.my-air/` that are rewritten in place are written to a temporary file and moved into place; new files (history snapshots, pasted images) are written directly
5. **No external dependencies:** All plugin features except branch-name suggestion work without network access from the plugin
6. **Format changes fail loudly:** An unknown registry status or kind, or a missing required field, throws; the refresh is logged as an IDE error and discarded, and the UI keeps the last good state

### 2.6 Assumptions and Dependencies

- Claude Code is installed and accessible via PATH
- IntelliJ IDEA has the Terminal plugin enabled
- Git is available for worktree operations
- File system permissions allow reading `~/.claude/` and writing to `~/.my-air/`

---

## 3. System Capabilities

### 3.1 Session Discovery and Monitoring

The system discovers and monitors all Claude Code sessions running anywhere (this IDE, another IDE, terminal, Claude Desktop) by reading session state from `~/.claude/`.

**Key Features:**
- Scan transcripts from `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`
- Monitor live registry in `~/.claude/sessions/<pid>.json`
- Track session status: working, needs input, unread, idle
- Display sessions in a unified tree/list view
- Group sessions by project or custom folders

### 3.2 Session Management

The system provides comprehensive session management capabilities:

**Key Features:**
- Open sessions in editor tabs with `claude --resume <id>`
- Follow session progress in real-time
- Close sessions and mark as settled
- Pin important sessions for quick access
- Search sessions by title, cwd, or branch (filters the tree)
- Configure session visibility (hide old sessions)

### 3.3 Session Creation

The system provides multiple ways to create new Claude Code sessions:

**Key Features:**
- Create plain sessions in the project directory
- Create sessions in new Git worktrees
- Create sessions with pre-filled prompts (Air-style composer)
- Configure sessions with presets (model, effort, directories)
- Attach sessions to plan tasks

### 3.4 Cross-IDE Coordination

The system coordinates sessions across multiple IDE instances:

**Key Features:**
- Detect sessions open in other IDE instances
- Focus on sessions in the correct IDE window
- Share session state across all IDE instances
- Handle session ownership (avoid duplicate tabs)

### 3.5 Session Organization

The system provides flexible session organization:

**Key Features:**
- Custom folder structure with drag-and-drop
- Nested subfolders
- Bind folders to project directories (worktrees included)
- Plan folders with milestones and tasks
- Automatic session filing based on project
- History of structure changes, listed and restored through MCP tools

### 3.6 Attention and Notifications

The system alerts users to sessions that need attention:

**Key Features:**
- Mark sessions needing input with an amber icon
- Mark unread replies on tabs and in tree
- Cycle through sessions needing attention (Alt+Shift+K)
- Taskbar widget with counts and hover flyout
- Bubble notifications for new arrivals

### 3.7 Context and Information Display

The system provides rich context about sessions:

**Key Features:**
- Session context tool window with cards
- Display model, effort, project, worktree, branch
- Show files written, artifacts, background tasks
- Display MCP servers and skills
- Branch diff integration (optional plugin)

### 3.8 MCP Tools

The system exposes tools via the IDE's MCP server for external agents:

**Key Tools:**
- `my_air_sessions_find` – Find sessions by various criteria
- `my_air_folders_get/apply` – Read and modify folder structure
- `my_air_plan_get/apply` – Read and modify plans
- `my_air_history_list/restore` – View and restore history
- `my_air_set_brief` – Set session goals and tasks
- `my_air_session_start` – Start new sessions programmatically

### 3.9 Taskbar Widget

The companion taskbar application provides at-a-glance session status:

**Key Features:**
- Display counts of sessions by status
- Hover flyout listing sessions in groups
- Bubble notifications for new arrivals
- Click to open session in IDE
- Focus management across IDE windows

---

## 4. System Interfaces

### 4.1 User Interfaces

| Interface | Description |
|-----------|-------------|
| **Claude Sessions Tool Window** | Main panel showing session tree/list |
| **Session Tabs** | Editor tabs for active sessions |
| **Session Context Tool Window** | Detailed information about selected session |
| **New Session Composer** | Air-style popup for creating sessions with prompts |
| **Settings Panel** | Configuration for My Air plugin |
| **Taskbar Widget** | Windows 11 taskbar application |

### 4.2 External Interfaces

| Interface | Description |
|-----------|-------------|
| **Claude Code Files** | Read-only access to `~/.claude/` |
| **File System** | Read/write access to `~/.my-air/` |
| **Git** | Worktree operations, branch management |
| **Windows API** | Process management, window focus (via JNA) |
| **MCP Server** | Expose tools to external agents |
| **IDE Platform** | IntelliJ IDEA extension APIs |

### 4.3 Internal Interfaces

| Interface | Description |
|-----------|-------------|
| **File-based IPC** | Communication between IDE instances via `~/.my-air/` |
| **Event System** | Internal notifications for session state changes |
| **MCP Toolset** | Internal tools for session management |

---

## 5. High-Level Requirements Overview

### 5.1 Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-001 | System shall discover all running Claude Code sessions | High |
| FR-002 | System shall display session status in real-time | High |
| FR-003 | System shall allow opening sessions in editor tabs | High |
| FR-004 | System shall support creating new sessions with configuration | Medium |
| FR-005 | System shall coordinate sessions across IDE instances | High |
| FR-006 | System shall provide session organization via folders | Medium |
| FR-007 | System shall alert users to sessions needing attention | High |
| FR-008 | System shall expose MCP tools for external agents | Medium |
| FR-009 | System shall provide taskbar widget for Windows 11 | Low |

### 5.2 Non-Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-001 | System shall end only the `claude` processes it started itself, and background jobs on Close and Settle | High |
| NFR-002 | System shall maintain data consistency across IDE instances | High |
| NFR-003 | System shall fail loudly on Claude Code file format changes | Medium |
| NFR-004 | System shall have minimal performance impact on IDE | High |
| NFR-005 | System shall preserve session state across IDE restarts | Medium |

---

## 6. System Architecture Overview

### 6.1 Component Structure

```
┌─────────────────────────────────────────────────────────┐
│                   My Air System                          │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │  IntelliJ Plugin │  │ Taskbar Widget  │              │
│  │  (Kotlin)        │  │ (WPF, .NET 10)  │              │
│  ├─────────────────┤  ├─────────────────┤              │
│  │ Session Scanner │  │ Board Reader    │              │
│  │ Session Manager │  │ Focus Handler   │              │
│  │ Tab Controller  │  │ UI Display      │              │
│  │ Folder Model    │  │                 │              │
│  │ MCP Tools       │  │                 │              │
│  │ Settings        │  │                 │              │
│  └─────────────────┘  └─────────────────┘              │
├─────────────────────────────────────────────────────────┤
│           Shared State (~/.my-air/)                      │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ Claude Code     │  │ IntelliJ IDEA   │              │
│  │ (CLI)           │  │ (IDE Platform)  │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘
```

### 6.2 Data Flow

```
Claude Code Processes
        │
        ▼
┌─────────────┐
│ ~/.claude/  │ ← Read by Plugin
│ (files)     │
└─────────────┘
        │
        ▼
┌─────────────┐     ┌─────────────┐
│ Plugin      │◀───▶│ ~/.my-air/  │ ← Taskbar reads the board, writes seen times and focus requests
│ (Scanner)   │     │ (shared)    │
└─────────────┘     └─────────────┘
        │                      │
        ▼                      ▼
┌─────────────┐          ┌─────────────┐
│ IDE UI      │          │ Taskbar     │
│ (Tabs, etc) │          │ Widget      │
└─────────────┘          └─────────────┘
```

### 6.3 Package Structure

The plugin code is organized under `dev.myair` by feature:

| Package | Responsibility |
|---------|----------------|
| `claude` | Reading Claude's files (scanner, registry, watcher) |
| `store` | Files in `~/.my-air` and atomic writes |
| `folders` | Folder model, structure store, history |
| `mcp` | MCP toolset and queries |
| `service` | Main service, taskbar board, tab re-targeting |
| `editor` | Session tabs and opener |
| `tabs` | Finding tabs and windows, cross-IDE sync |
| `actions` | Menu actions, worktrees, Git |
| `toolwindow` | Claude Sessions panel |
| `prompt` | New-session composer |
| `branchdiff` | Branch Diff plugin integration (optional) |

`MyAirSettings` lives in the root package `dev.myair`.

---

## 7. Key Constraints and Risks

### 7.1 Constraints

1. **File-based state:** Session state comes only from files; the plugin never queries a `claude` process
2. **Windows-centric:** Relies on Win32 API for process management
3. **Shared state:** All IDE instances must maintain consistent state
4. **Format fragility:** Depends on Claude Code file formats

### 7.2 Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Claude Code file format changes | High | Strict validation, fail loudly on unknown formats |
| Race conditions in file access | Medium | Atomic writes (temporary file and rename); one file per writer where possible (`ides/<pid>.json`, `seen/<id>`); no file locking |
| Cross-IDE state inconsistency | Medium | Event-driven synchronization |
| Performance impact on IDE | Medium | Efficient scanning, caching |

---

## 8. Future Work

This high-level view provides the foundation for more detailed requirements documentation. Future work includes:

1. Detailed functional requirements for each capability
2. Interface specifications for each component
3. Test strategy and verification plan
4. Detailed architecture documentation
5. Deployment and installation guide

---

## 9. References

- INCOSE Systems Engineering Handbook
- Claude Code Documentation: https://docs.claude.com/en/docs/claude-code
- IntelliJ Platform SDK Documentation
- Project README.md
- Project CLAUDE.md
