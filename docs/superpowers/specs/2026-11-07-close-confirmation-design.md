# Ask before closing a project or the IDE with working sessions

## TL;DR

- **Goal:** closing a project, exiting or restarting the IDE no longer ends a working `claude` without a word. My Air asks first, as Air 263 does.
- **Approach:** a pure function `interrupts(status, runningTasks)` decides which processes of `SessionTerminals` count: the registry says `WORKING` or `NEEDS_INPUT`, or a background task the process started still runs. Idle ones and ones still starting are never asked about. A `ProjectCloseHandler` asks about the processes of the closing project (open tabs and detached). An `ApplicationListener` asks about all of them on exit and restart. The dialog texts come from a pure function `confirmation(closing, titles)`.
- **Decided without asking (register):** the dialogs say "Claude Session" where Air says "Agent Session", and say that closing *ends* the sessions, which is what My Air does. During an exit the project handler does not ask again, since the exit question covered every session. Install My Air Build and Restart now asks too when a session works, because it ends every `claude`.
- **Risks:** statuses come from the last refresh of `ClaudeSessionsService` (file events, debounced to under a second). A session that started working in that window is not asked about.
- **Out of scope:** keeping sessions across a restart, asking about `claude` processes My Air does not run (terminals, Desktop), asking again for sessions that start working between the exit question and the projects closing (Air does that).

## Platform facts (IU-263.7307.200, `ApplicationImpl`, read with `javap -c`)

- `exit(int, boolean, String[], int)` sets `myExitInProgress = true` before it runs `doExit`, so `isExitInProgress` is already true when the project handlers are called during an exit.
- `destructApplication` calls `canExit(restart)` unless the flags carry `FORCE_EXIT` (bit 1): `iload 7; ifne 193; ... invokevirtual canExit`. `restart(exitConfirmed)` builds the flags as `4 | (exitConfirmed ? 2 : 0)`, so `restart(true)` (Install My Air Build and Restart) skips only the platform's "Are you sure?" dialog and still calls the vetoes.
- `canExit` asks every `ApplicationListener` first (`canRestartApplication` on restart, `canExitApplication` otherwise), then `ProjectManagerEx.canClose(project)` for every open project, which runs the `projectCloseHandler` extensions.

## Behaviour

| Event | Sessions asked about | Dialog | Yes | No |
|---|---|---|---|---|
| project closes (window closed, File › Close Project, a project opened in its window) | processes whose `Runtime.project` is the project, with a tab or detached, that `interrupts` | "Close Project with Running Claude Session(s)?", Close Project / Keep Open | the project closes; `ProjectClose` ends its processes, as today | the project stays open |
| IDE exits | every process that `interrupts` | "Exit IDE with Running Claude Session(s)?", End Sessions and Exit / Keep IDE Open | the IDE exits, every process ends | the IDE stays |
| IDE restarts (including Install My Air Build and Restart) | the same | "Restart IDE with Running Claude Session(s)?", End Sessions and Restart / Keep IDE Open | the IDE restarts | the IDE stays |
| a project closes as part of an exit or restart | none, the exit already asked | none | n/a | n/a |
| no process `interrupts` | none | none | n/a | n/a |

`interrupts(status, runningTasks)` = `status == WORKING || status == NEEDS_INPUT || runningTasks > 0`.

- `status` is the registry status of the process's pid in `ClaudeSessionsService.live`. It is null while the process starts (no pid, or no registry entry yet) and after it ended.
- `runningTasks` is `runningTasks(tasks, processStart)` from `Retirement.kt`. It is 0 when the process is gone.

So an ended process (its tab still shows the ended terminal) and a starting one are not asked about. `retireIdle` reads status and tasks through the same helper.

The message lists the sessions. For one: "'<title>' is still running. Closing the project ends it." For several: "<n> Claude sessions are still running:" then one title per line, then "Closing the project ends them." For exit and restart the last sentence reads "Exiting ends …" / "Restarting ends …".

## Wiring

- `ProjectCloseConfirmation : ProjectCloseHandler`, registered as `<projectCloseHandler>` in `plugin.xml`. It returns true at once while `isExitInProgress`.
- `ExitConfirmation : ApplicationListener`. `SessionTerminals` registers it in its constructor through `addApplicationListener(listener, this)`, with `SessionTerminals` made `Disposable`. No process exists before that service does, so the listener never misses one.
- `SessionTerminals.interrupted(project: Project?)` returns the titles, asserting the EDT, as everything that touches `runtimes` does.
