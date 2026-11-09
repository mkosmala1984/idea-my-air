# Functional Requirements: Taskbar Widget

**Area Code:** TW  
**Related High-Level View Section:** 3.9  
**Priority Area:** Low

---

## FR-TW-001: Taskbar Host Integration

**Requirement:** The taskbar widget shall host a window inside the Windows 11 taskbar.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Uses `Deskband11Lib.Wpf` for taskbar hosting
- Widget appears in Windows 11 taskbar
- Colours follow the taskbar's light or dark mode (`SystemUsesLightTheme`), read when each window is created

---

## FR-TW-002: Session Count Display

**Requirement:** The taskbar widget shall display counts of sessions by status.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Count of sessions needing input
- Count of sessions with unread replies
- Count of working sessions
- Counts displayed as numbers/badges
- Updated in real-time (on board change)

---

## FR-TW-003: Hover Flyout

**Requirement:** The taskbar widget shall show a flyout on hover listing sessions.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Hover on widget shows flyout
- Sessions grouped: Needs Input, Unread, Working, Recent
- Each row shows title and details
- Click on row opens session in IDE
- Flyout can be pinned

---

## FR-TW-004: Bubble Notifications

**Requirement:** The taskbar widget shall show bubble notifications for new arrivals.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Bubble above widget for new sessions needing input/unread
- Bubble names the session
- Hides after 8 seconds unless hovered
- Drops session that leaves group
- Gives way to flyout
- Click opens session

---

## FR-TW-005: Board Data Reading

**Requirement:** The taskbar widget shall read session data from `~/.my-air/taskbar/board.json`.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Reads board file on change
- Parses sessions and recent lists
- Validates format strictly
- A missing board file reads as an empty board; malformed JSON, an unknown field, a missing required field or an unknown status throws, and the app shows the exception in a message box and exits
- No direct communication with IDE

---

## FR-TW-006: IDE State Reading

**Requirement:** The taskbar widget shall read IDE state from `~/.my-air/ides/*.json`.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Reads all IDE state files
- Identifies IDE with session tab
- Determines target IDE for focus request
- Handles multiple IDEs correctly

---

## FR-TW-007: Focus Request Sending

**Requirement:** The taskbar widget shall send focus requests to the correct IDE.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Writes request to `~/.my-air/requests/<pid>/`
- Target IDE: one with session tab, else any IDE
- Request includes session ID
- IDE processes request and opens session
- Calls `AllowSetForegroundWindow` for the target IDE before writing the request; fails with an error when no IDE runs or Windows refuses

---

## FR-TW-008: Focus Activation

**Requirement:** The taskbar widget shall hold the foreground when it requests IDE focus.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Non-activated window cannot make IDE foreground
- The bubble activates itself before sending the request; a pinned flyout is already activated; a row click in a hovered flyout sends the request while the click has given the app the foreground

---

## FR-TW-009: Arrival Detection

**Requirement:** The taskbar widget shall detect new session arrivals between board reads.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Compare consecutive board reads
- Detect sessions that started needing input
- Detect sessions that got unread replies
- Ignore first read
- Ignore without live IDE
- Trigger bubble notification, unless the flyout is visible

---

## FR-TW-010: Build and Test Script

**Requirement:** The taskbar widget shall be built, tested and restarted by one script.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- Build/test via `pwsh taskbar/build.ps1`: stops a running `MyAirTaskbar`, runs `dotnet test taskbar/tests`, builds Release and starts the new build

---

## FR-TW-011: IDE Liveness and Periodic Reload

**Requirement:** The taskbar widget shall count only IDEs that still run and re-read its files periodically.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- An IDE file counts only when its pid exists and that process's start time equals `started` (epoch ms)
- The board and IDE files are re-read on file events and every 5 seconds
- Without a live IDE the widget is drawn at 40% opacity, the flyout header says "No IDE with My Air is running" and the flyout lists no sessions

---

## FR-TW-012: Seen on Click

**Requirement:** The taskbar widget shall mark a session seen when the user opens it from the widget.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- After sending a focus request, the widget writes `~/.my-air/seen/<id>` with the current time in epoch ms, through a temp file whose name contains a dot

---

## FR-TW-013: Single Instance and Exit

**Requirement:** The taskbar widget shall run as a single instance and be closable from its menu.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.9  
**Acceptance Criteria:**
- A second copy started while one runs exits at once (named mutex `Local\MyAir.Taskbar`)
- Right-clicking the widget shows a menu with "Exit", which quits the app
- After Explorer restarts or the taskbar is recreated, the widget attaches a new window
