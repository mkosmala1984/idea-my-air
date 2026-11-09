# User Interfaces – My Air System

**Related High-Level View Section:** 4.1  
**Related Functional Requirements:** FR-SM-*, FR-SO-*, FR-AN-*

---

## 1. Overview

The My Air system provides multiple user interfaces for interacting with Claude Code sessions. This document describes all user-facing interfaces.

---

## 2. Claude Sessions Tool Window

### 2.1 Description

The main panel (`SessionsPanel`, tool window id `Claude Sessions`): a list of pinned and running sessions above a session tree, split by a splitter. Anchored on the right side of the IDE by default (`plugin.xml`, `anchor="right"`).

### 2.2 Components

| Component | Description |
|-----------|-------------|
| **Toolbar (left)** | New Claude Session with Prompt..., New Claude Session, New Claude Session in Worktree..., session configuration buttons and dropdown (`PresetsGroup`), New Folder |
| **Toolbar (right)** | This Work Tree, View Options, Search |
| **Search Field** | Filters by each word in title, cwd, start branch or branch checked out in the work directory; Escape or the close button hides it |
| **Pinned List** | Sessions pinned by hand plus every session with a live process |
| **Tree View** | Session hierarchy with status icons |
| **Context Menu** | Right-click actions for sessions, folders and plans |

### 2.3 View Modes

| Mode | Description |
|------|-------------|
| **Projects** | Group sessions by project (default; Folders toggle off) |
| **Folders** | Use custom folder structure (View Options › Folders) |

### 2.4 Session Row

```
┌─────────────────────────────────────────────────────────────┐
│ [status icon] [title] [task count] [project]          [age] │
└─────────────────────────────────────────────────────────────┘
  └ child rows: work directory, added directories, branch,
    brief (goal, tasks), Files, outputs
```

**Elements:**
- **Icon:** Session status (`sessionIcon`): thin grey asterisk without a process, animated orange asterisk while working, amber disc with `!` when it needs input, blue asterisk when unread, else orange asterisk
- **Title:** Session name; bold when unread, grey without a process, grey italic when settled
- **Task count:** Count of the brief's tasks (`briefCount`)
- **Project:** The cwd folder name; always in the pinned list, in the tree only under `recent`; otherwise in the tooltip
- **Age:** Painted on the right by `SessionTrailer`, with hover buttons Pin and Settle
- **Status:** Status text, settled, Desktop and project are shown in the tooltip (`tooltipLines`)
- **Brief:** Goal and tasks shown as a child group under the session row
- **Unread:** Blue asterisk icon and bold title for unread replies

### 2.5 Toolbar Actions

| Action | Shortcut | Description |
|--------|----------|-------------|
| New Claude Session | - | Create new session in the project directory |
| New Claude Session with Prompt... | - | Create with composer popup |
| New Claude Session in Worktree... | - | Create in worktree |
| Refresh | - | Force refresh (item in View Options) |
| Search | Ctrl+F (platform Find) | Search sessions |
| View Options | - | Configure display |
| This Work Tree | - | Narrow the tree to the window's work tree (per project) |
| Next Claude Session Needing Me | Alt+Shift+K | Tools menu: go to the next session needing input or with an unread reply |

### 2.6 View Options

| Option | Values | Default |
|--------|--------|---------|
| Folders | On/Off | Off |
| Show All Sessions (shows settled sessions) | On/Off | Off |
| Open Sessions with Single Click | On/Off | Off |
| Hide Sessions Older Than (tree only) | 3, 7, 14, 30 days; choosing the selected one again clears it | None |
| Density | Compact (26 px rows, 16 px icons), Loose (34 px rows, 20 px icons) | Compact |
| Font Size | Increase / Decrease / Reset Font Size, steps added to the platform font, range -4..12 | 0 |
| Refresh | Action | - |

### 2.7 Context Menu

Tree rows:

