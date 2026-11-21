# store

## Overview

The files My Air owns under `~/.my-air`, shared by every IDE running the plugin and by the taskbar app: tab lists and
focus requests, pins, seen marks, presets, profiles (`profiles.json`), plan managers, reviews
(`reviews/<folderId>/review.json`), reviewer profiles (`review-profiles.json`) and system prompt files. Each store but
`TabStore` parses strictly (`TabStore` reads its files with plain `Gson.fromJson`), and each writes through
`writeAtomically`, so concurrent readers never see half a file.

## Interface

- `writeAtomically(target, text)` (`AtomicFiles.kt`): temp file, then a Win32 POSIX-semantics rename over the target.
- `Homes` (`Homes.kt`): `claude` (`~/.claude`, the `default` profile), `myAir` (`~/.my-air`).
- `TabStore(root, self, isAlive)` (`TabStore.kt`): `publish(tabs)`, `all()`, `requestFocus(pid, sessionId)`,
  `receiveRequests(onRequest): Closeable`, `withdraw()`; `TabStore.startMillis`, `TabStore.isAlive`; data `OpenTab`,
  `IdeTabs`.
- `Pins` (`PinnedSessions.kt`): `sessions`, `dismissed`, `settled`; `pin`, `unpin`, `remove`, `settle`, `unsettle`,
  `shown`, `prune`, `toJson`, `Pins.parse`. `pinnedSessions(sessions, live, pins, unread)`. `PinStore(file)`: `read()`,
  `update(op)`.
- `SeenStore(dir)` (`Seen.kt`): `read()`, `mark(sessionId, at)`; `firstSight(sessions, live, seen)`,
  `newToUser(visible, sessions, seen)`.
- `Preset` (`Presets.kt`): `dir(ideBase)`, `missingDirs(dir)`, `claudeArgs(prompt)`, `withVariant(variant)`,
  `firstVariant()`, `promptHead()`; `Variant(model, effort)`, `parseVariants(text)`, `variantsText(variants)`; `EFFORTS`, `presetOf(session, name)`,
  `continuationPrompt(session)`, `checkPresets`, `presetsJson`, `parsePresets`. `PresetStore(file)`: `read(): Result`,
  `write(presets)`, `PresetStore.INSTANCE` (`~/.my-air/presets.json`).
- `Profile`, `DEFAULT_PROFILE`, `Profile.isDefault`, `parseProfiles(text, defaultDir)`, `profilesJson(profiles)`,
  `ProfileStore(file, defaultDir)` (`Profiles.kt`, `~/.my-air/profiles.json`, `ProfileStore.getInstance()`): `all()`,
  `byName`, `default`, `find` (null when not listed; `default` without reading the file), `write(profiles)`;
  `profileEnv`, `addedProfileEnv`, `processProfileEnv`; `ideServer(claudeJson)`, `managerToolsRefusal`, `removeRefusal`,
  `mcpAddCommand`, `createProfileDirs(profile)` (`ProfileChecks.kt`).
- `ProfileCopy.kt`: `CopyItem` (the nine items a new profile can copy once from the default one, each with its group,
  label, effect and `CopySource`: a `settings.json` key, a file or a directory; `tickedByDefault`), `Availability`
  (`Available`, `Unavailable(reason, error)`), `copyAvailability(sourceDir)`, `copyFromDefault(sourceDir, targetDir,
  selection, register)`, `ProfileCopyException`.
- `PlanManager(session, cwd, project, profile)`, `PlanManagers` (`PlanManagers.kt`): `set`, `moveSession`, `prune`, `folderOf`,
  `toJson`, `PlanManagers.parse`. `PlanManagerStore(file)`: `read()`, `update(op)`.
- `PromptFiles` (`PromptFiles.kt`): `write(dir, text, pid)`, `prune(dir)`.
- `BranchReview` (`Reviews.kt`) with `Severity`, `FindingState`, `Report`, `Finding`, `Run`, `Chapter`, `Question`;
  `nextId(prefix)`, `toJson()`, `parseReview(text, source)`; internal `Keys`, the strict key reader `ReviewProfiles.kt`
  shares. `ReviewFiles(root)`: `file`, `readAll()`, `exists`, `read`, `write` of `<root>/<folderId>/review.json`
  (`~/.my-air/reviews`).
