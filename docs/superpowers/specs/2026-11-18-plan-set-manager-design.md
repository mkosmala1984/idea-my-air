# MCP tool `my_air_plan_set_manager`: replace a plan's manager session

## TL;DR

- **Goal:** an agent can make another session the manager of a plan, e.g. to move the manager to another working directory (start a session there with `my_air_session_start`, then hand the plan to it). Today the manager's `cwd`/`project` are fixed at its first start in `plan-managers.json`.
- **Approach (A, picked by the user):** a new tool `my_air_plan_set_manager(plan, session)` in `FoldersMcpToolset`; it rewrites the plan's entry in `plan-managers.json`, files the session in the plan folder, moves its process (if it runs in this IDE) into the plan tab and sends it `planPrompt(manager = true)`, as Turn into Plan does.
- **Old manager:** its process is detached, never killed (it is usually the very caller of the tool); the existing retirement ends it once idle. Its session stays in the plan folder under `no task`.
- **Refused:** no plan at the path, unknown session, the session manages this or another plan already, the session runs outside this IDE (another IDE, a terminal, Desktop) or is a background job.
- **Risk:** a plan tab of the same plan open in *another* IDE keeps showing the old process until that tab is closed and reopened (out of scope, see Non-goals).
- **Undo:** not via `my_air_history_restore` (`plan-managers.json` is not in the folder history); the tool returns the previous manager's id, so calling it again with that id reverts.
- **Out of scope:** changing the directory of the *same* session (approach B), any UI action.
- No decisions left open for the user.

## Requirements

- **R1** `my_air_plan_set_manager(plan, session)` with `plan` a plan folder path as `my_air_plan_get` gives it and `session` a session id sets the plan's entry in `~/.my-air/plan-managers.json` to that session.
- **R2** The stored `cwd` and `project` are the session's: those of its process's file when it runs in this IDE, else `ClaudeSession.cwd` and `ClaudeSession.project` of the scan.
- **R3** A session that runs in this IDE but has no transcript yet (just started by `my_air_session_start`) is accepted.
- **R4** The tool fails with an error to the agent, changing nothing, when `plan` names no plan folder.
- **R5** It fails, changing nothing, when the session is neither scanned nor running in this IDE.
- **R6** It fails, changing nothing, when the session already manages this plan or another plan (`PlanManagers.folderOf`).
- **R7** It fails, changing nothing, when the live registry shows the session running and no process of this IDE's `SessionTerminals` runs it (another IDE, a terminal, Claude Desktop).
- **R8** It fails, changing nothing, when the session is run by a background job (`LiveSession.job`).
- **R9** The session is filed in the plan folder (`assign`, `ChangeSource.MCP`), so it shows under `no task` unless its brief names a task of the plan.
- **R10** When the session's process runs in this IDE, the plan tab (opened in the IDE project the call came through, or focused if open) binds that process; the session's own tab, if any, closes without ending the process.
- **R11** When the session's process runs in this IDE, it receives `planPrompt(..., manager = true)` as a message (`SessionPane.send`).
- **R12** When the session has no process, `planPrompt(..., manager = true)` becomes the first prompt of the process the plan tab starts (`PlanFiles` first message, as `adopt` does).
- **R13** An open plan tab of that plan in this IDE switches its terminal pane to the new manager without being closed and reopened.
- **R14** The old manager's process in this IDE is detached, not closed, whatever `MyAirSettings.keepDetached` says, so a call made by the old manager itself gets its answer.
- **R15** The old manager's session stays filed in the plan folder.
- **R16** The tool returns the plan folder path, the new manager's id, `cwd` and `project`, and the previous manager's id (null when the plan had none).
- **R17** `my_air_plan_get` gives each plan its `manager`: the managing session's id, null when there is none.
- **R18** The appended system prompt names `my_air_plan_set_manager` and what it is for (`SessionSystemPromptTest` passes).
- **R19** The tool's description tells the agent to call it only when the user asked for it in the conversation, and how to move a manager to another directory (start a session there with `my_air_session_start`, `folder` set to the plan's path, then call this tool).

## Why

The manager runs where it was first started (`managerStart` stores `cwd`, `project`, and `--resume` reuses them). When the plan's work moves (a worktree, another checkout), the manager stays in the old directory. Replacing it with a session started in the new directory is the general operation; a directory change is its main use.

## Goals, non-goals, anti-goals

- Goal: one MCP call replaces the manager, in the IDE the agent talks to, without the user touching the UI.
- Non-goal: changing the directory of the same session id (`--resume` from another cwd); a UI action for this; following the change in a plan tab open in another IDE (that IDE's `PlanFiles` keeps its manager file while the process runs; it picks the new manager once the tab is reopened after the old process ends).
- Anti-goal: the call kills the process that made it; a half-applied change (entry written but tab still bound to the old process, or folder filed but entry not written) after a failure.

## Approaches

- **A. `my_air_plan_set_manager(plan, session)`, any existing session** — picked. General; the directory change is two calls (`my_air_session_start`, then this). The new manager starts with a fresh context and gets the plan from `planPrompt`.
- B. `my_air_plan_set_manager_dir(plan, dir)`, same session resumed elsewhere — keeps the context, but must end the running manager to resume it in another cwd, which kills the caller when the manager calls it; only covers the directory case.
- Both — rejected as more surface than needed now; B can be added later on top of A's pane rebinding.

## Design

- **Store:** `PlanManagers.set` overwrites the entry; `PlanFiles` gets a `replaceManager(folderId, manager, running, message)` next to `adopt`, sharing its checks (R6) except that an existing entry for the folder is allowed and returned as the previous manager. `adopt` keeps refusing a session managing a plan already.
- **Process binding:** `PlanFiles.managers[folderId]` becomes the new session's running file (or is removed when there is none, so `startManager` builds it from the store on the next bind). `PlanEditor` gets a way to rebind its `SessionPane` to `PlanFiles.manager(folderId)` (dispose the old pane with detach forced, R14; create a pane for the new file; `ActiveSession.tabFileChanged()`), called by the tool on every open `PlanEditor` of the plan in this IDE (R13).
- **Forced detach (R14):** `SessionTerminals.release` currently closes when `keepDetached` is off; the old manager's pane needs a release that always detaches (a parameter or a separate call), never applied to a job view (R8 keeps jobs out anyway).
- **Order of effects** (the anti-goal on half-applied changes): validate everything on the EDT first (R4–R8), then write the store, file the session, rebind, open the plan tab, send the message, close the session's own tab — the same order as `TurnIntoPlanAction.actionPerformed`, whose flow this extracts and reuses where it fits.
- **Other IDE:** R7 refuses a session running there, so the new manager never has a process outside this IDE at the moment of the call.
- **Prompt and docs:** `appendedSystemPrompt` lists the tool (R18); CLAUDE.md's plan view paragraph gains a sentence on replacing the manager.

## Left to the builder

- Whether `replaceManager` and `adopt` merge into one function with a flag or stay two.
- How `PlanEditor` exposes rebinding (a method found through `FileEditorManager.getAllEditors(file)`, as `TurnIntoPlanAction` finds the pane).
- The exact error texts and JSON field names of the result, within R16.
