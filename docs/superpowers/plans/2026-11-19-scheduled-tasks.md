# Scheduled Tasks Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `44c2581` |
| 2 | done | `16c2e86` |
| 3 | done | `8f0d081` |
| 4 | done | `591819d` |
| 5 | done | `e8b25c4` |
| 6 | done | `4ae7449` |
| 7 | done | `a792e23` |

## TL;DR

- **Goal:** tasks registered in My Air start an interactive `claude` without a tab at fixed weekday times; results and failures show in My Air.
- **Approach:** package `dev.myair.schedule`: a task store (`scheduled-tasks.json`), pure slot maths, run records that double as the one-IDE lock, a scheduler service ticking every 30 s, `SessionTerminals.startDetached`, retirement held while the result is unread, a registry editor tab.
- **Decisions needing input:** none; the spike (Task 1) may change how `startDetached` keeps a process without a view, not the design.
- **Risk:** Task 1: the platform terminal may not start a process before a view exists.
- **Out of scope:** as the spec: cron, Windows Task Scheduler, importing existing routines, prompt templating.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** register scheduled Claude tasks in My Air, run them at their times as detached interactive sessions, and show every run and failure in My Air.

**Architecture:** pure logic (task model and JSON, slots, run records, decisions) in `dev.myair.schedule` with JUnit tests; IDE glue (scheduler service, `SessionTerminals.startDetached`, notifications, registry tab) verified in the sandbox IDE.

**Tech Stack:** Kotlin, IntelliJ Platform 263 (local IU), Gson, JUnit 5, `java.time`.

**Spec:** `docs/superpowers/specs/2026-11-19-scheduled-tasks-design.md`

**Plan:** my-air/scheduled-tasks

## Global Constraints

- English in code, comments, strings, test names. Test names are backtick sentences.
- Unknown JSON keys and invalid values throw (`require`/`check`); no fallbacks, no silent skips.
- Files in `~/.my-air` are written through `writeAtomically` (`store/AtomicFiles.kt`); paths from `Homes.myAir`.
- Catch-up window 12 hours; tick 30 s; start deadline 5 minutes; records kept 90 days; retirement grace stays `DETACHED_GRACE` (60 s).
- A slot's record file name: `<task id>/<yyyy-MM-dd'T'HH-mm>.json` in the system zone; a manual run: `<yyyy-MM-dd'T'HH-mm-ss>-manual.json`.
- Classes named in `plugin.xml` are found at runtime only; every new one must be registered there.
- Commit trailers: `Plan-Task: N/7` and `Plan: docs/superpowers/plans/2026-11-19-scheduled-tasks.md`.

## Review Focus

- A slot on a DST change day (02:30 on the spring-forward Sunday) must still run once, at the resolved instant: test in Task 3.
- Two IDEs ticking in the same second must run a slot once: concurrent `claim` test in Task 4.
- Editing a task's time to earlier today must not fire the slot already passed today: `since` test in Task 3 (`edited` in Task 2).
- A task whose folder was deleted must fail its run with a reason, not throw inside the tick: pre-start check test in Task 6.
- Records of a deleted task must not break the registry or pruning: test in Task 4.

---

### Task 1: Spike and `SessionTerminals.startDetached`

**Files:**
- Modify: `src/main/kotlin/dev/myair/editor/SessionTerminals.kt` (`start`, new `startDetached`)

**Interfaces:**
- Produces: `SessionTerminals.startDetached(file: ClaudeSessionVirtualFile, project: Project)` (EDT): starts `file`'s process with no pane, `owner = null`, `detachedAt = Instant.now()`, then `TabsSync.getInstance().publish()`. Asserts no runtime exists for `file` yet.