- `Reviewer`, `ReviewProfile`, `parseReviewProfiles(text, source)` (`ReviewProfiles.kt`). `ReviewProfileStore(file)`:
  `read(): Result`, `ReviewProfileStore.INSTANCE` (`~/.my-air/review-profiles.json`).

## Invariants

- Every write goes through `writeAtomically`; a rename failing with other than access denied or sharing violation, or
  failing 5 times, throws. The temp file is always deleted.
- Parsers reject unknown keys and wrong types (`check`): `Pins.parse` needs `sessions` and allows only
  `sessions`/`dismissed`/`settled`; `parsePresets` only `presets` at the root and `PRESET_KEYS` per preset;
  `PlanManagers.parse` exactly `session`/`cwd`/`project`, optionally `profile` (`toJson` writes it only when not
  `default`); `parseProfiles` exactly `version` (1) and `profiles` at the root and `name`/`dir` per entry;
  `parseReview` its `format` (1) and an explicit key set per object (`Keys`); `parseReviewProfiles` only `profiles` at
  the root, `name`/`reviewers` per profile and `name`/`prompt`, optionally `model`/`effort`/`profile`, per reviewer.
- Profile names are not blank, not `default` in any case, and unique; directories are absolute, unique, hold no quote
  and are not the default directory. `ProfileStore.write` checks what it writes the same way.
- `copyFromDefault` writes only into an absent or empty directory (an empty selection only calls `register`), copies
  no link, junction or other special entry (it fails naming it), and on any failure of the copy or `register` deletes
  everything under the target (the target too when it was absent) before throwing. `copyAvailability` marks a missing
  or broken source as unavailable instead of throwing: a broken `settings.json` only the five key items, a bad
  `custom:` theme only the theme.
- Reviewer names and prompts are not blank and an effort is in `EFFORTS`; neither reviewer names within a profile nor
  profile names repeat. A `Finding` has at least one report, and a rejected, fixed or posted one its `reason`,
  `fixCommit` or `comment`. `BranchReview.next` is never lowered.
- `Preset` construction requires a non-blank name and values, `effort` in `EFFORTS`, and no `base` without `worktree`;
  a `profile` is not blank; `variants` are none or at least two, unrepeated, and exclude a `model` or `effort` on the
  preset; a `Variant` sets a model or an effort, its model not blank, its effort in `EFFORTS`; preset names are unique
  (`checkPresets`). `claudeArgs` puts the prompt after `--` and refuses a blank one.
- `Pins.pin` unsettles and undismisses; `settle` unpins and undismisses. A dismissed session stays hidden until it has a
  message after the dismissal and is not working; `prune` drops dismissals whose process ended or that are over.
- `pinnedSessions` orders needing input, then unread, then the rest, keeping the input order within a group.
- `PinStore.update` and `PlanManagerStore.update` are `@Synchronized` and write only when the value changed.
- `PresetStore.read` and `ReviewProfileStore.read` parse again only when the file's modification time or size
  changed; a parse failure is returned as a failed `Result`, a missing review profiles file as none.
  `ProfileStore.all()` is cached the same way but throws on a broken file. `ReviewFiles.readAll` keeps a broken
  `review.json` as a failed `Result`.
- `SeenStore.read` skips names with a dot (temp files); session ids have none.
- `TabStore` ignores and deletes the tab file of an IDE whose pid no longer has the recorded start time; a file
  removed between listing and reading is skipped.
- `PromptFiles` names files `<IDE pid>-<uuid>.md`; `prune` deletes those whose IDE process is not alive.

## Side Effects

- Creates and writes `~/.my-air/ides/<pid>.json`, `requests/<pid>/*.json`, `pins.json`, `seen/<id>`, `presets.json`,
  `profiles.json`, `plan-managers.json`, `reviews/<folderId>/review.json` and prompt files; deletes stale IDE files, drained requests and stale prompt files.
- `createProfileDirs` creates a profile's `projects/` and `sessions/`.
- `copyFromDefault` creates files in a new profile's directory and, on failure, deletes everything under it.
- `TabStore.receiveRequests` starts a daemon thread with a `WatchService` on its inbox; the returned `Closeable` stops it.
- `writeAtomically` calls Win32 through JNA (`CreateFile`, `SetFileInformationByHandle`) and sleeps 50 ms between retries.

## Verification

- Run: `./gradlew test --tests 'dev.myair.store.*'`
