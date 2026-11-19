# Scheduled tasks: register and run Claude sessions at fixed times, as Claude routines

## TL;DR

- **Goal:** a registry of scheduled tasks in My Air (add, edit, disable, delete, run now) whose runs and failures are seen in My Air, not in a log file nobody reads (`rtm-error-triage` failed 16–18.11 on a moved repository and nobody noticed).
- **Approach (picked by the user):** the clock lives in the plugin (B): a task runs only while an IDE runs, a slot missed while none ran runs once at the next start. A run is an interactive `claude` started without a tab, detached (1), so it shows in the tree and the pinned list with its live status, a permission prompt shows as "needs input" and the finished result as unread. Its process stays until the user has seen the result, so unread, the pinned list and the taskbar keep it.
- **Calls made without asking (to check):** one IDE runs a slot, decided by a run record created with `CREATE_NEW`; a schedule is a set of weekdays and one time of day (no cron); no time limit once the first message is there (5 min to get it); a missed slot catches up only within 12 h; a slot whose previous run still works is skipped; no worktree per run; runs filed in a folder named by the task; the registry is an editor tab like the Sessions dashboard.
- **Risk:** starting a terminal process with no view (`deferSessionStartUntilUiShown = false`) is untried here; a spike goes first.
- **Out of scope:** running while no IDE runs (Windows Task Scheduler), importing `~/.claude/routines` and Claude Desktop's `~/.claude/scheduled-tasks`, cloud routines, cron syntax, prompt templating (`{{DATE}}`).

**Plan:** my-air/scheduled-tasks

## Why

The user already runs scheduled agents outside My Air: `~/.claude/routines/rtm-error-triage` (Windows Task Scheduler at 08:00 → `run.ps1` → `claude -p`, output appended to `logs/<date>.log`) and Claude Desktop's scheduled tasks. Results and failures land in files. The user wants three things, in their words: visibility of results and errors in My Air, a registry of the tasks there, and editing them there. The IDE is open the whole working day, so a clock in the plugin is enough.

Anti-goal: a run that fails silently. Every failure, before or after `claude` starts, must show in My Air without the user looking for it.

## Approaches

**Where the clock lives**

- **A. Windows Task Scheduler.** My Air writes `schtasks` entries and reads results. Runs with the IDE closed, but the run is outside the plugin (no `SessionTerminals`, no live tab binding), and two systems must agree. Rejected by the user: the IDE is always open.
- **B. A timer in the plugin (picked).** Simple, all in one process; a missed slot catches up at the next start, as Claude Desktop's scheduled tasks do.

**How a run starts**

- **1. Interactive session without a tab (picked).** `SessionTerminals` starts `claude --session-id <uuid> ... -- <prompt>` detached. Visibility comes for free: live status, unread, taskbar bubble on needs input / unread reply, open by click, continue the conversation. A permission request waits for the user instead of failing. Cost: no exit code; the end is "idle", and the process ends by the existing `Retirement` (idle 60 s, no own background task).
- **2. Headless `claude -p`** as `run.ps1` and `AnalysisRun`. Clear exit code, but every tool must be pre-allowed or the run fails unanswered, and whether a `-p` process registers in `~/.claude/sessions` (statuses, unread) is unknown. Rejected: it gives less of the visibility the user wants.
- **3. Write Claude Desktop's `scheduled-tasks/*/SKILL.md` and let Desktop run them.** The schedule is not in `SKILL.md`, results stay outside My Air. Rejected.

## Design

### Task

A task is a `Preset` (name, model, effort, project directory, added directories, prompt; reused as is, so `claudeArgs` builds the flags) plus:

