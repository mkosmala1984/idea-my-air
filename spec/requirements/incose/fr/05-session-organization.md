# Functional Requirements: Session Organization

**Area Code:** SO  
**Related High-Level View Section:** 3.5  
**Priority Area:** Medium

---

## FR-SO-001: Custom Folder Structure

**Requirement:** The system shall allow creating custom folder structures for organizing sessions.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Folders created via UI or MCP tools
- Nested subfolders supported
- Drag and drop for organizing
- Folder names user-defined
- Structure stored in `~/.my-air/structure.json`

---

## FR-SO-002: Folder Directory Binding

**Requirement:** The system shall allow binding folders to project directories, so that sessions of those directories show in the bound folder without being filed.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Folder can be bound to one or more project directories
- Worktree sessions included in binding
- A session run in a bound directory or below it shows in the bound folder (the most specific binding wins) unless it is on a plan task its brief names, filed by the user, dropped into `sessions`, or in a folder its brief names; nothing is written to `structure.json` for it
- No manual filing needed for bound directories

---

## FR-SO-003: Plan Folders

**Requirement:** The system shall support plan folders with milestones, tasks, and session assignment.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Folder can be designated as plan folder
- Plan contains milestones and tasks
- Each task has a status (pending, in_progress, waiting, blocked, done, canceled) and an optional next step; each milestone is open or done, set by hand
- Markdown descriptions supported
- A task holds the sessions whose brief names its id (`T<n>`) in `plan_task`
- Sessions not assigned shown under "no task"

---

## FR-SO-004: Session Filing

**Requirement:** The system shall allow filing sessions into folders manually or automatically.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Manual filing by dragging a session onto a folder in the tree; agents file through MCP `assign` and the brief's `folder`
- Sessions of a bound directory show in the bound folder without filing (FR-SO-002)
- Session appears in correct folder immediately
- Each change to `structure.json`, filing included, first copies the previous version to the history (FR-SO-007)

---

## FR-SO-005: Recent Sessions List

**Requirement:** The system shall maintain a "recent" list of the 10 newest sessions.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Top 10 newest sessions shown
- Updated on each refresh
- Sessions appear in correct order

---

## FR-SO-006: Unfiled Sessions List

**Requirement:** The system shall maintain a "sessions" list of the sessions that show in no folder.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Sessions grouped by project
- Shows the sessions on no plan task, not filed by the user, in no existing folder named by their brief and in no bound folder, plus those the user dropped into `sessions`
- Updated on each refresh

---

## FR-SO-007: Structure History and Undo

**Requirement:** The system shall maintain history of structure changes, restorable through MCP tools.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Before each change that alters an existing `structure.json`, the previous version is saved as a snapshot named `structure-<yyyyMMdd-HHmmss-SSS>-<source>.json`; only the newest 20 snapshots are kept
- Snapshots stored in `~/.my-air/history/`
- History listed via MCP `my_air_history_list`
- Single snapshot can be restored (undo)
- Restored state applied atomically

---

## FR-SO-008: Tree State Persistence

**Requirement:** The system shall persist tree expansion state across restarts and rebuilds.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Expanded rows saved per mode (projects/folders)
- State restored after IDE restart
- Search expands everything, leaves state as before
- Application properties used for storage

---

## FR-SO-009: Worktree Narrowing

**Requirement:** The system shall allow narrowing the tree to sessions in the current worktree.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- "This Work Tree" toggle in toolbar
- Per-project setting
- Narrows tree (not list) to current worktree sessions
- Comparison by root directory
- Disabled outside repository
- Separate TreeState for narrowed view

---

## FR-SO-010: Folder Operations via MCP

**Requirement:** The system shall expose folder operations via MCP tools.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- `my_air_folders_get` reads folder structure
- `my_air_folders_apply` creates, renames, moves, deletes folders
- `my_air_folders_apply` binds/unbinds directories
- `my_air_folders_apply` makes folder a plan folder
- `my_air_folders_apply` files sessions
- All operations all-or-nothing (atomic)

---

## FR-SO-011: Unreadable Structure File

**Requirement:** The system shall refuse folder changes while `structure.json` cannot be read.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Folders mode shows the read error instead of the tree
- The tree's folder actions are disabled, and every folder MCP tool returns "Cannot read <file>: <error>"
- Sessions still list in Projects mode and the pinned list

---

## FR-SO-012: Filing Follows a Cleared Session

**Requirement:** The system shall carry a session's folder filing over to the new session when `/clear` or `/resume` switch a tab's process to another session.

**Priority:** Low  
**Verification Method:** Test  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- The new session id takes the old one's folder
- The change is written with source `auto`, leaving a history snapshot

---

## FR-SO-013: Folder Working Directory

**Requirement:** The system shall allow a folder to name a working directory where sessions started from it or below run.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.5  
**Acceptance Criteria:**
- Set and cleared from the folder's menu ("Set Working Directory...", "Clear Working Directory") and via MCP `set_workdir`
- A working directory holds no sessions, and any number of folders may share one
- A new session started from a folder row runs in the working directory, else the first bound directory, of the nearest folder at or above it having either, and is filed in the folder it was started from
