# store

## Overview

The files My Air owns under `~/.my-air`, shared by every IDE running the plugin and by the taskbar app: tab lists and
focus requests, pins, seen marks, presets, plan managers and system prompt files. Each store parses strictly and writes
through `writeAtomically`, so concurrent readers never see half a file.

## Interface

- `writeAtomically(target, text)` (`AtomicFiles.kt`): temp file, then a Win32 POSIX-semantics rename over the target.
- `Homes` (`Homes.kt`): `claude` (`~/.claude`), `myAir` (`~/.my-air`).
- `TabStore(root, self, isAlive)` (`TabStore.kt`): `publish(tabs)`, `all()`, `requestFocus(pid, sessionId)`,
  `receiveRequests(onRequest): Closeable`, `withdraw()`; `TabStore.startMillis`, `TabStore.isAlive`; data `OpenTab`,
  `IdeTabs`.
- `Pins` (`PinnedSessions.kt`): `sessions`, `dismissed`, `settled`; `pin`, `unpin`, `remove`, `settle`, `unsettle`,
  `shown`, `prune`, `toJson`, `Pins.parse`. `pinnedSessions(sessions, live, pins, unread)`. `PinStore(file)`: `read()`,
  `update(op)`.
- `SeenStore(dir)` (`Seen.kt`): `read()`, `mark(sessionId, at)`; `firstSight(sessions, live, seen)`,
  `newToUser(visible, sessions, seen)`.
- `Preset` (`Presets.kt`): `dir(ideBase)`, `missingDirs(dir)`, `claudeArgs(prompt)`; `EFFORTS`, `presetOf(session, name)`,
  `continuationPrompt(session)`, `checkPresets`, `presetsJson`, `parsePresets`. `PresetStore(file)`: `read(): Result`,
  `write(presets)`, `PresetStore.INSTANCE` (`~/.my-air/presets.json`).
- `PlanManager(session, cwd, project)`, `PlanManagers` (`PlanManagers.kt`): `set`, `moveSession`, `prune`, `folderOf`,
  `toJson`, `PlanManagers.parse`. `PlanManagerStore(file)`: `read()`, `update(op)`.
- `PromptFiles` (`PromptFiles.kt`): `write(dir, text, pid)`, `prune(dir)`.

## Invariants

- Every write goes through `writeAtomically`; a rename failing with other than access denied or sharing violation, or
  failing 5 times, throws. The temp file is always deleted.
- Parsers reject unknown keys and wrong types (`check`): `Pins.parse` needs `sessions` and allows only
  `sessions`/`dismissed`/`settled`; `parsePresets` only `presets` at the root and `PRESET_KEYS` per preset;
  `PlanManagers.parse` exactly `session`/`cwd`/`project`.
- `Preset` construction requires a non-blank name and values, `effort` in `EFFORTS`, and no `base` without `worktree`;
  preset names are unique (`checkPresets`). `claudeArgs` puts the prompt after `--` and refuses a blank one.
- `Pins.pin` unsettles and undismisses; `settle` unpins and undismisses. A dismissed session stays hidden until it has a
  message after the dismissal and is not working; `prune` drops dismissals whose process ended or that are over.
- `pinnedSessions` orders needing input, then unread, then the rest, keeping the input order within a group.
- `PinStore.update` and `PlanManagerStore.update` are `@Synchronized` and write only when the value changed.
- `PresetStore.read` parses again only when the file's modification time or size changed; a parse failure is returned
  as a failed `Result`.
- `SeenStore.read` skips names with a dot (temp files); session ids have none.
- `TabStore` ignores and deletes the tab file of an IDE whose pid no longer has the recorded start time; a file
  removed between listing and reading is skipped.
- `PromptFiles` names files `<IDE pid>-<uuid>.md`; `prune` deletes those whose IDE process is not alive.

## Side Effects

- Creates and writes `~/.my-air/ides/<pid>.json`, `requests/<pid>/*.json`, `pins.json`, `seen/<id>`, `presets.json`,
  `plan-managers.json` and prompt files; deletes stale IDE files, drained requests and stale prompt files.
- `TabStore.receiveRequests` starts a daemon thread with a `WatchService` on its inbox; the returned `Closeable` stops it.
- `writeAtomically` calls Win32 through JNA (`CreateFile`, `SetFileInformationByHandle`) and sleeps 50 ms between retries.

## Verification

- Run: `./gradlew test --tests 'dev.myair.store.*'`
