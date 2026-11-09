# Functional Requirements: MCP Tools

**Area Code:** MT  
**Related High-Level View Section:** 3.8  
**Priority Area:** Medium

---

## FR-MT-001: MCP Server Integration

**Requirement:** The system shall expose tools through the IDE's MCP server when enabled.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Tools registered via `my-air-mcp.xml`
- Available when MCP Server plugin enabled
- Plugin works without MCP Server (tools just unavailable)

---

## FR-MT-002: Session Search Tool

**Requirement:** The system shall provide `my_air_sessions_find` tool for searching sessions.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Search by date range
- Search by folder
- Search by filed/unfiled state
- Search by words, each of which must occur (ignoring case) in the title, the project path, the branch the session started on or the branch checked out where it works now
- Returns matching sessions with metadata

---

## FR-MT-003: Folder Structure Tools

**Requirement:** The system shall provide tools for reading and modifying folder structure.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- `my_air_folders_get` reads folder structure
- `my_air_folders_apply` performs folder operations:
  - Create, rename, move, delete folders
  - Bind and unbind directories
  - Make folder a plan folder
  - File sessions
- Operations are all-or-nothing (atomic)
- Changes leave history snapshots

---

## FR-MT-004: Plan Management Tools

**Requirement:** The system shall provide tools for reading and modifying plans.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- `my_air_plan_get` reads plans for project directory
- Shows sessions for each task
- `my_air_plan_apply` modifies one plan:
  - Add, update, move, delete milestones
  - Add, update, move, delete tasks
- Plan named by folder path
- Operations all-or-nothing

---

## FR-MT-005: History Management Tools

**Requirement:** The system shall provide tools for viewing and restoring structure history.

**Priority:** Low  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- `my_air_history_list` lists snapshots
- `my_air_history_restore` restores one snapshot by name
- Restore applies state atomically
- History preserved after restore

---

## FR-MT-006: Session Brief Tool

**Requirement:** The system shall provide `my_air_set_brief` tool for agents to describe their goals.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Agent sets goal and tasks
- Agent names plan task it works on
- Brief shown in tree and context pane
- Brief persists with session

---

## FR-MT-007: Session Start Tool

**Requirement:** The system shall provide `my_air_session_start` tool for agents to create sessions.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Accepts configuration by name/model/effort
- Accepts directory, added directories
- Accepts worktree base (must be named)
- Accepts branch
- Accepts plan task
- Accepts folder (files session)
- Tab opens in IDE project the call came through
- Errors returned to agent, not shown as dialogs

---

## FR-MT-008: Tool Error Handling

**Requirement:** MCP tools shall return clear error messages for invalid inputs or failures.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Invalid parameters rejected with clear message
- Git failures reported with details
- Input validation errors clear
- No dialogs shown to user
- Input errors and failed `git` commands are returned in the tool response as expected errors carrying their message; other failures are not converted

---

## FR-MT-009: Tool Argument and Result Formats

**Requirement:** MCP tools shall describe their arguments and results in their tool descriptions; list arguments are strings holding JSON, and results are JSON except for `my_air_set_brief`.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Request format documented
- Response format documented
- `changes`, `add_dirs` and `tasks` take a string holding a JSON array
- `my_air_set_brief` returns `ok`, or `ok, folder <id> at '<path>'` (with ` (created)` when it created the folder)

---

## FR-MT-010: Tool Availability

**Requirement:** The system shall load its MCP tools only through an optional dependency on the IDE's MCP Server plugin.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- Tools registered only when MCP Server enabled
- Plugin functions normally without MCP
- `my_air_set_brief` is enabled only while the Briefs setting is on

---

## FR-MT-011: Brief Folder and Title

**Requirement:** The system shall let `my_air_set_brief` file and title the calling session when filing is on.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- `folder` and `title` are refused with "filing sessions is turned off; leave out folder and title" while filing is off
- A missing `folder` is created only one level below an existing folder, never at the top level or in a plan folder; the change is recorded with source `brief`
- `plan_task` must name an existing task, else the call is refused
- `plan_task`, `folder` and `title` keep their value when a later call leaves them out

---

## FR-MT-012: Folder Working Directory via MCP

**Requirement:** The system shall let agents set a folder's working directory and start sessions from a folder.

**Priority:** Low  
**Verification Method:** Test  
**Traceability:** HL-3.8  
**Acceptance Criteria:**
- `my_air_folders_apply` op `set_workdir` sets a folder's working directory to an existing absolute directory; `dir` null clears it
- `my_air_session_start` with `folder` and no `dir` runs in the working directory or bound directory of that folder or the nearest folder above it having either, and fails when none has
- `my_air_history_restore` refuses a name that `my_air_history_list` does not list
