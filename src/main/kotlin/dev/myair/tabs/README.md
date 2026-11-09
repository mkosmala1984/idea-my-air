# tabs

## Overview

Finding session tabs and windows, in this IDE and across IDE instances. `SessionHosts` locates the tab (or detached
process) of a session, including JetBrains Air's tabs, and the Win32 window hosting a `claude` process. `TabsSync` is
this IDE's side of `TabStore` (`../store/README.md`): it publishes the IDE's session tabs and opens a session when
another IDE or the taskbar app asks.

## Interface

- `SessionHosts` (`SessionHosts.kt`): `findTab(sessionId): SessionTab?`, `openTabs(): List<OpenTab>`,
  `visibleSessions()`, `focus(tab)`, `allowForeground(pid)`, `hostWindow(pid, cwd): HostWindow?`, `bringToFront(window)`.
- Data: `SessionTab(project, file)`, `HostWindow(pid, executable, title, hwnd)`.
- `TabsSync` (`TabsSync.kt`, `@Service(APP)`, `getInstance()`): `publish()`, `allIdes()`, `isSelf(ide)`,
  `focusElsewhere(sessionId): Boolean`, `focusIn(ide, sessionId)`.
- Registered in `plugin.xml`: `TabsSyncStartup` (`postStartupActivity`), `TabsSyncListener`
  (`FileEditorManagerListener`), `SeenOnActivation` (`ApplicationActivationListener`).
- Store contract: [store](../store/README.md).

## Invariants

- A session tab is a file for which `sessionFileOf` gives a session file (a session tab, or a plan tab with a manager),
  or an Air tab (`com.intellij.air.thread.view.AgentThreadViewVirtualFile`) whose `getThreadId`/`getSessionId` give ids.
- `findTab` prefers an open tab; else a detached session's tab file (`tabFileOf`), so focusing it reopens that file.
- `allIdes()` lists this IDE first; `focusElsewhere` returns false when no other IDE has a tab on the session.
- `focusIn` for this IDE throws when no tab here shows the session; `allowForeground` and `bringToFront` throw when
  Windows refuses.
- A focus request whose session has neither a tab nor a transcript is logged as a warning and dropped; otherwise it
  opens in the first open project whose base contains the session's cwd, else the first open project.
- `hostWindow` walks the `claude` process's ancestors to the first with a visible titled window, preferring one whose
  title contains the session directory's name.

## Side Effects

- Writes `~/.my-air/ides/<pid>.json` on tab open/close, project startup and re-targeting; removes it on `dispose`.
- Watches `~/.my-air/requests/<pid>/` on a daemon thread; requests are handled on the EDT.
- Win32 calls through JNA: `AllowSetForegroundWindow`, `EnumWindows`, `GetWindowText`, `ShowWindow(SW_RESTORE)` (only a
  minimized window), `SetForegroundWindow`.
- Focuses project windows and opens editor tabs; on tab selection and IDE activation calls
  `ClaudeSessionsService.lookedAt()`, which marks sessions seen.

## Verification

- Run: `./gradlew test buildPlugin` (no tests live in `dev.myair.tabs`, so `--tests 'dev.myair.tabs.*'` finds none)
