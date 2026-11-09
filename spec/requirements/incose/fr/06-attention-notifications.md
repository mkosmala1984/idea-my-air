# Functional Requirements: Attention and Notifications

**Area Code:** AN  
**Related High-Level View Section:** 3.6  
**Priority Area:** High

---

## FR-AN-001: Needs Input Indication

**Requirement:** The system shall visually indicate sessions that need user input.

**Priority:** High  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Sessions with `waiting` status shown with an amber disc icon bearing `!` in tree and list
- Tab icon shows "needs input" state
- Visual distinction from other statuses
- Indication updated in real-time

---

## FR-AN-002: Unread Reply Indication

**Requirement:** The system shall mark sessions with unread replies.

**Priority:** High  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Unread sessions marked with blue asterisk and a bold title in tree and list
- Tab icon shows unread state
- Unread determined by: a running, idle session whose last message is newer than `~/.my-air/seen/<id>`
- Seen timestamp updated when tab selected in active window

---

## FR-AN-003: Seen State Management

**Requirement:** The system shall track when sessions were last seen by the user.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Seen timestamp stored in `~/.my-air/seen/<id>` (epoch ms)
- One file per session
- Updated on: tab selection, IDE activation, refresh
- A running session met for the first time is marked at its last message
- Reply finished in visible tab never unread

---

## FR-AN-004: Attention Cycle Navigation

**Requirement:** The system shall allow cycling through sessions needing attention.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Keyboard shortcut: Alt+Shift+K
- Cycles, wrapping round, through running sessions needing input (longest waiting first), then running sessions with unread replies (oldest reply first)
- Opens/selects each session in sequence
- When no other session needs the user, a popup says "Nothing needs you" or "Nothing else needs you" followed by "· <n> working"

---

## FR-AN-005: Taskbar Count Display

**Requirement:** The taskbar widget shall display counts of sessions by status.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Count of sessions needing input
- Count of sessions with unread replies
- Count of working sessions
- Counts updated in real-time
- Displayed as three numbers in the widget window hosted in the taskbar; the widget is drawn at 40% opacity while no IDE with My Air runs

---

## FR-AN-006: Taskbar Flyout

**Requirement:** The taskbar widget shall show a hover flyout listing sessions in status groups.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Hover on taskbar widget shows flyout
- Sessions grouped: Needs Input, Unread, Working, Recent
- Each row shows session title and details
- Click on row opens session in IDE
- Flyout can be pinned

---

## FR-AN-007: Taskbar Bubble Notifications

**Requirement:** The taskbar widget shall show bubble notifications for new session arrivals.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Bubble shown when session needs input or gets unread reply
- Bubble names the session
- Bubble hides after 8 seconds unless hovered
- Bubble drops session that leaves group
- Bubble gives way to flyout
- Click on bubble opens session

---

## FR-AN-008: Board Data Publication

**Requirement:** The system shall publish session status data for the taskbar widget.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Board written to `~/.my-air/taskbar/board.json`
- Updated on each refresh when changed
- Includes: sessions with status, unread flag, project, waitingFor, lastMessage, activity
- Includes recent sessions list
- Format strictly validated by taskbar widget

---

## FR-AN-009: Arrival Detection

**Requirement:** The taskbar widget shall detect new session arrivals between board reads.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Compare consecutive board reads
- Detect sessions that started needing input
- Detect sessions that got unread replies
- Ignore first read
- Ignore without live IDE

---

## FR-AN-010: Attention Priority Ordering

**Requirement:** The system shall order sessions by urgency in attention views.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- Needs Input highest priority
- Unread second priority
- Working third priority
- Recent fourth priority
- Within a flyout group, newest last message first, sessions without a transcript last

---

## FR-AN-011: Board Contents

**Requirement:** The system shall list on the board every session with a running process and a short list of recent ones.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.6  
**Acceptance Criteria:**
- `sessions` holds exactly the sessions with a running process; one without a transcript yet is titled "New session", without project and lastMessage
- `recent` holds at most 5 sessions, newest first, leaving out settled sessions and those in the Needs Input, Unread or Working groups
- Status values are written as `NEEDS_INPUT`, `IDLE` and `WORKING`; null fields are left out
