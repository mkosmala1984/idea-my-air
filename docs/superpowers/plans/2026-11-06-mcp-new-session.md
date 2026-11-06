# MCP tool: start a new session

| Task | Status | Commit |
|---|---|---|
| 1 | done | |
| 2 | done | |
| 3 | done | |
| 4 | done | |
| 5 | blocked: sandbox check by hand not run yet | |

## TL;DR

- Goal: a `my_air_session_start` MCP tool, so an agent can start a new Claude Code session in a My Air tab: first message, model, effort, directory, added directories, optionally a worktree (fixed base, optional new branch) and a plan task.
- Approach: the tool builds a `Preset` and calls a popup-free core shared with `startPreset`/`startInWorktree`. Worktree creation moves out of the UI callbacks into one function that throws, so the agent gets an error message instead of a dialog in the IDE.
- Decisions for you:
  1. Window: the tab opens in the IDE project the MCP call came in through (`currentCoroutineContext().project`), else the last focused project frame. Alternative: always the window holding the calling session's tab. Proposed: the first.
  2. Approval: the description says "start a session only when the user asked for it in this conversation", with no dialog in the IDE. Alternative: a confirmation dialog in the IDE. Proposed: none.
  3. Worktree without `base`: an error (an MCP call cannot pick from a popup). Alternative: the repository's default branch. Proposed: the error.
  4. Optional `preset` (name from `presets.json`) whose fields the other parameters override? Proposed: yes, it costs a few lines.
- Risks: `startInWorktree` reports failures in dialogs and runs as `Task.Backgroundable`; the refactor must keep the UI path unchanged (dialogs stay there). The tool returns before `claude` writes a transcript, so it returns the new session's id and cwd, not a session from the scanner.
- Out of scope: sending text to a running session, closing sessions, the new-session popup (`PromptPanel`), Jira branch name suggestion.

## Current code

- `FoldersMcpToolset` (`mcp/`) holds the My Air tools; `onEdt` turns `IllegalArgumentException` into `McpExpectedError`. `SessionSystemPromptTest` asserts the system prompt names every tool of `FoldersMcpToolset`.
- `startPreset(project, preset, where, prompt, branch)` (`actions/PresetActions.kt`): checks directories with a dialog (`allExist`), then `startInWorktree` or `ClaudeSessionOpener.startNew`.
- `startInWorktree` (`actions/NewClaudeSessionInWorktreeAction.kt`): `background { Git.repo(dir) }`, then a base popup or the given base, then `background { Git.addWorktree | Git.addBranchWorktree; copyIncluded }`, then `pushRecent` and `startNew(project, dir, path, id, args, plan)`. Failures: `Messages.showErrorDialog`.
- `ClaudeSessionOpener.startNew(project, sessionProject, cwd, id, args, plan)`: files the session into the plan folder (`PlanStart`), opens and pins the tab. EDT.

## Tool contract

```
my_air_session_start(
  prompt: String,                // first message, required, not blank
  dir: String? = null,           // project directory; default: the IDE project's base
  model: String? = null,
  effort: String? = null,        // one of EFFORTS
  add_dirs: String? = null,      // JSON array of directories
  worktree: Boolean? = false,
  base: String? = null,          // required with worktree; a branch of dir's repository
  branch: String? = null,        // new branch off base, only with worktree
  plan_task: String? = null,     // T<n>; files the session in that task's plan folder (PlanStart)
  preset: String? = null,        // name in presets.json; the parameters above override its fields
) -> {"id": "...", "cwd": "...", "project": "..."}
```

Errors (`McpExpectedError`): missing directories (all of them named), unknown effort, preset or plan task, `base` not in the repository, `branch`/`base` without `worktree`, worktree without `base`, any `git` failure with its stderr.

## Tasks

### Task 1: worktree creation without UI

- Extract from `startInWorktree` a function `createWorktree(dir: Path, base: String, branch: String?, id: String): Path` (in `NewClaudeSessionInWorktreeAction.kt`, off the EDT): `Git.repo`, `require(base in repo.branches)`, path from `worktreeName`/`dirName`, running sessions' directories read from `ClaudeSessionsService` (on the EDT before the call, passed in as `running: List<Path>`), `Git.addWorktree`/`addBranchWorktree`, `copyIncluded`, returns the path. `pushRecent` stays with the caller on the EDT.
- `startInWorktree`'s `create` calls it inside its `background`; the dialogs stay there.
- Proof: `./gradlew test` (`GitTest` covers `addWorktree`/`addBranchWorktree`); in the sandbox, New Session in Worktree still creates the worktree and opens the tab.

### Task 2: directory check without UI

- `allExist` splits into `missingDirs(preset, dir): List<Path>` (pure) and the dialog caller. The tool throws `IllegalArgumentException` naming them.
- Test: `PresetsTest` (or a new `PresetActionsTest`) for `missingDirs` with a temp dir and a missing one.

### Task 3: the tool

- New `SessionsMcpToolset` in `mcp/` (or a method of `FoldersMcpToolset`, so the system prompt test sees it: preferred, one toolset, one `onEdt`). Registered in `my-air-mcp.xml` only if it is a new class.
- Body: build the `Preset` (from `preset` by name, `PresetStore.INSTANCE.read().getOrThrow()`, fields overridden; `Preset.init` validates effort and blanks), resolve `PlanStart` from `plan_task` (`folders.findTask` → its plan folder, as `BriefMcpToolset` reports unknown ids with the open ones), check `missingDirs`, then:
  - no worktree: `withContext(EDT) { ClaudeSessionOpener.startNew(project, dir, args = preset.claudeArgs(prompt), id, plan) }`;
  - worktree: `running` on the EDT, `createWorktree` on `Dispatchers.IO`, then `pushRecent` and `startNew` on the EDT.
- Project: `currentCoroutineContext().project` from `com.intellij.mcpserver` (verify the name with `javap` on the mcpserver jar), else `ProjectManager.openProjects` focused one; `checkNotNull` with a message when there is none.
- Returns `{"id","cwd","project"}` as JSON (`McpJson.kt`).
- Description: what it starts, that it opens a tab in the IDE and runs `claude` there, "only when the user asked for a new session in this conversation", that the session's first message should be self-contained (the new session does not see this conversation).

### Task 4: system prompt and docs

- `SessionSystemPrompt.kt`: one line naming `my_air_session_start` and when to use it; `SessionSystemPromptTest` then passes for the new tool.
- `CLAUDE.md`: one sentence in "How the pieces fit" (the MCP part of the session tab paragraph).

### Task 5: proof

- `./gradlew test buildPlugin`.
- Sandbox (`runIde` without `CLAUDE*` variables): from a session tab ask the agent to start a session (a) in the project with `model haiku`, (b) in a worktree off `main` with a new branch, (c) with a missing `add_dirs` entry and (d) worktree without base. Expected: (a)(b) a new pinned tab whose `claude` gets the flags (Session Context shows model/effort; `git worktree list` shows the tree); (c)(d) the agent receives the error text, no dialog in the IDE.