- [x] **Step 1:** Refactor `start(file, pane: SessionPane?, project, deferUntilUiShown: Boolean)`; `attach` passes its pane and `true`, `startDetached` passes `null` and `false` to `TransferableTerminalSessionFactory.create(..., deferSessionStartUntilUiShown = ...)`. With `pane == null` the runtime starts detached (`detachedAt` set).
- [x] **Step 2:** Temporary trigger for the spike: an internal action (not committed) that builds a `ClaudeSessionVirtualFile(UUID, cwd = project base, project = project base, NEW_SESSION_TITLE, listOf("--session-id", id, "--", "say hi and stop"))` and calls `startDetached`.
- [x] **Step 3:** Run the sandbox (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`), trigger it and check, writing each answer into this task as a line:
  - a `~/.claude/sessions/<pid>.json` appears with the session id and goes `busy` then `idle`;
  - the session shows in the pinned list with the working spinner, then idle;
  - opening it from the pinned list binds the same process in a tab (no second `claude`, text readable, columns follow the tab);
  - 60 s after idle with no tab it ends (`retireIdle`).
  If the process does not start without a view: keep a hidden view in the runtime (`session.view` held, never added to a component), retry, and note the result. Stop and report if neither works.
- [x] **Step 4:** Remove the temporary action, `./gradlew test buildPlugin` passes.
- [x] **Step 5:** Commit `SessionTerminals: start a session's process without a tab (startDetached)`.

### Task 2: Task model and store

**Files:**
- Create: `src/main/kotlin/dev/myair/schedule/ScheduledTasks.kt`
- Test: `src/test/kotlin/dev/myair/schedule/ScheduledTasksTest.kt`

**Interfaces:**
- Produces:
  - `data class Schedule(val days: Set<DayOfWeek>, val time: LocalTime)`, `init { require(days.isNotEmpty()) }`.
  - `data class ScheduledTask(val id: String, val preset: Preset, val schedule: Schedule, val enabled: Boolean, val allowedTools: List<String>, val folder: String?, val since: Instant)`, `init`: `preset.prompt != null`, `!preset.worktree`, `!preset.pinned`, `!preset.askMore`, no blank `allowedTools` entry. `val name get() = preset.name`.
  - `fun ScheduledTask.claudeArgs(sessionId: String): List<String>` = `--session-id <id>`, then `--allowedTools <comma-joined>` when not empty, then `preset.claudeArgs(preset.prompt)`.
  - `fun edited(old: ScheduledTask, new: ScheduledTask, now: Instant): ScheduledTask`: `new` with `since = now` when `schedule` changed or `enabled` went false→true, else `old.since`.
  - `fun parseScheduledTasks(json: String): List<ScheduledTask>`, `fun scheduledTasksJson(tasks: List<ScheduledTask>): String`. Format: `{"version":1,"tasks":[{"id","name","model","effort","project","addDirs","prompt","days":["MONDAY",…],"time":"08:00","enabled","allowedTools","folder","since":"<ISO instant>"}]}`; absent optional keys are null/empty; unknown keys, another `version`, a duplicate `id` throw.
  - `class ScheduledTaskStore(val file: Path)` with `read(): Result<List<ScheduledTask>>` cached by modification time and size as `PresetStore.read`, `write(tasks: List<ScheduledTask>)`; `companion object { val INSTANCE = ScheduledTaskStore(Homes.myAir.resolve("scheduled-tasks.json")) }`.

- [x] **Step 1: Write the failing tests**
  - `round trips every field through JSON` (one full task, one minimal).
  - `rejects an unknown key, another version and a duplicate id` (`assertThrows`).
  - `rejects a task without a prompt, with a worktree, with no days`.
  - `puts allowed tools before the preset flags and the prompt after --`: `assertEquals(listOf("--session-id","s1","--allowedTools","Read,Bash(git log:*)","--model","opus","--","triage"), …)`.
  - `an edit moves since only when the schedule changes or the task is enabled again`.
  - `read returns a failure naming a broken file and an empty list for a missing one`.
- [x] **Step 2:** `./gradlew test --tests dev.myair.schedule.ScheduledTasksTest` fails (unresolved).
- [x] **Step 3:** Implement `ScheduledTasks.kt` as above, parsing with Gson `JsonParser` like `parsePresets`.
- [x] **Step 4:** The tests pass.
- [x] **Step 5:** Commit `Scheduled tasks: model and ~/.my-air/scheduled-tasks.json store`.

### Task 3: Slots

**Files:**
- Create: `src/main/kotlin/dev/myair/schedule/Slots.kt`
- Test: `src/test/kotlin/dev/myair/schedule/SlotsTest.kt`

**Interfaces:**
- Consumes: `Schedule`, `ScheduledTask` (Task 2).
- Produces:
  - `val CATCH_UP: Duration = 12.hours`
  - `fun latestSlot(schedule: Schedule, now: ZonedDateTime): ZonedDateTime` (the latest slot `<= now`, looking back 7 days)
  - `fun nextSlot(schedule: Schedule, now: ZonedDateTime): ZonedDateTime` (the first slot `> now`)
  - `fun dueSlot(task: ScheduledTask, now: ZonedDateTime, claimed: (ZonedDateTime) -> Boolean): ZonedDateTime?`: `latestSlot` when `task.enabled`, slot `>= since`, `now - slot <= CATCH_UP` and not `claimed`; else null.

- [x] **Step 1: Write the failing tests** (zone `Europe/Warsaw`, schedule Mon–Fri 08:00):
  - `the latest slot of Tuesday 07:00 is Monday 08:00 and the next is Tuesday 08:00`.
  - `a slot runs within the catch-up window`: Monday 10:00 → Monday 08:00.
  - `a slot older than 12 hours does not catch up`: Tuesday 07:00 → null; Monday 07:00 after a weekend → null.
  - `a disabled task, a claimed slot and a slot before since never run`.
  - `editing the time to earlier today does not fire today's passed slot` (since = now 09:00, time 08:00 → null).
  - `a slot in the spring-forward gap runs once at the resolved instant` (2026-03-29 02:30 → 03:30 CEST).
- [x] **Step 2:** Tests fail.
- [x] **Step 3:** Implement with `ZonedDateTime.with(time)` per day back from `now.toLocalDate()`.
- [x] **Step 4:** Tests pass.
- [x] **Step 5:** Commit `Scheduled tasks: slots, next slot and 12 h catch-up`.

### Task 4: Run records

**Files:**
- Create: `src/main/kotlin/dev/myair/schedule/RunRecords.kt`
- Test: `src/test/kotlin/dev/myair/schedule/RunRecordsTest.kt`

**Interfaces:**
- Produces:
  - `enum class RunState { STARTING, STARTED, FAILED, SKIPPED }`
  - `data class RunRecord(val taskId: String, val slot: Instant, val manual: Boolean, val ide: Long, val session: String, val state: RunState, val error: String?, val notified: Boolean, val startedAt: Instant)`; `init`: `error != null` iff state is `FAILED` or `SKIPPED`.
  - `class RunRecords(val dir: Path, val zone: ZoneId)`: `claim(record): Boolean` (`Files.newOutputStream(CREATE_NEW)`, false on `FileAlreadyExistsException`), `exists(taskId, slot: Instant): Boolean`, `update(record)` (atomic), `of(taskId): List<RunRecord>` newest `startedAt` first, `all(): List<RunRecord>`, `prune(now: Instant)` deleting records whose `startedAt` is over 90 days old (and empty task dirs). `companion object { val INSTANCE = RunRecords(Homes.myAir.resolve("scheduled-runs"), ZoneId.systemDefault()) }`.
  - Pure decisions in the same file:
    - `fun orphaned(record: RunRecord, ideAlive: Boolean, hasTranscript: Boolean): Boolean` = state in `STARTING`/`STARTED`, `!ideAlive`, `!hasTranscript`.
    - `fun startTimedOut(record: RunRecord, now: Instant, hasTranscript: Boolean): Boolean` = `STARTED`, `!hasTranscript`, `now - startedAt >= 5.minutes`.
    - `fun blocksNext(status: SessionStatus?): Boolean` = `WORKING` or `NEEDS_INPUT`.

- [x] **Step 1: Write the failing tests**
  - `a slot is claimed once`: second `claim` of the same task and slot returns false.
  - `of two threads claiming the same slot exactly one wins` (two threads, `CountDownLatch`, 50 rounds).
  - `update replaces a record and of lists newest first`.
  - `prune deletes records over 90 days old, including those of a deleted task`.
  - `a record with an error must be failed or skipped`.
  - `orphaned`, `startTimedOut` (4:59 no, 5:00 yes, with transcript never), `blocksNext` (working and needs input block, idle and null do not).
- [x] **Step 2:** Tests fail.
- [x] **Step 3:** Implement; JSON by Gson with explicit fields, unknown keys throw.
- [x] **Step 4:** Tests pass.
- [x] **Step 5:** Commit `Scheduled tasks: run records, the one-IDE claim and run decisions`.

### Task 5: Retirement held while unread

**Files:**
- Modify: `src/main/kotlin/dev/myair/editor/Retirement.kt`, `editor/SessionTerminals.kt` (`retireIdle`), `service/ClaudeSessionsService.kt:158`
- Test: `src/test/kotlin/dev/myair/editor/RetirementTest.kt`

**Interfaces:**
- Produces: `fun retires(detachedFor: Duration, status: SessionStatus?, runningTasks: Int, held: Boolean = false): Boolean` (false when `held`); `SessionTerminals.retireIdle(live, sessions, now, held: Set<String>)`, `held` checked by `file.sessionId`.
- Consumes (later, Task 6): `ScheduledRuns.held(unread: Set<String>): Set<String>`; until Task 6 the service passes `emptySet()`.

- [x] **Step 1:** Test `a held session never retires, however long idle` and that the existing cases are unchanged.
- [x] **Step 2:** Fails.
- [x] **Step 3:** Implement; update the call in `ClaudeSessionsService`.
- [x] **Step 4:** `./gradlew test` passes.
- [x] **Step 5:** Commit `Retirement: a held session stays until seen`.

### Task 6: Scheduler service

**Files:**
- Create: `src/main/kotlin/dev/myair/schedule/ScheduledRuns.kt`, `src/main/kotlin/dev/myair/schedule/RunProject.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (app service, `My Air` notification group already exists), `service/ClaudeSessionsService.kt` (`held` from Task 5)
- Test: `src/test/kotlin/dev/myair/schedule/RunProjectTest.kt`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces:
  - `fun pickProject(dir: Path, bases: List<Path>): Int?`: index of the base that is or contains `dir` (longest), else 0, null when empty.
  - `fun preStartError(task: ScheduledTask, dir: Path, folderExists: Boolean, claudeFound: Boolean, hasProject: Boolean): String?`: first of "directory missing: <path>" (`preset.missingDirs`), "folder deleted", "claude not found on PATH", "no IDE project open".
  - `@Service(APP) class ScheduledRuns(scope: CoroutineScope)`: `tick(now: Instant)`, `runNow(taskId: String): String?` (null when started, else the refusal), `held(unread: Set<String>): Set<String>` (sessions of this IDE's `STARTED` records in `unread`). Started from `ClaudeSessionsService` init (`ScheduledRuns.getInstance()`), ticks every 30 s via `AppExecutorUtil.getAppScheduledExecutorService()`.

Tick, in order (records on a pooled thread, starts on the EDT):
1. Read tasks; a failure: log once per distinct message, run nothing.
2. For each record of this IDE (`ide == ProcessHandle.current().pid()`): `startTimedOut` → `SessionTerminals.close(file)`, `FAILED` "no first message within 5 minutes"; `STARTED`, no transcript and `!SessionTerminals.runs(file)` → `FAILED` "claude ended before its first message". For any record: `orphaned` (ide pid not alive) → `FAILED` "the IDE running it ended".
3. Each task's `dueSlot(task, now, claimed = { records.exists(task.id, it.toInstant()) })`: previous run's live status `blocksNext` → claim as `SKIPPED` "previous run still running"; else claim `STARTING` with a new session id and run.
4. Every `FAILED` record of this IDE with `notified == false`: sticky notification "Scheduled task \"<name>\" failed: <error>" with action "Open Scheduled Tasks", then `notified = true`.
5. `prune(now)` once a day.

A run: `preStartError` → `FAILED`; else `SeenStore.mark(session, startedAt)` (so `firstSight` does not swallow the reply), `declineProjectMcpServers(dir)`, `updateFolders(ChangeSource.AUTO) { it.assign(session, folder) }` when `folder` set, `ClaudeSessionVirtualFile(session, dir, dir, task.name, task.claudeArgs(session))`, `SessionTerminals.startDetached(file, project)`, record `STARTED`. An exception in a run → `FAILED` with its message (logged as error).

- [x] **Step 1:** Tests for `pickProject` (nested bases pick the longest, no match → 0, none → null) and `preStartError` (each reason, in order, null when fine).
- [x] **Step 2:** Fail.
- [x] **Step 3:** Implement both, then `ScheduledRuns`; register in `plugin.xml`.
- [x] **Step 4:** `./gradlew test buildPlugin`; in the sandbox, with a task due now in `scheduled-tasks.json` written by hand: it starts once with two sandbox windows open, shows in the pinned list, stays after idle while unread, ends 60 s after being opened; a task with a missing directory gives the notification once; `claude` killed before its first message gives "claude ended before its first message".
- [x] **Step 5:** Commit `Scheduled tasks: the scheduler runs due slots as detached sessions and reports failures`.

### Task 7: Registry tab, dialog, docs

**Files:**
- Create: `src/main/kotlin/dev/myair/schedule/ui/ScheduledTasksFiles.kt` (`ScheduledTasksVirtualFile`, `ScheduledTasksFileSystem` `myair-scheduled://tasks`, provider, editor, one file per IDE as `DashboardFiles`), `ui/ScheduledTasksPanel.kt`, `ui/ScheduledTaskDialog.kt`, `ui/OpenScheduledTasksAction.kt`, `ui/TaskRows.kt`
- Modify: `plugin.xml` (file system, editor provider, action in Tools menu and the Claude Sessions toolbar), `CLAUDE.md` (a paragraph on scheduled tasks under "How the pieces fit" and the two files under "Where the data comes from")
- Test: `src/test/kotlin/dev/myair/schedule/ui/TaskRowsTest.kt`

**Interfaces:**
- Consumes: `ScheduledTaskStore`, `RunRecords`, `nextSlot`, `ScheduledRuns.runNow`, `edited`, `ClaudeSessionOpener.open`.
- Produces: `fun scheduleText(schedule: Schedule): String` ("daily 07:30", "Mon–Fri 08:00", "Mon, Wed 09:00"); `data class TaskRow(val name: String, val schedule: String, val next: String, val last: String?, val failed: Boolean)`; `fun taskRow(task: ScheduledTask, records: List<RunRecord>, now: ZonedDateTime): TaskRow` (`next` "disabled" for a disabled task; `failed` when the newest non-skipped record failed; `last` "<state> <time>" or "failed: <error>").

- [x] **Step 1:** Tests for `scheduleText` (the three forms) and `taskRow` (disabled, failed newest, a later started run clears failed, no records → `last` null).
- [x] **Step 2:** Fail.
- [x] **Step 3:** Implement `TaskRows.kt`, then the tab (rows with Run now, Edit, Enable/Disable, Delete with a confirmation; a row opens to its history: slot, state or error, the session with `sessionIcon` and unread bold, click → `ClaudeSessionOpener.open`), a broken store file shown in place of the list, and the dialog (name, prompt, directory, added directories, model, effort, weekdays, time, allowed tools, folder picked from `SessionFolders` outside plan folders) writing through `edited`. The tab refreshes on `ClaudeSessionsService` notifications and every tick.
- [x] **Step 4:** `./gradlew test buildPlugin`; in the sandbox: add a task, Run now, edit its time, disable it, delete it; a failed run shows red and its error; history opens the session.
- [x] **Step 5:** Update `CLAUDE.md`; commit `Scheduled tasks: registry tab, task dialog, docs`.

## Requirements evidence

Recorded in the My Air plan (my-air/scheduled-tasks) per requirement:

| R | Task |
|---|---|
| R1 | 2, 7 |
| R2 | 3, 6 |
| R3 | 3 |
| R4 | 4 |
| R5 | 1, 2, 6 |
| R6 | 1, 6 |
| R7 | 4, 6 |
| R8 | 7 |
| R9 | 7 |
| R10 | 3 |
| R11 | 5, 6 |
| R12 | 4, 6 |
