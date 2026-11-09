# branchdiff

## Overview

The bridge to the optional Branch Diff plugin (`dev.kosmala.branch-diff-tree`). It makes Branch Diff follow the active
session: its target is the work tree the session works in now, against that branch's base. Loaded only when Branch Diff
is installed, through the optional `<depends>` on `my-air-branch-diff.xml`.

## Interface

- `SessionDiffTargetProvider` (`SessionDiffTargetProvider.kt`), implementing Branch Diff's `DiffTargetProvider`,
  registered as `diffTargetProvider` in `src/main/resources/META-INF/my-air-branch-diff.xml`:
  - `target(project): DiffTarget?`: `DiffTarget(Git.root(workDir), Git.baseOf(root), title)`.
  - `subscribe(project, parent, onChange)`: delegates to `ActiveSession.subscribe`.
  - `setBase(project, target, base)`: stores the base for the checked-out branch.

## Invariants

- No active session tab (`ActiveSession.tab == null`) means no target (null).
- The work dir is the scanned session's `workDir`, else the tab's `cwd` for a tab no scan has seen.
- `target` throws (`check`) when the work dir no longer exists or lies in no Git repository; Branch Diff shows the message.
- `setBase` throws on a detached HEAD, which stores no base.
- The title is the session's, else the tab's.

## Side Effects

- `setBase` writes `branch.<branch>.myAirBase` to the repository config (`Git.setBase`), then calls
  `ActiveSession.baseChanged()` on the EDT (skipped once the project is disposed), so Session Context and Branch Diff
  read the base again.
- `target` reads the file system and runs `git` (`Git.root`, `Git.baseOf`).

## Verification

- Run: `./gradlew test buildPlugin` (no tests live in `dev.myair.branchdiff`; Branch Diff's `buildPlugin` must have run
  first, as Gradle compiles against its zip)
