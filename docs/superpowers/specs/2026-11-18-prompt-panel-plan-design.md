# Prompt panel: start a new session as the manager of a new plan

## TL;DR

- **Goal:** from the new-session panel (`PromptPanel`) create a plan right away: a new plan folder whose manager is the session being started, opened in the plan tab instead of a session tab. Today this takes two steps: start a session, then Turn into Plan.
- **Approach (A, picked by the user):** a toggle chip `Plan` in the card's chip row; while on, the bar shows a `Plan name` row (as `New branch` shows its row) and Enter/send creates the plan. Nothing else changes in the panel: configuration, model, effort, project, workspace and base apply to the manager as to a plain session.
- **The session only manages the plan:** the typed message goes to it as is (or nothing); its role comes from the manager system prompt that every manager start already appends. No instruction to break the message into milestones.
- **Calls made without asking (to check):** the plan folder goes into the folder bound to the project directory, else the top level (a bound folder that is or lies in a plan gives the plan's parent); the name is required, no default from the message; the name is not kept in the draft; the toggle is kept with the last start's choices like the other chips.
- **Risk:** small; the manager's first start gets the panel's flags through a new per-folder argument list in `PlanFiles` that replaces `firstMessages` (Turn into Plan moves to it too).
- **Out of scope:** the new-branch popup and preset toolbar buttons (`startPreset` outside the panel), MCP `my_air_session_start` creating plans, Haiku naming the plan.

## Requirements

- **R1** The panel's chip row has a `Plan` chip after effort that toggles on and off; off by default.
- **R2** With `Plan` on, the bar shows a `Plan name` row with a text field; with it off the row is hidden. The popup refits its height (`relayout`).
- **R3** Starting with `Plan` on and a blank plan name shows an error under the panel and starts nothing, the message kept.
- **R4** Starting with `Plan` on creates a plan folder (`createFolder(..., plan = Plan())`, `ChangeSource.UI`) named by the field, in `projectFolder(dir)` of the start's project directory; when that folder is or lies in a plan folder, in that plan folder's parent; with no bound folder, at the top level.
- **R5** The new session (a new UUID) is filed in that plan folder and stored as its manager in `plan-managers.json` with the `cwd` it starts in and the project directory.
- **R6** With workspace Checkout the manager starts in the project directory; New worktree and New branch first create the worktree as a plain start does (`createWorktree`, base remembered), the manager starting in it; Existing worktree starts it in the worktree picked.
- **R7** The manager's first process gets the panel's `--model`, `--effort` and `--add-dir` and the typed message after `--` (`Preset.claudeArgs(prompt)`); an empty message starts it without one.
- **R8** The plan opens in a plan tab in the panel's IDE project (`PlanOpener.open`); no session tab opens.
- **R9** The manager's start carries the manager system prompt (`planPrompt(manager = true)`) as any manager start does; nothing else is added to its first message.
- **R10** A `--resume` of the manager later gets none of the panel's flags (as for presets).
- **R11** The `Plan` toggle is stored with the last start's choices and with a draft, and restored by the next panel; the plan name is not stored and opens empty.
- **R12** Turn into Plan still sends its manager prompt as the first prompt of a manager without a process, through the same per-folder arguments.
- **R13** A start that fails before the plan exists (blank name, missing directories, a worktree that cannot be made) creates no plan folder and no `plan-managers.json` entry.

## Why

Starting a planned piece of work means today: start a session, wait for it, then Turn into Plan from its menu, which also names the plan in a dialog. Choosing it in the panel up front saves the second step and starts the manager with the model and workspace picked there.

## Approaches

- **A. Toggle chip + `Plan name` row (picked).** Fits the panel: every choice is a chip, a field shows only when needed (as `New branch`). Enter keeps working; the toggle persists like the other choices.
- **B. A second button `Plan ►` beside send, the name in a dialog.** No toggle to remember, but Enter cannot create a plan and the panel gets two start buttons.
- **C. Start a plain session, then run Turn into Plan on it automatically.** Rejected: races the process start and sends the manager prompt as a message, which a fresh start does not need.

## Design

- `Choice` / `LastChoice` / `Draft` (`PromptChoices.kt`) gain `plan: Boolean`; `saveChoice`/`loadChoice` store it under `$key.plan`.
- `PromptPanel`: `planChip` (a `Chip` used as a toggle, its look on/off as the chip API allows), `planRow` with `planName` in the bar beside `nameRow`; `start()` validates the name, then calls a new `startPlan(project, preset, where, prompt, branch, name, existingDir)` instead of `startPreset`.
- `startPlan` (next to `startPreset`, `actions/PresetActions.kt`): resolves the start directory as `startPreset` does; for a worktree, `createWorktree` off the EDT as `startInWorktree` does (base popup when the base is not known yet), then on the EDT: create the folder (R4), assign the session, `PlanFiles.adopt(folderId, PlanManager(id, cwd, project), running = null, args = preset.claudeArgs(prompt))`, `PlanOpener.open`. The worktree comes first so R13 holds.
- `PlanFiles`: `firstMessages: Map<String, String>` becomes `firstArgs: Map<String, List<String>>`, appended after `managerStart`'s `--session-id`/`--resume` args; `adopt` takes `args: List<String>` in place of `message`. Turn into Plan passes `listOf("--", message)`. `managerStart` with an entry whose session has no transcript already gives `--session-id <id>`, so a fresh manager needs no other change.
- Where the plan folder goes is a pure function over `SessionFolders` (`planParentFor(dir)`), tested.

## Left to the builder

- How the toggle chip shows its on state (icon, text colour, background) within `Composer.kt`'s `Chip`.
- Whether `startPlan` shares code with `startInWorktree` by extracting the worktree step, or calls `createWorktree` directly.
