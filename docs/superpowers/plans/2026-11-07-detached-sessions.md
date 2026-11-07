# Plan: sessions that outlive their tab

| Task | Status | Commit |
|---|---|---|
| 1 | done | `02ffec3` |
| 2 | done | `0436d8d` |
| 3 | done | `40f12b5` |
| 4 | done | |
| 5 | | |

## TL;DR

- **Goal:** implement `docs/superpowers/specs/2026-11-07-detached-sessions-design.md`. A closed tab detaches `claude`, which ends only after at least 60 s detached and idle, with no background task of its own running.
- **Approach:** `SessionTerminals` (application service) owns one `TransferableTerminalSession` per `ClaudeSessionVirtualFile`. Editors bind views to it, and every finder of session tabs also sees detached files.
- **Decisions:** none open. The three questions are answered in the spec's TL;DR.
- **Risks:** `TransferableTerminalSession` is internal API. Only sandbox runs can verify the views after rebinding (focus, resize, `sendText`), not unit tests.
- **Out of scope:** asking before close/exit (`docs/ideas.md`), surviving an IDE restart.

## Task 1: retirement rule

`src/main/kotlin/dev/myair/editor/Retirement.kt`, pure:

- `fun retires(detachedFor: Duration, status: SessionStatus?, runningTasks: Int): Boolean`: true only for `>= 60 s`, `IDLE` and `0`.
- `fun runningTasks(tasks: List<BackgroundTask>, processStart: Instant?): Int`: counts `end == null && started >= processStart`. A null `processStart` (process gone) counts none.

Test `src/test/kotlin/dev/myair/editor/RetirementTest.kt`: the spec's table, and a task started before the process.

- [x] Write the test, see it fail, implement, see it pass. Commit.

## Task 2: `SessionTerminals` and the editor

`src/main/kotlin/dev/myair/editor/SessionTerminals.kt`, `@Service(APP)` with an injected `CoroutineScope`:

- `Runtime(file, session: Deferred<TransferableTerminalSession>, promptFile, owner, project, detachedAt)`.
- `attach(file, editor, project)`:
  - **new runtime:** builds the `claude` command (moved from `ClaudeSessionEditor.start`). It creates the session through `TransferableTerminalSessionFactory.getInstance().create(project, options, null, deferSessionStartUntilUiShown = true)` on the EDT, and calls `editor.show(session.view)`.
  - **existing runtime:** `session.bind(project)`; the previous owner, if any, gets `showElsewhere()`.
  - Sets `file.pid` from `session.processId()`. When `sessionState` reaches `Terminated`, it drops the runtime and deletes the prompt file.
- `release(file, editor)`: a no-op unless `editor` owns the runtime. With `keepDetached` off it calls `close`; otherwise it sets `owner = null` and `detachedAt = now`.
- `close(file)`: `session.close()`, then drops the runtime and deletes the prompt file.
- `detached(): List<Pair<Project, ClaudeSessionVirtualFile>>`.
- `retireIdle(live, sessions, now)`: applies `retires`. The status comes from `live` by `file.pid`, the tasks from the session `file.sessionId`.
- `ProjectCloseListener.projectClosing` closes every runtime whose `project` is closing.

`ClaudeSessionEditor`:
- `start()` → `SessionTerminals.attach`.
- `show(view)` wraps the view in `CenteredTerminal`.
- `showElsewhere()` shows a notice with **Show Here**, which calls `attach` again.
- `type()` uses the current view.
- `dispose()` → `release`.

- [x] `./gradlew test buildPlugin` passes (except `ContextCardsTest`, which fails the same on `33ac8f7`: `panel {}` needs an Application). Commit.

## Task 3: detached sessions everywhere a tab counts

- `SessionHosts.findTab` and `openTabs` add `SessionTerminals.detached()`.
- `retargetOpenTabs` iterates open plus detached files, and `tabHeads` reads their `cwd`/`project`.
- `ClaudeSessionsService.retargetTabs` calls `SessionTerminals.retireIdle` after retargeting.
- `CloseAndSettleAction` calls `close(file)` before closing the tab.

- [x] Tests pass, build passes. Commit.

## Task 4: setting and hint

- `MyAirSettings.keepDetached` (default true), with a checkbox in `MyAirConfigurable`.
- When a tab closes on a `WORKING` session with the setting on, a balloon shows once per IDE run (notification group `My Air`).

- [x] Build passes. Commit.

## Task 5: docs

- CLAUDE.md: the editor paragraph (process ownership, prompt file lifetime, detached sessions in finders).
- `docs/ideas.md`: the close/exit confirmation.

- [ ] Commit.

## Verification

Unit tests from Task 1, and `./gradlew test buildPlugin`. Then the spec's 12 sandbox scenarios, run by hand in `runIde`.
