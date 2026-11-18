# Prompt panel: start as a new plan — Implementation Plan

## TL;DR

- Goal: the `Plan` toggle in `PromptPanel` starts the new session as the manager of a new plan, opened in a plan tab.
- Approach: pure parts first (where the plan folder goes, the toggle in the panel's choices), then the start path (`startPreset` gets a plan name, `startInWorktree` an opener, `PlanFiles` takes first-start arguments), then the UI.
- No decisions left for the user; the calls are in the spec's TL;DR.
- Risk: `PlanFiles.adopt`'s signature changes for its three callers (Turn into Plan, MCP `my_air_plan_set_manager`, the new start).

| Task | Status | Commit |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a `Plan` chip in the new-session panel that creates a plan managed by the session being started.

**Architecture:** `startPreset` keeps doing the directory checks and the worktree; only its last step (open a session tab) is replaced by `startAsPlan` (create the plan folder, store the manager, open the plan tab). The manager's first process gets the panel's flags through `PlanFiles.firstArgs`.

**Tech Stack:** Kotlin, IntelliJ Platform 263, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-18-prompt-panel-plan-design.md`

## Global Constraints

- English in code, comments and strings.
- No fallbacks: a folder that `createFolder` silently did not create fails a `check`.
- `./gradlew test buildPlugin` passes at the end.

## Review Focus

- Plan on with workspace New worktree and the base not read yet: the base popup must still lead to the plan, not a session tab (owned by Task 2: the opener goes through `startInWorktree`).
- Plan on with a project whose bound folder is a plan folder: the new plan goes beside it, never inside (Task 1 test).
- A draft typed with Plan on reopens with Plan on (Task 1 test on `restore`).
- Turn into Plan of a session without a process still gets the manager prompt as its first message (Task 2: `listOf("--", message)`).
- An empty message with Plan on starts the manager with no `--` (Task 2: `claudeArgs(null)`).

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 3 | |
| R2 | 3 | |
| R3 | 3 | |
| R4 | 1, 2 | |
| R5 | 2 | |
| R6 | 2 | |
| R7 | 2 | |
| R8 | 2 | |
| R9 | 2 | |
| R10 | 2 | |
| R11 | 1 | |
| R12 | 2 | |
| R13 | 2 | |

---

### Task 1: Where the plan goes, and the toggle in the panel's choices

**Files:**
- Modify: `src/main/kotlin/dev/myair/folders/SessionFolders.kt` (after `planFoldersOf`)
- Modify: `src/main/kotlin/dev/myair/prompt/PromptChoices.kt`
- Test: `src/test/kotlin/dev/myair/folders/SessionFoldersTest.kt`, `src/test/kotlin/dev/myair/prompt/PromptChoicesTest.kt`

**Interfaces:**
- Produces: `SessionFolders.planParentFor(dir: Path): String?`; `Choice.plan: Boolean = false`; `LastChoice.plan: Boolean? = null`.

- [ ] **Step 1: Failing tests**
  - `SessionFoldersTest` › `a new plan goes into the folder bound to its project, beside a plan bound there, else at the top level`: bound folder `app` (dirs `/app`) gives `"app"` for `/app` and `/app/.claude/worktrees/x`; with a plan folder `p` bound to `/q` under `rtm`, `/q` gives `"rtm"`; a plan folder bound at the top level gives `null`; an unbound directory gives `null`.
  - `PromptChoicesTest` › `the plan toggle is restored from the last start and a draft`: `restore(presets, LastChoice("Review", null, null, null, plan = true)).plan` is true; `plan = null` gives false; `Choice(review, "opus", "low", false, plan = true).toLast().plan` is true.
- [ ] **Step 2:** `./gradlew test --tests dev.myair.folders.SessionFoldersTest --tests dev.myair.prompt.PromptChoicesTest` fails to compile.
- [ ] **Step 3:** Implement: `planParentFor` = bound folder (`projectFolder`), replaced by the parent of `planFolderAt(bound)` when there is one; `plan` threaded through `Choice`, `LastChoice`, `toLast`, `restore` (`last.plan == true`).
- [ ] **Step 4:** Same command passes.
- [ ] **Step 5:** Commit with `Plan-Task: 1/3` and `Plan:` trailers.

### Task 2: Starting a session as a new plan's manager

**Files:**
- Create: `src/main/kotlin/dev/myair/planview/NewPlan.kt`
- Modify: `src/main/kotlin/dev/myair/planview/PlanFiles.kt` (`firstMessages` → `firstArgs`, `adopt`)
- Modify: `src/main/kotlin/dev/myair/toolwindow/TurnIntoPlan.kt`, `src/main/kotlin/dev/myair/mcp/FoldersMcpToolset.kt` (callers of `adopt`)
- Modify: `src/main/kotlin/dev/myair/actions/PresetActions.kt` (`startPreset`), `src/main/kotlin/dev/myair/actions/NewClaudeSessionInWorktreeAction.kt` (`startInWorktree`)

**Interfaces:**
- Consumes: `SessionFolders.planParentFor` (Task 1).
- Produces: `PlanFiles.adopt(folderId: String, manager: PlanManager, running: ClaudeSessionVirtualFile?, firstArgs: List<String>): String?` (`firstArgs` used only when `running` is null, appended after `managerStart`'s arguments); `startAsPlan(project: Project, name: String, sessionProject: Path, cwd: Path, id: String, args: List<String>)` (EDT); `startPreset(project, preset, where, prompt = null, branch = null, planName: String? = null)`; `startInWorktree(..., open: (cwd: Path, id: String, args: List<String>) -> Unit = <startNew as today>)`.

- [ ] **Step 1:** `PlanFiles`: `firstArgs: ConcurrentHashMap<String, List<String>>`; `startManager` appends `firstArgs.remove(folder.id).orEmpty()`; `adopt` takes `firstArgs`. Turn into Plan and the MCP tool pass `listOf("--", message)`.
- [ ] **Step 2:** `startAsPlan`: create the plan folder (`Plan()`) under `planParentFor(sessionProject)` with the session assigned (`ChangeSource.UI`), `check` it exists, `adopt(folderId, PlanManager(id, cwd, sessionProject), null, args)`, `PlanOpener.open(project, folderId)`.
- [ ] **Step 3:** `startInWorktree` calls `open(path, id, claudeArgs)` where it called `startNew`; `startPreset` picks `startAsPlan` as the opener when `planName` is given, `startNew` otherwise, for both the checkout and the worktree path.
- [ ] **Step 4:** `./gradlew test` passes (no behaviour test: IDE services; `PlanFilesTest` and `SessionSystemPromptTest` stay green).
- [ ] **Step 5:** Commit with `Plan-Task: 2/3`.

### Task 3: The `Plan` chip and the name row; docs; sandbox check

**Files:**
- Modify: `src/main/kotlin/dev/myair/prompt/Composer.kt` (`Chip.selected`), `src/main/kotlin/dev/myair/prompt/PromptPanel.kt`, `CLAUDE.md` (the `PromptPanel` paragraph)

- [ ] **Step 1:** `Chip.selected: Boolean` paints the pressed background while on. `planChip` (icon `AllIcons.Nodes.Target`, text `Plan`, no chevron, tooltip) after `effortChip` toggles `choice.plan`; `planRow` (`Plan name` label + `planName` field) in the bar under `nameRow`, visible with `choice.plan`.
- [ ] **Step 2:** `start()`: with `choice.plan` a blank name shows `Name the plan` and returns before anything is stored; else passes `planName` to `startPreset` and clears the field.
- [ ] **Step 3:** `CLAUDE.md`: the toggle in the `PromptPanel` paragraph.
- [ ] **Step 4:** `./gradlew test buildPlugin` passes; in the sandbox: Plan on, blank name refused; named, Checkout → plan tab with the manager running the message; New branch → worktree made, manager runs there.
- [ ] **Step 5:** Commit with `Plan-Task: 3/3`; fill the evidence table.