| Action | Description |
|--------|-------------|
| New Claude Session / New Claude Session in Worktree... | Start in the selected project or folder directory, filed in the selected folder |
| Open in IDEA / Open in Terminal | Open the row's directory |
| Pin/Unpin | Toggle pin state |
| Settle | Mark as done |
| New Session with Same Settings | Create similar session |
| Save Settings as Configuration... | Save as preset |
| Show Changes vs Base | Diff of the session's work tree against its base |
| New Folder, Rename..., Delete... | Folder editing (Folders mode) |
| Plan actions | New Plan..., Make Folder a Plan, Remove Plan, Bind/Unbind Directory..., Set/Clear Working Directory, Add Milestone..., Add Task..., Mark Milestone Done/Reopen Milestone, task status, Edit Next Step..., Edit Description... |

Pinned list rows: Pin/Unpin, Settle, Remove from List (Delete key), New Session with Same Settings, Save Settings as Configuration..., Show Changes vs Base.

A session is opened by double-click or Enter, or by a plain single click when Open Sessions with Single Click is on. Sessions are moved between folders by drag and drop.

---

## 3. Session Tabs

### 3.1 Description

Editor tabs for active Claude Code sessions.

### 3.2 Tab Structure

```
┌─────────────────────────────────────────────────────────────┐
│ [status icon + branch badge] [project icon] [title] [close] │
└─────────────────────────────────────────────────────────────┘
```

**Elements:**
- **Icon:** Status indicator (no process, working, needs input, unread)
- **Branch Badge:** Badge layered in the corner of the status icon when the session works in another work tree than its home (`TabMark`)
- **Project Icon:** For a session of another project than the window's, that project's icon after the status icon, and its colour mixed into the tab background (`ClaudeSessionTabColorProvider`)
- **Title:** Session name
- **Close:** Close tab; with Keep sessions running after their tab closes on (default), `claude` runs on detached

### 3.3 Tab Behavior

| Behavior | Description |
|----------|-------------|
| **Lazy Start** | A tab restored at IDE start starts its process when first selected |
| **Process Follow** | The tab follows its process by pid when `/clear` or `/resume` switch it to another session (`retarget`) |
| **Status Update** | Icon updates on every refresh |
| **Unread Mark** | Blue asterisk for unread replies |
| **Close Action** | With `keepDetached` (default on) the process runs on detached and ends once idle for 60 s with no own background task; with it off, or for a view of a background job, closing ends the process. Close and Settle settles and ends it |

### 3.4 Tab Persistence

- **Saved:** URLs (`myair-session://`), order, splits, pins
- **Restored:** On IDE restart, as new tabs, never into Recent Files
- **Lazy:** Start on first selection
- **No Duplicates:** Opening a session goes to an existing tab in this IDE, then a tab in another IDE, then warns when the registry shows it running elsewhere (Switch to Window / Open Anyway / Cancel)

---

## 4. Session Context Tool Window

### 4.1 Description

Detailed information about the session of the active session tab (`SessionContextPanel`, tool window `Session Context`, right, secondary).

### 4.2 Cards

| Card | Content |
|------|---------|
| **Title** | Session title; a click selects its tab |
| **Model/Effort** | Model and effort chips with the age |
| **Continue** | Button that starts a new session continuing this one (its model, effort, added directories, in its work tree; first message names the old session id and transcript) |
| **Environment** | Project, folder relative to it, branch → base (base changeable from a popup), start folder and branch, worktree, Desktop, added directories with branches |
| **Changes vs base** | Files changed since the merge base, Show diff, Tree, Plannotator |
| **Files outside the repo** | Files written outside the work tree ("Files" until git was read) |
| **Outputs** | `md`/`html` files written, newest first |
| **Artifacts** | Published artifacts, opened in the browser |
| **Plan** | Plan, milestone and task of the session |
| **Brief** | Goal and tasks |
| **Background** | Background tasks: status, duration, tokens, tool uses, output file |
| **MCP servers** | Connected servers |
| **Skills** | Skills offered to the session, grouped by plugin, pinnable, with a button that types `/<name> ` into the session |

### 4.3 Behavior

- **Follows active tab:** Updates when the active session tab, its session, head or live entry changes (`ActiveSession`)
- **Links:** Paths clickable to open files
- **Real-time:** Updates as session progresses

---

## 5. New Session Composer

### 5.1 Description

Air-style composer (`PromptPanel`) for creating sessions with a first message, shown as a popup (`NewSessionWithPromptAction`) and in the empty editor area (`PromptEmptyState`). An unsent message is kept as a draft with its choices.

### 5.2 Fields

