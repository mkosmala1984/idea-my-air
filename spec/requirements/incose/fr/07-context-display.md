# Functional Requirements: Context and Information Display

**Area Code:** CD  
**Related High-Level View Section:** 3.7  
**Priority Area:** Medium

---

## FR-CD-001: Session Context Tool Window

**Requirement:** The system shall provide a Session Context tool window with cards describing the selected session.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Tool window shows cards for selected session tab
- Cards updated when session changes
- Cards include: model/effort, continue button, project info, worktree info, branch info

---

## FR-CD-002: Model and Effort Display

**Requirement:** The system shall display the model and effort settings for the selected session.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Model name shown in short form (e.g., `claude-opus-5-5` as "Opus 5.5", a date suffix dropped; an id of another form as it is)
- Effort level shown (e.g., low, medium, high)
- Updated when session changes
- Read from session transcript

---

## FR-CD-003: Continue Session Button

**Requirement:** The system shall provide a button in the context window that starts a new session continuing the selected one.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Button visible in context window
- Clicking starts a new session in a new pinned tab, with the session's model, effort and added directories, in the folder it works in, whose first message names the session's id and transcript path
- Button shown whenever the session's folder exists, whatever the session's status

---

## FR-CD-004: Project Information Display

**Requirement:** The system shall display project information for the selected session.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Project name/path shown
- Worktree path shown (if applicable)
- Branch name shown
- In a repository, the base branch shown after the branch: the base stored for the branch, else the default branch marked "(default)"
- Updated when session changes

---

## FR-CD-005: Branch Diff Display

**Requirement:** The system shall display branch diff information with multiple views.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Per-file diff view
- Combined diff view
- Tree view
- Plannotator review, offered only when `plannotator.exe` is on PATH, run in a Terminal tab at the work tree
- Changes since base branch
- Base branch selectable from a popup of up to 10 branches whose tips HEAD contains; not on a detached HEAD

---

## FR-CD-006: Added Directories Display

**Requirement:** The system shall display directories added to the session via `--add-dir`.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- List of added directories shown
- Paths displayed clearly
- Updated when session changes

---

## FR-CD-007: Output Files Display

**Requirement:** The system shall display files written by the session outside the worktree.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Files written outside worktree listed
- An Outputs section lists every `md`/`html` file the session wrote, newest first
- Artifacts shown separately
- Paths clickable (open file)

---

## FR-CD-008: Background Tasks Display

**Requirement:** The system shall display background tasks with their status and usage.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Background tasks listed
- Status shown: running (with start time), completed, failed (with exit code for a command), killed, stopped, expired, or "ended with its process"
- Tokens and tool uses shown for agents and workflows, agents done for workflows, event count for monitors, duration for ended tasks
- Updated in real-time

---

## FR-CD-009: MCP Servers and Skills Display

**Requirement:** The system shall display the MCP servers connected to the session and the skills available in it.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- MCP servers listed
- Skills listed
- Skills grouped by plugin
- Skills pinnable
- Skills typeable into session prompt
- Updated when session changes

---

## FR-CD-010: Active Session Tracking

**Requirement:** The system shall track the currently active session.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- Context window follows active tab
- Branch Diff follows active session (if plugin installed)
- Updates on tab selection

---

## FR-CD-011: Plan Card

**Requirement:** The system shall show a Plan card in the context window for a session whose brief names an existing plan task.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- The card shows the plan folder's path, the task's milestone, the task's id, title, status and next step
- The task's description is rendered from Markdown
- The other sessions whose brief names the same task are listed with their status icon; a click opens one
- Artifacts the session published are listed in their own card and open in the browser

---

## FR-CD-012: Open Folder Links

**Requirement:** The system shall offer links that open the session's folder in IDEA and in a terminal.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.7  
**Acceptance Criteria:**
- "IDEA" and "Terminal" links shown on the project row while the session's folder exists
- IDEA focuses the open project of this IDE at that folder, or opens it
- Terminal opens a tab at that folder in the Terminal tool window
