# Functional Requirements: Cross-IDE Coordination

**Area Code:** CI  
**Related High-Level View Section:** 3.4  
**Priority Area:** High

---

## FR-CI-001: Session Ownership Detection

**Requirement:** The system shall detect which IDE instance has a session tab open and avoid duplicate tabs.

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- System reads `~/.my-air/ides/<pid>.json` for all running IDEs
- Session tab ownership tracked per IDE instance
- Opening a session checks for existing tabs first
- Opening a session from the plugin (tool window, list, actions, taskbar request) never opens a second tab when an IDE running the plugin already shows the session; a second process is started only when the user chooses "Open Anyway"

---

## FR-CI-002: Session Opening Priority Chain

**Requirement:** The system shall follow a specific priority chain when opening sessions: local tab → other IDE tab → warning → new tab.

**Priority:** High  
**Verification Method:** Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
1. Check for tab in this IDE → open if found
2. Check for tab in another IDE → bring that IDE to foreground, open tab
3. If the registry shows the session running other than as a background job → show a "Session Already Running" warning offering "Switch to Window" (only when an ancestor of the `claude` process other than this IDE owns a visible window), "Open Anyway" and "Cancel"; when opened by "Next Claude Session Needing Me", that window is brought to the front without the warning
4. Otherwise → create a new tab running `claude --resume <id>`; when the session's directory no longer exists, show the error "Session directory no longer exists" and open no tab

---

## FR-CI-003: Cross-IDE Focus Request

**Requirement:** The system shall allow focusing on a session open in any IDE instance.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- "Focus Claude Session…" action in Tools menu
- User can select session from list
- IDE with session brought to foreground
- Tab with session selected and focused
- Works across multiple IDE instances

---

## FR-CI-004: IDE State Publishing

**Requirement:** The system shall publish its open tabs to shared state for other IDEs to discover.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- Tab state written to `~/.my-air/ides/<pid>.json`
- Updated on tab open, close, project startup, re-targeting
- File withdrawn on IDE dispose
- Format includes PID, start time, and list of open tabs

---

## FR-CI-005: Focus Request Handling

**Requirement:** The system shall handle focus requests from other IDEs and the taskbar widget.

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- Requests read from `~/.my-air/requests/<pid>/`
- Request processed: bring IDE to foreground, open/select tab
- The sender (another IDE or the taskbar widget) calls `AllowSetForegroundWindow` for the target IDE's pid before writing the request
- Each request file (`*.json` holding `sessionId`) is deleted as soon as it is read, before the request is handled; requests already waiting when the IDE starts are handled too

---

## FR-CI-006: Session Re-targeting

**Requirement:** The system shall point each session tab of this IDE at the session its `claude` process shows now, after `/clear`, `/resume` or `/bg` switch the process to another session.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- Re-targeting triggered after each state change
- Tabs followed by process ID (PID), or by background job id for a tab showing a job
- The tab takes the new session's id, title, status and unread mark; its folder filing is carried over to the new session id
- A tab whose process is no longer registered keeps its session and shows no status; a process that exits by itself leaves its tab showing the ended terminal until the user closes it

---

## FR-CI-007: Background Job Handling

**Requirement:** The system shall properly handle sessions that are background jobs running in other programs.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- A session run by a background job is never treated as running in another program; a tab showing it in another IDE running the plugin is still focused first
- New tab can attach to background job
- Warning not shown for background jobs
- The tab follows the job's registry entry for its session and status

---

## FR-CI-008: Window Detection

**Requirement:** The system shall detect the window of a program running a session.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- Find nearest ancestor window of `claude` process
- Only visible windows with a non-empty title are considered; a window of the current IDE is not offered
- Windows API used via JNA
- Handles processes without windows gracefully

---

## FR-CI-009: State Consistency Across IDEs

**Requirement:** The system shall maintain consistent session state across all IDE instances.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- All IDEs see the same session list
- Pinning/unpinning synchronized
- Folder structure synchronized
- Settled state synchronized
- Each IDE refreshes after file events in `~/.my-air` and `~/.claude`, debounced to 500 ms of quiet and at most 700 ms after the first event, and in any case every 60 s

---

## FR-CI-010: IDE Startup Synchronization

**Requirement:** The system shall synchronize state when a new IDE instance starts.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- New IDE reads shared state on startup
- The new IDE publishes its restored session tabs on project startup; other IDEs' tab files are read when a session is opened, on the Focus action and on a focus request
- A session tab restored on startup does not start its `claude` while its directory is gone or the registry shows its session running other than as a background job; it shows why, with "Switch to Window" (when a window is found) and "Open Anyway"
- Session list populated correctly

---

## FR-CI-011: Detached Sessions as Tabs

**Requirement:** The system shall treat a session whose `claude` process runs on after its tab closed (detached) as a tab of this IDE.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- Detached sessions are listed in `~/.my-air/ides/<pid>.json` with the open tabs
- Opening a detached session from any IDE reopens it in the IDE running its process, showing the same process
- Re-targeting applies to detached sessions as to open tabs

---

## FR-CI-012: Stale IDE Detection

**Requirement:** The system shall ignore and remove tab files of IDEs that are no longer running.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- An IDE counts as running only when its pid exists and that process's start time (epoch ms) equals the `started` field of its file
- A file of an IDE not running is deleted by the IDE that reads it
- A file removed between listing and reading is skipped

---

## FR-CI-013: Focus Request Without Tab

**Requirement:** The system shall open a requested session that no tab in the receiving IDE shows.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.4  
**Acceptance Criteria:**
- The session opens in the open project whose base directory contains the session's cwd, else in the first open project, whose window is focused
- Opening follows the priority chain of FR-CI-002
- A request for a session without a transcript yet is logged as a warning and not acted on
