# Functional Requirements: Session Creation

**Area Code:** SC  
**Related High-Level View Section:** 3.3  
**Priority Area:** Medium

---

## FR-SC-001: New Session from Toolbar

**Requirement:** The system shall allow creating new Claude sessions from the main toolbar, Claude Sessions toolbar, or Tools menu.

**Priority:** High  
**Verification Method:** Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- "New Claude Session" action available in toolbar and menu
- Creates plain `claude` session in project directory
- Session opens in new editor tab
- Session immediately visible in session list

---

## FR-SC-002: New Session in Worktree

**Requirement:** The system shall allow creating new sessions in Git worktrees with automatic worktree setup.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- "New Claude Session in Worktree..." action available
- User can pick base branch for worktree
- After the base, the user picks "Check out <base>" or "New branch off <base>...". Check out: the base's local branch (for a remote pick its local namesake, created tracking it when missing) is checked out without a new branch in `<worktrees dir>/<name>`, `<name>` being the base without non-alphanumerics, today's date and the first 4 characters of the session id (e.g. `main-2026-10-28-3fb8`); when the main checkout holds that branch, or a worktree holding it cannot be removed (FR-SC-014), a new branch `worktree-<name>` off the base is created instead. New branch: the named branch is created off the base in `<worktrees dir>/<branch>`
- Untracked, gitignored files of the main checkout matching `.worktreeinclude` are copied into the worktree
- When a new branch is created, the base is stored in the repository config as `branch.<branch>.myAirBase`, which `git branch -m` carries
- Session runs in worktree directory

---

## FR-SC-003: Session Composer (Air-style)

**Requirement:** The system shall provide an Air-style composer for creating sessions with pre-filled prompts.

**Priority:** Medium  
**Verification Method:** Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Composer popup available from "New Claude Session with Prompt…"
- Composer fills empty editor area when no file is open
- Fields: first message, model, effort, configuration, workspace, branch
- Pasted images and files become `@path` mentions
- Session created with all specified parameters

---

## FR-SC-004: Session Configurations (Presets)

**Requirement:** The system shall support named session configurations (presets) for quick session creation.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Presets stored in `~/.my-air/presets.json`
- Preset fields: name, `--model`, `--effort`, project directory, worktree base, `--add-dir` directories, toolbar button
- Presets editable in Settings › Tools › My Air
- "New Session with Same Settings" creates a session with the selected session's model, effort, project and added directories, in a new worktree when its cwd is not its project; the worktree's base is picked at start
- "Save Settings as Configuration…" saves current session as new preset

---

## FR-SC-005: Preset Toolbar Buttons

**Requirement:** The system shall provide toolbar buttons for presets configured with own button.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Presets with "own toolbar button" shown in PresetsGroup
- Button icon shows preset initials
- Dropdown shows all presets
- Broken preset file named in dropdown
- Model/effort apply only to new sessions (not `--resume`)

---

## FR-SC-006: Session Creation via MCP

**Requirement:** The system shall allow external agents to create sessions via MCP tool `my_air_session_start`.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Tool accepts: configuration name, model, effort, directory, added directories, worktree base, branch, plan task, folder
- Worktree created if specified
- Session filed in specified folder
- Tab opens in IDE project the call came through
- Errors (input failures, git failures) returned to agent, not shown as dialogs

---

## FR-SC-007: Worktree Directory Configuration

**Requirement:** The system shall allow configuring the worktree directory location.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Setting in Settings › Tools › My Air
- Default: `.claude/worktrees` (relative to main checkout)
- Custom directory accepted
- Worktrees created in configured location

---

## FR-SC-008: Session Creation Errors

**Requirement:** The system shall report session creation failures with the cause: missing directories, invalid configuration values and `git` failures.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Branch names are not checked by the system; a name `git` refuses fails worktree creation with `git`'s error output
- A directory outside a Git repository fails worktree creation with `git`'s error output
- Worktree creation failures reported with details (the `git` command, exit code and stderr)
- Effort must be one of `low`, `medium`, `high`, `xhigh`, `max`; the model is not validated
- A missing start directory or added directory is reported, naming the directories, and no session starts

---

## FR-SC-009: Session Filing on Creation

**Requirement:** The system shall allow filing newly created sessions into custom folders.

**Priority:** Low  
**Verification Method:** Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- Session can be filed into specified folder during creation
- A folder is given through the MCP tool's `folder` argument, or by starting the session from a folder row's menu in the tree
- Session appears in correct folder immediately

---

## FR-SC-011: Plan Context for Sessions in a Plan

**Requirement:** The system shall give a session that belongs to a plan the plan's context in its system prompt.

**Priority:** Low  
**Verification Method:** Test  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- A session belongs to a plan through the task its brief names, else the plan row it was started from (tree menu or MCP `plan_task`), else the plan folder it shows in
- Such a session starts with `--append-system-prompt-file` naming a file with the plan's name, path and description and its task's and milestone's
- The file lives in `~/.my-air/prompts/` until the process ends
- A background job's session gets no plan file

---

## FR-SC-012: Session Start Arguments

**Requirement:** Every session process the system starts, other than `claude attach`, shall get the My Air system prompt and environment.

**Priority:** Medium  
**Verification Method:** Test  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- `--append-system-prompt` with the My Air system prompt is passed on every start, `--resume` included
- With briefs on, `--settings ~/.my-air/hooks/brief-reminder.json` is passed
- Environment: `CLAUDE_CODE_NO_FLICKER=1` and `CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL=true`
- `claude` is looked up on PATH; when it is missing, the start fails with "claude executable not found on PATH"

---

## FR-SC-013: New Session Tab

**Requirement:** A session started by the system shall get a new session id and open in a pinned editor tab.

**Priority:** Medium  
**Verification Method:** Test/Demonstration  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- The process starts with `--session-id <new UUID>`
- The new tab is pinned in the current editor window
- A folder or plan folder the session is started for is assigned before the tab opens
- A session started from a folder row runs in the working directory, else the first bound directory, of that folder or the nearest folder above it having either

---

## FR-SC-014: Freeing a Branch for a Worktree

**Requirement:** Before checking out a picked branch in a new worktree, the system shall free that branch from a linked worktree that holds it when this is safe.

**Priority:** Low  
**Verification Method:** Test  
**Traceability:** HL-3.3  
**Acceptance Criteria:**
- `git worktree prune` runs first
- A linked worktree holding the branch is removed (`git worktree remove`) when it has no modified, staged or untracked file, is not locked and no running session started or works in it
- Otherwise, and when the main checkout holds the branch, a new branch `worktree-<name>` off the pick is created (FR-SC-002)
- The pick is put first among the repository's recent bases, which keep 5
