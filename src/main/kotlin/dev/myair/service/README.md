# service

## Overview

The application-wide session state of My Air. `ClaudeSessionsService` is the one watcher over `~/.claude` and `~/.my-air`:
it scans transcripts, reads the live registry, folders and pins, and pushes every change to subscribed panels on the EDT.
After each change it re-targets open tabs to the session their process shows now and writes the taskbar board. The
other files are pure functions the service and the UI call, kept here so plain JUnit tests can cover them.

## Interface

- `ClaudeSessionsService` (`ClaudeSessionsService.kt`, `@Service(APP)`, `getInstance()`):
  - state: `sessions`, `listed` (with unscanned running sessions), `live`, `folders`, `foldersError`, `heads`, `pins`,
    `unread`, `loaded`, `structureFile`, `history`; `branchOf(session)`.
  - `subscribe(parent, listener)`, `refresh()`, `lookedAt()`, `updateFolders(source, op)`, `updatePins(op)`.
- `ActiveSession` (`ActiveSession.kt`, `@Service(PROJECT)`, `getInstance(project)`): `tab` (the active
  `ClaudeSessionVirtualFile`), `baseGeneration`, `subscribe(parent, listener)`, `baseChanged()`, `tabFileChanged()`;
  pure `activeTab(selected, last, isOpen)`.
- `BoardWriter(myAir)` (`BoardWriter.kt`): `seenStore`, `update(sessions, live, settled): Set<String>` (the unread ids).
- `TaskbarBoard.kt`: `BoardSession`, `RecentSession`, `RECENT = 5`, `boardSessions(sessions, live, seen)`,
  `recentSessions(sessions, board, settled)`, `boardJson(board, recent)`.
- `TabRetargeting.kt`: `NEW_SESSION_TITLE`, `TabTarget`, `retarget(sessionId, title, pid, job, live, titles)`,
  `retargetOpenTabs(live, titles, unread, workDirs, heads): List<Pair<String, String>>` (session switches, from to to).
- `AttentionQueue.kt`: `attentionQueue(sessions, live, unread)`, `nextInQueue(queue, shown, last)`.
- `SessionProjects.kt`: `inProjects(sessions)`, `LinkedWorktree(path, created)`, `gitWorktrees(root)`.
- `UnscannedSessions.kt`: `unscannedSessions(scanned, live)`, `withUnscanned(scanned, unscanned)`.
- `LatestRead<K, V>(executor, edt, read, land)` (`LatestRead.kt`): `asked`, `request(key)`, `forget()`;
  `LatestRead.onPool(executor, modality, read, land)`.

## Invariants

- `ClaudeSessionsService` state and listeners are touched on the EDT only; refreshes run on a single-thread bounded
  executor (`refresher`), so an older result never lands after a newer one.
- `structure.json` and `pins.json` are read on the EDT inside the refresh's `invokeLater`, where `updateFolders` and
  `updatePins` write, so a read from before a change cannot undo it.
- A refresh exception is logged as an IDE error (`thisLogger().error`) and drops that refresh; a broken `structure.json`
  sets `foldersError` instead of hiding the sessions, and `updateFolders` throws (`check`) while it is set.
- Listeners are notified only when the read `State` or `unread` changed, except the first refresh (`loaded`).
- With `MyAirSettings.briefs` off, every session's `brief` is dropped at scan time.
- `ActiveSession` notifies only when the tuple (tab, its session id, the session, its work tree head, its live entry,
  `baseGeneration`) changed; `baseChanged()` asserts the EDT.
- `retarget` follows a tab by pid or job (`shownBy`), never by session id; an unregistered process keeps its session
  with a null status. A session absent from `titles` gets `NEW_SESSION_TITLE`.
- `retargetOpenTabs` publishes to other IDEs (`TabsSync.publish`) only when a session or title changed, not a status alone.
- `boardSessions` orders by `SessionStatus.waitRank`; `unread` means idle with a last message after `seen[id]`.
  `recentSessions` leaves out settled sessions and those in another flyout group, at most `RECENT`.
- `gitWorktrees` skips an admin directory without `gitdir`, and `worktreeOf` throws on an empty `gitdir`.
- `LatestRead` lands only the result of the latest `request`; `forget` drops the one under way.
- `pluginJar()` throws unless the plugin's classes come from a regular jar file; `LOCALAPPDATA` must be set.

## Side Effects

- Watches `~/.claude/sessions`, `~/.claude/projects/*`, `~/.my-air` and `~/.my-air/seen` (`ClaudeHomeWatcher`), plus a
  60 s scheduled rescan (`safetyNet`).
- Reads transcripts, the registry, `%LOCALAPPDATA%/Claude-Data` (Desktop sessions), HEAD files of every session's and
  open project's directories (`Git.head`) and `.git/worktrees/*`.
- Writes `~/.my-air/taskbar/board.json` (only when its content changed) and `~/.my-air/seen/<id>` via `BoardWriter`;
  `pins.json`, `structure.json` and `plan-managers.json` through the stores; the scan cache on `dispose` (`scanner.save()`).
- Ends idle detached terminals on every refresh (`SessionTerminals.retireIdle`).
- Mutates open tab files (`sessionId`, `title`, `status`, `unread`, `mark`) and repaints their tabs
  (`updateFilePresentation`); carries folder filing and plan managers over to a switched session id.

## Verification

- Run: `./gradlew test --tests 'dev.myair.service.*'`
