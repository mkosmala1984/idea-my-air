# Functional Requirements: Session Discovery and Monitoring

**Area Code:** SD  
**Related High-Level View Section:** 3.1  
**Priority Area:** High

---

## FR-SD-001: Session File Scanning

**Requirement:** The system shall scan Claude Code transcript files `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl` (one directory level below `projects/`; `subagents/` transcripts deeper down are not listed) to discover sessions.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- The session's cwd is taken from the first transcript line carrying a `cwd` field, not decoded from the directory name; a titled transcript without any `cwd` throws
- Every `.jsonl` file one level below `projects/` is read; a transcript with neither a title nor a human prompt yields no session
- System extracts session ID from filename
- A malformed JSON line, or a titled transcript without a message, throws; the refresh that met it is logged as an IDE error and discarded, and the session list keeps its previous state
- A transcript shorter than the offset already read is read again from the start

---

## FR-SD-002: Live Registry Monitoring

**Requirement:** The system shall monitor the live session registry in `~/.claude/sessions/<pid>.json` to detect running sessions and their current status.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- System reads registry files for all running Claude processes
- System requires the fields `kind`, `pid`, `procStart`, `status`, `entrypoint`, `cwd`, `startedAt`, `sessionId`, and `jobId` when `kind` is `bg`; `waitingFor` is optional
- System validates `status` values (`busy`, `shell`, `waiting`, `idle`) and `kind` values (`interactive`, `bg`, `daemon`, `daemon-worker`)
- A missing required field or an unknown `status`/`kind` throws (fail loudly); the following files are skipped without error: a file without `statusUpdatedAt` (a `claude` still starting), a `spare` worker, `daemon`/`daemon-worker` kinds, a file deleted between listing and reading

---

## FR-SD-003: Session Status Tracking

**Requirement:** The system shall track and display session status, updating as sessions change state.

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- A refresh starts once file events have been quiet for 500 ms, and no later than 700 ms after the first event of a burst
- The UI updates after that refresh completes (scan and registry read, then the EDT update); the 700 ms cap applies to the debounce only
- System correctly maps registry status to UI indicators:
  - `busy` → Working
  - `shell` → Working
  - `waiting` → Needs Input
  - `idle` → Idle
- A running idle session is "unread" when its last message is newer than the time stored in `~/.my-air/seen/<sessionId>`

---

## FR-SD-004: Cross-Source Session Discovery

**Requirement:** The system shall discover sessions started from any source: this IDE, another IDE, terminal, or Claude Desktop.

**Priority:** High  
**Verification Method:** Demonstration  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Sessions started in terminal appear in IDE session list
- Sessions started in another IDE appear in current IDE session list
- Sessions from Claude Desktop appear in session list
- A session is listed whatever its origin, except: transcripts without title or human prompt, subagent transcripts, a session continued under a new id by `/bg` (its old id is hidden while the new one is scanned), and running processes whose registry file has no `statusUpdatedAt` yet

---

## FR-SD-005: Session Grouping by Project

**Requirement:** In Projects mode the system shall group discovered sessions by the directory they started in (cwd), with the group holding the newest session first.

**Priority:** High  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Sessions are grouped by exact cwd; a session started in a subfolder or a linked worktree forms its own group
- Groups ordered by newest activity first
- Group header shows session count and most urgent status
- In Folders mode a session whose cwd is in a linked worktree of a main checkout is placed in the folder bound to that main checkout

---

## FR-SD-006: New Session Detection

**Requirement:** The system shall detect and display sessions that have started but not yet written their first transcript.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Sessions with registry entry but no transcript appear as "New session"
- New sessions dated by `startedAt` from registry
- New sessions shown in tree and pinned list
- Session moves to normal state once first transcript is written

---

## FR-SD-007: Background Job Detection

**Requirement:** The system shall detect and properly classify background jobs (`claude --bg` or `/bg`).

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Background job workers identified by `kind: "bg"`
- `jobId` required for background sessions
- Parked processes (old session with `parkedJobId`) correctly handled
- Background sessions not confused with interactive sessions

---

## FR-SD-008: Session Refresh Cadence

**Requirement:** The system shall refresh session state on file system events plus a periodic interval.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Refresh triggered on changes in `sessions/` and `projects/<dir>/` directories
- Debouncing: 500ms quiet period, 700ms cap
- Periodic refresh every 60 seconds
- Refresh does not cause UI flicker or lag

---

## FR-SD-009: Session State Persistence

**Requirement:** The system shall maintain session state across IDE restarts without losing discovered sessions.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Session list re-populated after IDE restart
- Pinned sessions preserved
- Folder structure preserved
- No duplicate sessions after restart

---

## FR-SD-010: Error Handling for Format Changes

**Requirement:** The system shall fail loudly when encountering unknown registry statuses or missing required fields, indicating a Claude Code format change.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Unknown `status` and `kind` values throw exceptions
- Missing required fields throw exceptions; a missing `statusUpdatedAt` skips the file instead (see FR-SD-002)
- The error message names the registry file and the missing field or unexpected value, except for a missing `sessionId` or `pid`, which throws without naming the field
- System does not silently skip or misinterpret unknown formats
- A thrown error is logged as an IDE error and the refresh is discarded; the UI keeps the last successfully read state

---

## FR-SD-011: Live Process Verification

**Requirement:** The system shall count a registry entry as running only while its pid belongs to the process that wrote it.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- An entry counts only if the start time of process `pid` equals `procStart`, read as a Windows FILETIME (100 ns ticks since 1601-01-01 UTC)
- A registry file left by a dead process, or whose pid was reused, is ignored
- When a background job's worker and another process report the same session, the worker's entry is used; interactive processes parked into that job by `/bg` are recorded with it

---

## FR-SD-012: Transcript Interpretation

**Requirement:** The system shall derive each session's title, date and identity from its transcript content.

**Priority:** High  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Title: the last `custom-title` line, else the brief's title, else the last `ai-title` line, else the first line of the first human prompt (at most 80 characters)
- Date: the `timestamp` of the last `user`/`assistant` line or `system` `local_command` line outside sidechains and `isMeta`, not the file's modification time
- When one session id lies in several project directories (a `--resume` from another folder), the copy with the latest message counts, else the longest
- A session whose transcript has a `continued-in` line naming a scanned session is hidden

---

## FR-SD-013: Incremental Scanning

**Requirement:** The system shall read only the transcript lines appended since the previous scan, also across IDE restarts.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.1  
**Acceptance Criteria:**
- Only complete lines after the stored byte offset are read; a line still being written waits for the next scan
- The scan state is saved to `~/.my-air/scan-cache-<hash of the plugin jar path>.json` after the first scan, at most once a minute, and on IDE exit
- The saved state is discarded, and every transcript read again, when the hash of the plugin's `dev/myair/claude/` classes differs from the one it was saved with
