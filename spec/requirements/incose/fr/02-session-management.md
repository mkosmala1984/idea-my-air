# Functional Requirements: Session Management

**Area Code:** SM  
**Related High-Level View Section:** 3.2  
**Priority Area:** High

---

## FR-SM-001: Session Tab Opening

**Requirement:** The system shall open selected sessions in editor tabs that run `claude --resume <sessionId>`, or `claude attach <jobId>` for a session a background job runs.

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Double-click or Enter on a session row in the tree or the pinned list opens it; with View Options › Open Sessions with Single Click on, a plain left click does too
- The tab runs `claude --resume <sessionId>` with the start flags of FR-SC-012; a session run by a background job gets `claude attach <jobId>` instead
- Tab follows the process as it progresses
- Tab title reflects session title
- Tab icon reflects session status

---

## FR-SM-002: Tab Status Indication

**Requirement:** The system shall display session status through tab icons with visual indicators.

**Priority:** High  
**Verification Method:** Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Tab icon shows: no process, working, needs input, unread
- Unread sessions marked with blue asterisk
- A branch badge is shown when the session works in another git work tree (compared by root) than its home: the window's work tree for a session of the window's project, its project's work tree otherwise; no badge outside a repository
- Different project's icon shown when session belongs to another project

---

## FR-SM-003: Tab Persistence Across Restart

**Requirement:** The system shall preserve session tabs, their order, splits, and pins across IDE restarts.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Tab URLs, order, splits, and pins saved by platform
- Restored tabs start `claude` when first selected (lazy start)
- A restored tab starts with `--resume`, or with `--session-id` when the session has no transcript yet
- A restored tab whose directory is gone, or whose session the registry shows running other than as a background job, does not start; it says so and offers Switch to Window (when the host window is known) and Open Anyway

---

## FR-SM-004: Session Closing and Settling

**Requirement:** The system shall allow closing session tabs and marking sessions as settled (done).

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Closing a tab ends or detaches its `claude` process as FR-SM-011 specifies
- "Close and Settle" (tab context menu) settles the session, ends its process and closes the tab; for a background job still in the registry it also runs `claude stop <jobId>` (5 s timeout; a timeout or non-zero exit is logged as an error)
- Settled sessions hidden from list and tree until "Show All Sessions" enabled
- Settling unpins the session
- Pinning unsettles the session

---

## FR-SM-005: Session Pinning

**Requirement:** The system shall allow pinning important sessions for quick access above the main list.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Pinned sessions appear in pinned list above main tree
- Running sessions automatically shown in pinned list
- Pinning/unpinning persisted in `~/.my-air/pins.json`
- "Remove from List" unpins the selected sessions; a running one is hidden until it has a newer message and is no longer working, then shows again

---

## FR-SM-006: Session Search

**Requirement:** The system shall provide search that filters the session tree by title, cwd and branch.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Search field available (Ctrl+F)
- Search matches each word in title, cwd, startBranch, or checked-out branch
- A non-empty search expands the whole tree; closing the search restores the previous expansion
- The search filters the tree only; the pinned list is not filtered

---

## FR-SM-007: Session Visibility Configuration

**Requirement:** The system shall allow configuring session visibility based on age and status.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- View Options: hide from the tree (not the pinned list) sessions whose last message is 3, 7, 14, or 30 days old or older; choosing the selected option again shows all
- Settled sessions hidden by default
- "Show All Sessions" toggle in the toolbar's View Options menu
- Density options: Compact or Loose
- Font size adjustable

---

## FR-SM-008: Session Context Menu Actions

**Requirement:** The system shall provide context menu actions for session management.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Right-click on session shows context menu
- Actions include: Pin, Settle, New Session with Same Settings, Save Settings as Configuration..., Show Changes vs Base; the tree menu adds Open in IDEA and Open in Terminal, the pinned-list menu adds Remove from List
- Actions work from both tree and list views

---

## FR-SM-009: Cycle Through Sessions Needing Attention

**Requirement:** The system shall allow cycling through sessions that need user attention (Alt+Shift+K).

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Keyboard shortcut Alt+Shift+K cycles through sessions
- Only sessions needing input or with unread replies included
- Order: sessions needing input, longest waiting first, then unread sessions, oldest reply first; after the last it wraps to the first
- The next session's tab is focused (or its window brought forward); when no other session waits, a popup says "Nothing needs you" or "Nothing else needs you" with the number of working sessions

---

## FR-SM-010: Session Brief Display

**Requirement:** The system shall display session briefs (goals and tasks) set by agents.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- Briefs shown in tree and context pane
- The tree shows the brief's goal and all of its tasks
- A brief naming a plan task places the session under that task in Folders mode
- Brief update triggers UI refresh

---

## FR-SM-011: Detached Session Processes

**Requirement:** The system shall keep a session's `claude` process running after its tab closes, while "Keep sessions running after their tab closes" is on (default), and end it once it is idle.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- With the setting on, closing a tab leaves its process running detached; with it off, closing the tab ends the process
- A process that never started or already ended is ended when its tab closes, whatever the setting
- A tab showing a background job ends its own process when closed (unless the platform reopens it); the job keeps running
- A detached process is ended on the refresh after it has been detached at least 60 s, is idle and has no background task it started still open; needing input keeps it
- Opening a detached session again shows the same process
- Closing the tab of a working session shows a notification, once per IDE run
- Closing a project, exiting or restarting the IDE first asks for confirmation when a process it would end is working, needs input or runs a background task it started

---

## FR-SM-012: Open Routing

**Requirement:** Opening a session shall go to where it already runs before starting a second process.

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.2  
**Acceptance Criteria:**
- A tab of the session in this IDE (detached processes included) is focused
- Otherwise a tab in another IDE running this plugin is focused through a focus request
- Otherwise, when the registry shows the session running other than as a background job, a "Session Already Running" dialog names the program and offers Switch to Window (when its window is known), Open Anyway and Cancel
- Otherwise a new tab opens; a session directory that no longer exists shows an error instead
