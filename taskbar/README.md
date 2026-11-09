# taskbar

## Overview

`MyAirTaskbar`, a .NET 10 WPF app (`Deskband11Lib.Wpf` 1.4.2) that hosts a small window inside the Windows 11 taskbar.
It shows three counts: sessions needing input, with an unread reply, and working. Hover peeks at a flyout listing them,
a click pins it, and a bubble announces sessions that newly need the user. It reads what the plugin writes to
`~/.my-air` and talks to IDEs only through files. Tests live in `tests/` (`BoardTest.cs`).

## Interface

- `App` (`App.xaml.cs`): single instance, creates one `BoardWatcher` and the `MainWindow`, recreating the window on
  `TaskbarWindowRecreationRequired`.
- `Board.cs`: records `BoardSession`, `OpenTab`, `Ide`, `RecentSession`, `BoardFile` (`Empty`); enums `SessionStatus`
  (`Working`, `NeedsInput`, `Idle`), `Group` (`NeedsInput`, `Unread`, `Working`, `Recent`); static `Board`: `ParseBoard`,
  `ParseIde`, `Target`, `GroupOf`, `Arrivals`, `Count`, `HiddenIdle`, `Ago`, `Label`, `Title`, `Detail`, `Summary`.
- `BoardWatcher(root, dispatcher)` (`BoardWatcher.cs`): `Board`, `Recent`, `Ides`, events `Changed` and `Arrived`,
  `Focus(sessionId)`.
- `MainWindow` (`MainWindow.xaml(.cs)`): the counts, `Host` (`TaskbarContentHost`, placed `BeforeNotificationArea`),
  hover/click/right-click (Exit) handling.
- `Flyout` (`Flyout.xaml(.cs)`): `Pinned`, `JustDismissed`, `Peek(widget)`, `Pin(widget)`, `Dismiss()`.
- `Bubble` (`Bubble.xaml(.cs)`): `Announce(arrived)`, `Dismiss()`.
- `Popup` (`Popup.cs`): `Items(board, groups, resources, projectInTitle)`, `PlaceOver(window, widget)`,
  `HideFromAltTab(window)`; records `FlyoutHeader`, `FlyoutRow`.
- `Theme` (`Theme.cs`): `TaskbarIsLight()`, `Apply(resources)`, `DotKey(group)`.
- `build.ps1`: stops `MyAirTaskbar`, runs `dotnet test tests`, builds Release, starts the new exe.

## Invariants

- JSON is parsed strictly (`Board.Options`): camelCase, unmapped members disallowed, required constructor parameters and
  nullable annotations respected, statuses as `SNAKE_CASE_UPPER` strings without integers. A changed format throws.
  The format is the plugin's `boardJson` (`src/main/kotlin/dev/myair/service/TaskbarBoard.kt`) and `IdeTabs`
  (`src/main/kotlin/dev/myair/store/TabStore.kt`).
- An IDE counts only while its pid's start time equals `Started` (same test as `TabStore.isAlive`).
- `GroupOf` is null for an idle session already read; those are counted in the footer (`HiddenIdle`) when "Recent" does
  not list them.
- `Arrivals` are sessions newly in `NeedsInput` or `Unread`; `Arrived` is never raised on the first read nor without a
  live IDE.
- `Target` picks the IDE with a tab on the session, else any IDE; `Focus` throws when there is none or when
  `AllowSetForegroundWindow` fails.
- The bubble hides 8 s after the last arrival unless hovered, drops a session that left its announced group, and is not
  shown while the flyout is visible; opening the flyout dismisses it.
- Without a live IDE the flyout shows an empty board and the widget is drawn at 0.4 opacity.
- An unhandled dispatcher exception shows a message box and exits with code 1; a second instance (mutex
  `Local\MyAir.Taskbar`) exits at once.

## Side Effects

- Reads `~/.my-air/taskbar/board.json` and `~/.my-air/ides/*.json` with read/write/delete sharing; watches both
  directories (`FileSystemWatcher`) and reloads every 5 s.
- Creates `~/.my-air/ides`, `seen` and `taskbar` directories.
- `Focus` writes `~/.my-air/requests/<pid>/<guid>.json` (temp `.tmp`, then moved) and `~/.my-air/seen/<id>` (epoch ms).
- Win32: `AllowSetForegroundWindow`, `SetWindowLongPtr` (`WS_EX_TOOLWINDOW`); reads the registry value
  `HKCU\...\Themes\Personalize\SystemUsesLightTheme`.

## Verification

- Run: `pwsh taskbar/build.ps1` (or `dotnet test taskbar/tests` alone)
