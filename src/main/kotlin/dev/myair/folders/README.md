# dev.myair.folders

## Overview

The user's folder tree of Folders mode and the plans of plan folders, as one immutable value (`SessionFolders`) that is read from and written to `structure.json`. The file is shared by every IDE and lives at `~/.my-air/structure.json` (built in `src/main/kotlin/dev/myair/service/ClaudeSessionsService.kt`, `StructureStore(Homes.myAir.resolve("structure.json"))`). The package also applies the change lists that agents send through the MCP tools (`FolderChanges`, `PlanChanges`) and keeps the snapshots used for undo (`StructureHistory`). It holds no IDE services, so all of it is testable with plain JUnit.

## Interface

- `SessionFolders.kt`
  - `data class Folder(id, name, folders, items, dirs, plan, workdir)`: `startDir` (the `workdir`, else the first of the `dirs`) and `subtree()`.
  - `fun startFolder(ancestry: List<Folder>)`: the nearest folder in the ancestry that has a `startDir`.
  - `data class Item(type, id)`: the only type is `SessionFolders.SESSION`.
  - `data class SessionFolders(folders, nextId, unfiled)`:
    - Queries: `all`, `find`, `folderOf`, `projectFolder(cwd)`, `startFolder(id)`, `ancestry`, `planFolderAt`, `planFoldersOf(dir)`, `findTask`, `findMilestone`, `taskOf(session)`, `placeOf(session)`, `findByPath`, `pathOf`, `children`, `nameTaken`, `canAddPlan`, `canHold`, `sessionCount`.
    - Folder edits: `createFolder`, `rename`, `delete`, `moveFolder`, `assign`, `unfile`, `carryOver(from, to, scanned)`, `bind`, `unbind`, `setWorkdir`, `briefFolder(path, newId)`.
    - Plan edits: `addPlan`, `removePlan`, `setDescription`, `addMilestone`, `addTask`, `editMilestone`, `editTask`, `moveMilestone`, `moveTask`, `deletePlanItem`.
    - Storage: `restore(snapshot)`, `toJson()`, `SessionFolders.parse(text)`.
- `Plan.kt`
  - `enum PlanStatus`: `PENDING`, `IN_PROGRESS`, `WAITING`, `BLOCKED`, `DONE`, `CANCELED`, serialized as lowercase `label`. It also has `closed` and `PlanStatus.of(label)`.
  - `data class PlanTask`, `data class Milestone` and `data class Plan`, with `tasks()`.
  - `fun progress(tasks)`: `"done/not canceled"`.
- `FolderChanges.kt`
  - `object FolderChanges`, with `apply(structure, changes, scanned, projects, newId)`.
  - Ops: `create`, `add_plan`, `bind`, `unbind`, `set_workdir`, `rename`, `move`, `delete`, `assign`.
- `PlanChanges.kt`
  - `object PlanChanges`, with `apply(structure, folderId, changes)`.
  - Ops: `set_description`, `add_milestone`, `add_task`, `update`, `move`, `delete`.
- `StructureStore.kt`
  - `enum ChangeSource`: `UI`, `AUTO`, `MCP`, `BRIEF`, `RESTORE`, written as a lowercase `label`.
  - `class StructureStore(file, history)`, with `read()` and `@Synchronized update(source, op)`.
- `StructureHistory.kt`: `data class Snapshot(name, taken, source)` and `class StructureHistory(dir, keep = 20, clock)`, with `snapshot`, `list` (newest first) and `read`.

## Invariants

### Structure (`SessionFolders.init`)

These checks throw rather than repair:

- Folder ids are unique.
- Every item is of type `session`.
- A session is filed at most once, and never both filed and in `unfiled`.
- A directory is non-blank and bound to at most one folder.
- A `workdir` is non-blank.
- A folder name is non-blank, trimmed, has no `/` and no control characters, and is unique among its siblings, ignoring case.
- No plan folder holds another plan folder.
- Milestone and task ids are unique across the file. They have the form `M<n>` and `T<n>` with `n < nextId`.
- Titles are non-blank, and a task's `next` is never blank.

### Edits

- Every `SessionFolders` operation that names a folder, milestone or task that is gone returns `this` unchanged, since another IDE may have deleted it. Every other broken rule throws.
- `addMilestone` and `addTask` take their id from `nextId` and increment it, so an id is never reused. `restore` keeps the higher `nextId` of the two structures.
- `deletePlanItem` deletes a milestone only when all its tasks are `closed`. `removePlan` removes only a plan without milestones. `moveTask` stays within one plan.
- `SessionFolders.moveFolder` counts `index` before the move. `FolderChanges` `move` takes the final position and converts it.

### Placing sessions

- `placeOf` decides in this order:
  1. The plan folder of the brief's task.
  2. The folder the session is filed in.
  3. `sessions` (null), when the session is in `unfiled`.
  4. The brief's folder, when it exists and is not in a plan folder.
  5. `projectFolder(session.project)`.
- `projectFolder` compares paths as text and picks the longest bound directory at or above `cwd`.
- `briefFolder` creates a folder only one level below an existing one. It refuses a new top-level folder and any folder at or in a plan folder.

### Change lists and file format

- `FolderChanges.apply` and `PlanChanges.apply` take a JSON array and fold the changes in order.
  - An unknown op, an unknown field (the `FIELDS` whitelist), path, session, directory or id throws an `IllegalArgumentException` that names the change.
  - Nothing is written until the whole list has been applied, so a list lands entirely or not at all.
  - `PlanChanges` addresses only milestones and tasks of the plan of `folderId`.
- `SessionFolders.parse` reads versions 1 to 4 and throws on an unknown version or field.
- `toJson` always writes version 4 (`VERSION`). `description`, `next` and `workdir` appear in the file only when set.

## Side Effects

- `StructureStore`:
  - The constructor creates the parent directory.
  - `update` reads the file again before applying `op`. When the result equals the structure it read, it writes nothing.
  - Otherwise it snapshots the old text to `history` and writes through `dev.myair.store.writeAtomically`.
  - `@Synchronized` serializes callers only within one JVM. Two IDEs editing at once can lose a change made within the same few milliseconds.
- `StructureHistory.snapshot`:
  - Writes `history/structure-<yyyyMMdd-HHmmss-SSS>-<source>.json` with `CREATE_NEW`, so a name taken twice fails.
  - Deletes all but the newest `keep` snapshots.
  - `list` throws on an unexpected file name in the directory, and `read` refuses any name that `list` does not return.
- `FolderChanges` `set_workdir` checks the file system (`Path.isDirectory`). Everything else in the package is pure.

## Verification

- Run: `./gradlew test --tests 'dev.myair.folders.*'`