| Field | Type | Description |
|-------|------|-------------|
| **First Message** | Textarea | Initial prompt |
| **Configuration** | Chip | Default, then `presets.json` |
| **Model** | Chip | Model selection (default `opus`) |
| **Effort** | Chip | Effort level (default `medium`) |
| **Workspace** | Chip | Current checkout, a new worktree, or a new worktree on a new branch (name typed or suggested) |
| **Branch** | Chip / searchable list | Checked-out branch, or for a worktree the base branch |

### 5.3 Features

- **Image Paste:** Ctrl+V of an image saves a PNG in `~/.my-air/images/` and inserts an `@path` mention
- **File Paste:** Copied files become `@path` mentions
- **Popup Mode:** Small window
- **Fill Mode:** Fills empty editor area

### 5.4 Workflow

```
Open Composer
    │
    ▼
Fill fields
    │
    ├─ Paste images/files
    │
    ▼
Create Session
    │
    ▼
Session opens in tab
```

---

## 6. Settings Panel

### 6.1 Description

Configuration for My Air plugin (`MyAirConfigurable`, state `MyAirSettings` in `myAir.xml`).

### 6.2 Location

Settings › Tools › My Air

### 6.3 Sections

| Section | Settings |
|---------|----------|
| **General** (ungrouped rows) | Session briefs (default on); Agents file and title their sessions (default on, only with briefs); Session tab width (0..1000 columns, default 0 = fill); Keep sessions running after their tab closes (default on); Jira tools for branch names (default `mcp__atlassian__getJiraIssue mcp__atlassian__getAccessibleAtlassianResources`) |
| **Session Configurations** | Session configurations (`~/.my-air/presets.json`) |
| **Worktrees** | Worktrees directory (default `.claude/worktrees`, relative to the main checkout) |

### 6.4 Presets Management

| Action | Description |
|--------|-------------|
| Add | Create new preset |
| Edit | Modify existing preset (also double-click) |
| Delete | Remove preset |
| Reorder | Change order |

### 6.5 Preset Fields

| Field | Type | Description |
|-------|------|-------------|
| Name | Text | Preset name, unique |
| Model | Editable combo | `--model` value: claude's own setting, fable, opus, sonnet, or a full name |
| Effort | Combo | `--effort` value or claude's own setting |
| Project | Directory | Project directory; empty = IDE project |
| Worktree | Radio + combo | None / new worktree, base picked at start / new worktree off a fixed branch |
| Other directories | Text area | `--add-dir` paths, one per line |
| Own button on the toolbar | Checkbox | Show on toolbar |

---

## 7. Taskbar Widget UI

### 7.1 Description

Windows 11 taskbar application showing session status.

### 7.2 Components

| Component | Description |
|-----------|-------------|
| **Main Widget** | Count badges in taskbar |
| **Flyout** | Hover list of sessions; a click pins it |
| **Bubble** | Notification for new arrivals |

### 7.3 Count Badges

```
┌─────────────┐
│ [2] [1] [3] │  ← Needs Input, Unread, Working
└─────────────┘
```

### 7.4 Flyout

```
┌─────────────────────────────────────────┐
│ Claude sessions                         │
├─────────────────────────────────────────┤
│ Needs Input                             │
│ • Session A                             │
├─────────────────────────────────────────┤
│ Unread                                  │
│ • Session C                             │
├─────────────────────────────────────────┤
│ Working                                 │
│ • Session D                             │
├─────────────────────────────────────────┤
│ Recent                                  │
│ • Session E - 5 min ago                 │
└─────────────────────────────────────────┘
```

### 7.5 Bubble

```
┌─────────────────────┐
│ Session A needs     │
│ your input          │
└─────────────────────┘
```

**Behavior:**
- Shows on new arrivals
- Hides after 8 seconds unless hovered
- Click opens session
- Gives way to flyout

---

## 8. Traceability

| Interface | FR Coverage |
|-----------|-------------|
| Tool Window | FR-SM-*, FR-SO-* |
| Session Tabs | FR-SM-001, FR-SM-002, FR-SM-003 |
| Context Window | FR-CD-* |
| Composer | FR-SC-* |
| Settings | FR-SC-004 |
| Taskbar Widget | FR-TW-*, FR-AN-* |
