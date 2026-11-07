# Sessions that outlive their tab

## TL;DR

- **Goal:** closing a session tab no longer kills `claude` mid-turn. The process detaches and keeps working. It ends once it has been detached for at least 60 s and is idle. This is what Air 263 does (`DetachedTerminalRetirement`).
- **Approach:** the terminal moves from the editor into an application service, `SessionTerminals`. It is built on the platform's `TransferableTerminalSession` (internal API, the one Air 263 uses), which keeps the process apart from its view: `bind(project)` gives a new `TerminalView` to a live process, `close()` ends it. The editor binds on open and only lets go on dispose. Reopening the session from anywhere binds again, with no second process.
- **Retirement rule:** a pure function `retires(detachedFor, status, runningTasks)`. It is checked on every `ClaudeSessionsService` refresh (file events, and at least every 60 s).
- **Decided (2026-11-07):** a detached session that **needs input** stays alive until answered; the setting "Keep sessions running after their tab closes" defaults to **on**; asking before closing a project or exiting the IDE with detached sessions is a later improvement (`docs/ideas.md`), not this change.
- **Risks:** `TransferableTerminalSession` is `@ApiStatus.Internal` and may change in the next build. Retiring kills background tasks that the transcript does not show as running.
- **Out of scope:** keeping sessions across an IDE restart (Air's `claude --bg` daemon mode), moving a detached session to another IDE process, showing detached sessions differently in the tree.

The decision register for this design is `.claude/decisions/2026-11-07-bffce52c-277f-4166-9be4-da42620de8fe.md`.

## Why

`ClaudeSessionEditor.dispose()` disposes the terminal tab's `Content`, and that ends `claude`. A tab closed by mistake, or to tidy the editor, kills a turn in progress, its subagents and its background shells. Air 263 treats the tab as only a view. Its backend marks the terminal detached when the last view closes (`SessionTerminalState.isDetached`). Every 60 s it checks whether the session still works, and retires it with `SessionTerminalCloseReason.RETIRED` on the first check where it does not. Closing a working session shows "Your Agent Session is still running. You can find it in Agent Sessions." once.

## What Air 263 does (reference)

Decompiled from `air-plugin/lib/modules` (263.7307.200):

| Piece | Where | What it does |
|---|---|---|
| tab close | `AgentThreadViewPendingEditorLifecycleService.fileClosed` | skips moves (`closesForMove`), a session still shown in another project, and `CLOSING_TO_REOPEN`; otherwise reports `surfaceClosed` and shows the hint when the session works |
| detached state | `SessionTerminalState.isDetached` | `isClaimed && !presented && awaitsAgentAddressSince == null && closeRequest == null` |
| retirement | `DetachedTerminalRetirement` | waits 60 s, then checks `AgentSessionRowActivityService.isWorking`; retires on the first check where it is false, otherwise waits another 60 s |
| close reasons | `SessionTerminalCloseReason` | `RETIRED`, `ARCHIVED` (marked Done), `PROJECT_CLOSED` |
| process outside the view | `ToolWindowAgentThreadViewTerminalTabs.prepareTab` | `TransferableTerminalSessionFactory.create(...)`; a view moves with `prepareBind(project).commit().finish()`, and the process ends with `close()` |

`TransferableTerminalSessionFactory` is an application service (`intellij.terminal.frontend`), so the process lives in the application's scope, not the editor's. `create(project, options, resolver, fusInfo, deferSessionStartUntilUiShown)` starts it, and `sessionState` (`TerminalViewSessionState`) reports when it ends.

## Behaviour

| Event | Today | After |
|---|---|---|
| tab closed, setting on | `claude` ends | `claude` keeps running, detached |
| tab closed, setting off | `claude` ends | `claude` ends, unless the platform closes the tab to reopen it (`CLOSING_TO_REOPEN`, a move to another window) |
| tab closed before its process started | `claude` never starts | the same: the process starts only once a view shows, so it ends at once (`releaseOf`) |
| Close and Settle | `claude` ends | `claude` ends (the session is done, like Air's `ARCHIVED`) |
| a detached session opened from the tree, the pinned list, the taskbar or another IDE | n/a | the same process shows in a new tab of the window it last showed in, as opening goes where a session runs (`findTab`, then `bind`) |
| a detached session retires | n/a | `close()`; the session stays in the list without a process, as after `/exit` |
| `claude` exits while detached (`/exit`, a crash) | n/a | the runtime is dropped, and so is its prompt file |
| project closed | tabs close, `claude` ends | the same, detached sessions last bound in that project included |
| IDE exit | `claude` ends | the same |
| the same tab opened in two splits | each editor starts its own `claude` on the same id | the newest editor binds the view; the other shows "Shown in another split" with **Show Here**, which binds it back |

Hint: when a tab of a session whose status is `WORKING` closes and the setting is on, a balloon says, once per IDE run: `"<title>" keeps working. Open it again from Claude Sessions.`

### Retirement rule

```kotlin
/** Whether a session detached for [detachedFor] ends now. [status] is null until the process registers. */
fun retires(detachedFor: Duration, status: SessionStatus?, runningTasks: Int): Boolean =
    detachedFor >= 60.seconds && status == SessionStatus.IDLE && runningTasks == 0
```

| status | runningTasks | detachedFor | retires |
|---|---|---|---|
| `IDLE` | 0 | 59 s | no |
| `IDLE` | 0 | 60 s | yes |
| `IDLE` | 1 | 10 min | no (a background shell or Monitor of this process still runs) |
| `WORKING` | 0 | 10 min | no |
| `NEEDS_INPUT` | 0 | 10 min | no |
| null (not registered yet) | 0 | 10 min | no; a process that never registers ends through `sessionState` instead |

`runningTasks` counts the session's `BackgroundTask`s with `end == null` that started at or after the process's own start (`ProcessHandle.of(pid).info().startInstant()`). Tasks of an earlier process stay open in the transcript forever (the transcript does not record that they died) and must not keep a new process alive.

The check runs at the end of every `ClaudeSessionsService` refresh, after `retargetOpenTabs`, so a detached session retires within 60–120 s of going idle.

## Design

### `SessionTerminals` (application service, `editor/`)

Holds one runtime per `ClaudeSessionVirtualFile`. The file object is the identity, since it carries the pid, and the session id that `/clear` retargets.

```kotlin
class SessionTerminals {
    private class Runtime(val session: TransferableTerminalSession, val promptFile: Path?, var owner: ClaudeSessionEditor?, var project: Project, var detachedAt: Instant?)

    /** Binds [file]'s process to [editor] in [project], starting it on first use; returns the view to show. */
    fun attach(file: ClaudeSessionVirtualFile, editor: ClaudeSessionEditor, project: Project): TerminalView
    /** [editor] lets go of [file]'s process; ends it when keeping is off, otherwise marks it detached. No-op unless [editor] owns it. */
    fun release(file: ClaudeSessionVirtualFile, editor: ClaudeSessionEditor)
    fun close(file: ClaudeSessionVirtualFile)            // Close and Settle, retirement, project close
    fun detached(): List<Pair<Project, ClaudeSessionVirtualFile>>
    fun retireIdle(live: Map<String, LiveSession>, sessions: Map<String, ClaudeSession>, now: Instant)
}
```

- `attach` on a file with no runtime: builds the `claude` command as `ClaudeSessionEditor.start()` does today (`appendedSystemPrompt`, brief reminder settings, `promptFile`, `file.claudeArgs`, the two environment variables, `deferSessionStartUntilUiShown = true`). It creates the session through `TransferableTerminalSessionFactory.getInstance().create(...)`. That call suspends, so the editor shows an empty panel until it returns, and `file.pid` comes from `session.processId()`.
- `attach` on a file with a runtime: `prepareBind(project).commit().finish()`; the previous owner, if any, gets told to show the notice.
- `promptFile` moves from the editor into the runtime. `claude` reads it again after a compaction, so it is deleted when the process ends, not when the tab closes. CLAUDE.md's "lives until the tab closes" changes accordingly.
- A coroutine on `session.coroutineScope` collects `sessionState`. On termination it drops the runtime and deletes `promptFile`. An attached editor keeps showing the ended terminal, as today with `closeOnProcessTermination(false)`.
- A `ProjectCloseListener` closes every runtime whose `project` is closing.

### `ClaudeSessionEditor`

- `start()` asks `SessionTerminals.attach` and wraps the returned `TerminalView` in `CenteredTerminal` as now. `type()` uses the current view.
- `dispose()` calls `release` instead of disposing `Content`.
- `restoreBlocker()` stays as it is. A restored tab never has a runtime, because runtimes do not survive a restart.

### Callers that must also see detached sessions

These iterate `openFiles` today. Each also takes `SessionTerminals.detached()`, otherwise opening a detached session falls through to "already running outside an editor tab of this IDE", where Open Anyway starts a second process:

- `SessionHosts.findTab`: a detached session is a `SessionTab(project, file)`; `focus` reopens the same file object, so the editor attaches to the live process.
- `SessionHosts.openTabs`: published to `ides/<pid>.json` so other IDEs and the taskbar app send their focus requests here. The format is unchanged.
- `retargetOpenTabs`: `/clear` while detached must still move `file.sessionId`.
- `CloseAndSettleAction`: `close(file)` before closing the tab.

`SessionHosts.visibleSessions` stays on open tabs: a detached session is not being looked at.

### Setting

`MyAirSettings.keepDetached` ("Keep sessions running after their tab closes", Settings › Tools › My Air), on by default. Off: `release` calls `close`.

## Verification

Unit (`SessionTerminalsTest`, plain JUnit): `retires` over the table above, and `runningTasks` ignoring tasks started before the process.

Sandbox (`runIde`), each with `~/.claude/sessions/<pid>.json` and Task Manager watched:

1. Start a long turn, close the tab: the pid stays; the hint shows once; the tree still shows the session working.
2. Reopen from the tree mid-turn: the same pid, output continues, no "already running" dialog.
3. Let it finish while closed: the process ends 60–120 s after the status turns `idle`.
4. Close an idle session with a running `run_in_background` shell: it is not retired while the shell runs.
5. Close and Settle: the process ends at once.
6. `/clear` in a tab, close it, reopen the new session from the tree: the same process binds.
7. Focus request from a second IDE and a taskbar click on a detached session: it opens here, the same pid.
8. Split Right on a session tab: one `claude`; the other split shows the notice; Show Here moves the view.
9. Drag a tab to another editor window: the same pid.
10. Close the project with a detached session: its process ends.
11. Setting off: closing the tab ends the process, as today.
12. Resize and focus after rebinding: `CenteredTerminal` columns match the tab width, and typing goes to `claude`.