- `schedule`: a non-empty set of weekdays and one local time `HH:mm`;
- `enabled`;
- `allowedTools`: optional list passed as `--allowedTools`, so tools known to be needed do not prompt;
- `folder`: optional folder id where its runs are filed (`assign`, `ChangeSource.AUTO`); none: they show where `placeOf` puts any session (its project's bound folder);
- `id`: a stable id (UUID) so a rename keeps the run history;
- `since`: the instant the task was created or its `schedule` last changed (or it was enabled again); slots before it never run.

`prompt` is required; `worktree` must be false (a worktree per run would pile up; a task that needs a fresh checkout says so in its prompt). The preset's `pinned` and `askMore` do not apply and must be false.

Stored in `~/.my-air/scheduled-tasks.json` (`version`, `tasks`), shared by every IDE, written with `writeAtomically`, read on every tick cached by modification time as `PresetsGroup` reads `presets.json`. Unknown keys and invalid values throw; a broken file shows its error in the registry tab and stops scheduling until fixed.

### Slots and catch-up

A slot is a task's scheduled instant (`weekday + time` in the system zone). A tick every 30 s (app service, started with the application) finds, per enabled task, the latest slot `s` with `s <= now`. It runs `s` when no run record exists for `s`, `s >= since`, and `s` is at most 12 hours old (my call). Older missed slots are skipped: an IDE opened Tuesday 07:00 does not run Monday 08:00 an hour before Tuesday 08:00; opened Monday 10:00 it runs Monday 08:00 once.

When the task's previous run still has a live process that is working or needs input, the slot is not run: its record is written as `skipped` with "previous run still running" (shown in the history, no notification). An idle previous run does not block. Run now on such a task is refused with that message (my call).

### One IDE per slot

The run record is the lock: `~/.my-air/scheduled-runs/<task id>/<slot as yyyy-MM-dd'T'HH-mm>.json`, created with `CREATE_NEW`. The IDE whose create succeeds runs the slot; the others see `FileAlreadyExistsException` and skip it. "Run now" creates a record named by the current instant with `manual: true`.

Record fields: `slot`, `manual`, `ide` (pid), `session` (id, set before the start), `state`: `starting`, `started`, `failed`, `skipped`; `error` (text) when failed or skipped; `notified`; `startedAt`. Records are kept 90 days, pruned on the tick.

### A run

1. Check before starting: the task's directory and added directories exist (`Preset.missingDirs`), `claude` is found (`claudeExecutable`). A failure writes `state: failed` with the reason.
2. Create a `ClaudeSessionVirtualFile` with a new session id, the task's directory and `claudeArgs(prompt)` plus `--allowedTools`, file the session in the task's folder, and start it through a new `SessionTerminals.startDetached(file, project)`: as `start` but with no pane, `deferSessionStartUntilUiShown = false`, `owner = null`, `detachedAt = now`. The project is any open project (the one whose base is or contains the task's directory first); the process belongs to it as detached processes do, so closing that project ends it.
3. The record becomes `started`. From here the session is an ordinary detached session: it shows in the pinned list (it has a live process), its status follows the registry, a permission request makes it "needs input" (amber, taskbar bubble), the finished reply makes it unread (blue, bubble), and a click opens it in a tab bound to the same process.
   **Held until seen (picked by the user, A):** unread and the pinned list need a live process, and `retireIdle` would end it 60 s after the reply, taking the result out of sight. So a scheduled run's process does not retire while its session is unread (`Seen`: no seen mark at or after its last message). `retires` gains that condition for the sessions of `started` records; once the user opens the session (tab selected, taskbar or bubble click) the usual 60 s idle rule ends it. Cost accepted: one idle `claude` process per unseen result.
4. Failure after start: the process ends while the session has no transcript (`ended` with no scanned session of that id) → the record becomes `failed` with "claude ended before its first message".
5. Start deadline (my call): no transcript 5 minutes after the start (a start-up question with no view to answer it in: folder trust, `.mcp.json` approval, login) → the process is closed and the record becomes `failed` with "no first message within 5 minutes". Before the start the task's directory gets `declineProjectMcpServers`, as a worktree start does, so the `.mcp.json` question does not come.
6. An IDE that dies leaves its records: on the tick, a record in `starting` or `started` whose `ide` pid no longer runs and whose session has no transcript becomes `failed` with "the IDE running it ended"; the IDE that rewrites it notifies.

Every `failed` record raises a sticky error notification (group "My Air") naming the task and the reason, with an action opening the registry tab, in the IDE that wrote the failure only, once (`notified: true` on the record); and marks the task red in the registry tab until a later run starts.

No time limit once the session has its first message: a long run stays visible as working; the user ends it as any session.

### Registry tab

An editor tab like the Sessions dashboard: `ScheduledTasksVirtualFile` under `myair-scheduled://tasks`, one per IDE, opened from Tools › Scheduled Tasks and a Claude Sessions toolbar button. It lists every task:

```
RTM error triage   Mon–Fri 08:00   next: tomorrow 08:00   ● started 08:00 today
PR descriptions    Mon 09:00       next: Mon 09:00        ● failed: directory missing
Retro              daily 07:30     disabled
  + Add task
```

A row has: name, schedule, next slot (or "disabled"), the latest run's state, and buttons Run now, Edit, Enable/Disable, Delete (asks). It opens to the run history, newest first: slot, state or error, and the run's session (title, status icon, unread mark as in the tree), a click opening it through `ClaudeSessionOpener`. Edit and Add open a dialog: the fields of `PresetDialog` without worktree, pinned and askMore, plus weekdays, time, allowed tools and folder.

## Left to the builder

- Layout and widgets of the tab and dialog, in the look of the dashboard and `PresetDialog`.
- Tick period (30 s or the next slot timer), notification wording, the exact JSON field names.
- Where `startDetached` and the existing `start` share code.

## Spike first

Start a `claude` with `TransferableTerminalSessionFactory.create(project, options, null, deferSessionStartUntilUiShown = false)` and no view: does the process start, register in `~/.claude/sessions`, accept the prompt after `--`, and bind a view later when a tab opens? Terminal size before any view (columns claude renders at) is part of it. If it does not start without a view, the fallback is a hidden view kept by `SessionTerminals`; that changes task 2 of the plan, not the design.
