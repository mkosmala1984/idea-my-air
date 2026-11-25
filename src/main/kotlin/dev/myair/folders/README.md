# dev.myair.folders

## Overview

The user's folder tree of Folders mode and the plans of plan folders, as one immutable value (`SessionFolders`) that is read from and written to `structure.json`. The file is shared by every IDE and lives at `~/.my-air/structure.json` (built in `src/main/kotlin/dev/myair/service/ClaudeSessionsService.kt`, `StructureStore(Homes.myAir.resolve("structure.json"))`). The package also applies the change lists that agents send through the MCP tools (`FolderChanges`, `PlanChanges`) and keeps the snapshots used for undo (`StructureHistory`). It holds no IDE services, so all of it is testable with plain JUnit.

## Interface

- `SessionFolders.kt`
  - `data class Folder(id, name, folders, items, dirs, plan, workdir)`: `startDir` (the `workdir`, else the first of the `dirs`) and `subtree()`.
  - `fun startFolder(ancestry: List<Folder>)`: the nearest folder in the ancestry that has a `startDir`.
  - `data class Item(type, id)`: the only type is `SessionFolders.SESSION`.
  - `data class SessionFolders(folders, nextId, unfiled)`:
    - Queries: `all`, `find`, `folderOf`, `projectFolder(cwd)`, `startFolder(id)`, `ancestry`, `planFolderAt`, `planFoldersOf(dir)`, `planParentFor(dir)` (where a new plan for `dir` goes), `findTask`, `findMilestone`, `taskOf(session)`, `placeOf(session)`, `findByPath`, `pathOf`, `children`, `nameTaken`, `canAddPlan`, `canHold`, `sessionCount`.
    - Folder edits: `createFolder`, `rename`, `delete`, `moveFolder`, `assign`, `unfile`, `carryOver(from, to, scanned)`, `bind`, `unbind`, `setWorkdir`, `briefFolder(path, newId)`.
    - Plan edits: `addPlan`, `removePlan`, `setDescription`, `addMilestone`, `addTask`, `editMilestone`, `editTask`, `moveMilestone`, `moveTask`, `deletePlanItem`, `setConstraints`, `addDecision`, `editDecision`, `addRequirement`, `editRequirement`, `setReview`, `addBlocker`, `editBlocker`, `deleteEntry`.
    - Storage: `restore(snapshot)`, `toJson()`, `SessionFolders.parse(text)`.
- `Plan.kt`
  - `enum PlanStatus`: `PENDING`, `IN_PROGRESS`, `WAITING`, `BLOCKED`, `DONE`, `CANCELED`, serialized as lowercase `label`. It also has `closed` and `PlanStatus.of(label)`.
  - `data class PlanTask`, `data class Milestone` and `data class Plan` (description, milestones, `constraints`, `decisions`, `requirements`, `review`, `next`), with `tasks()` and `usesVersion5`.
  - `Requirement` (`status` derived from its evidence: `RequirementStatus` `UNPROVEN`/`NOT_DONE`/`PROVEN`), `EvalKind`, `Decision`, `Review` with `ReviewStatus`, `Blocker`, `requireEvidence(item)` and `Plan.ENTRY_PREFIXES` (`R`, `D`, `B`).
  - `fun progress(tasks)`: `"done/not canceled"`.
- `FolderChanges.kt`
  - `object FolderChanges`, with `apply(structure, changes, scanned, projects, reviews, canvases, newId)` returning `Applied(folders, canvases)`: the folders and the ids of the folders the list made canvas folders (still in the structure); it runs `refuseKindPlacement` with those counted. Writing their `canvas.json` is the caller's.
  - Ops: `create` (`plan`, `canvas` optional), `add_plan`, `add_canvas`, `bind`, `unbind`, `set_workdir`, `rename`, `move`, `delete`, `assign`.
- `PlanChanges.kt`
  - `object PlanChanges`, with `apply(structure, folderId, changes, today)`.
  - Ops: `set_description`, `add_milestone`, `add_task`, `set_constraints`, `add_decision`, `add_requirement`, `add_evidence`, `set_review`, `add_blocker`, `update`, `move`, `delete`.
- `KindPlacement.kt`: `placementProblem(before, after, reviews, canvases, added)`, `refuseKindPlacement(before, after, reviews, canvases, added)` (`added`: canvas folders the write makes, counted in `after` only) and `inReviewOrCanvas(structure, reviews, canvases, id)` (what the tree's menus disable plan items with), the rules for review and canvas folders, whose ids `SessionFolders` does not know (see Invariants).
- `StructureStore.kt`
  - `enum ChangeSource`: `UI`, `AUTO`, `MCP`, `BRIEF`, `RESTORE`, written as a lowercase `label`.
  - `class StructureStore(file, history)`, with `read()` and `@Synchronized update(source, op)`.
- `StructureHistory.kt`: `data class Snapshot(name, taken, source)` and `class StructureHistory(dir, keep = 20, clock)`, with `snapshot`, `list` (newest first) and `read`.

## Invariants

### Folder kinds (`KindPlacement.kt`)

A review folder holds no plan and lies in no review or plan folder. A canvas folder holds no plan, review or canvas folder and lies in no review folder; it may lie in a plan folder. Only placements new in a write count, so a structure made before a rule existed does not block unrelated writes. `ClaudeSessionsService.updateFolders` runs `refuseKindPlacement` on every structure write, and the tree's drop runs `placementProblem`; the tree's menus disable New Plan..., Make Folder a Plan and Turn into Plan... where `inReviewOrCanvas` holds.

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
- Plan entries (`Plan.init`): `next` keys are `R`, `D` or `B` with values above 1; requirement, decision and blocker ids are unique within the plan and below their prefix's `next`; texts are non-blank, and a requirement's or blocker's on one line; `constraints` and a decision's `session` are non-blank when set; a requirement's tasks are tasks of its plan, each named once, and its evidence items match `requireEvidence`; dates are ISO local dates; a review commit is not blank.

### Edits

- Every `SessionFolders` operation that names a folder, milestone or task that is gone returns `this` unchanged, since another IDE may have deleted it. Every other broken rule throws.
- `addMilestone` and `addTask` take their id from `nextId` and increment it, so an id is never reused. `restore` keeps the higher `nextId` of the two structures.
- `addRequirement`, `addDecision` and `addBlocker` take their id from the plan's `next` (`R`/`D`/`B` per plan), so it is never reused within the plan, even after a delete.
- `deletePlanItem` deletes a milestone only when all its tasks are `closed`, and takes the deleted tasks out of every requirement's `tasks`. `removePlan` removes only a `removable` plan: no milestones, constraints, decisions, requirements or review. `moveTask` stays within one plan.
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
- `PlanChanges` `update` accepts the fields of its id's prefix only (`M`/`T`: title, description, status, next; `R`: text, eval, tasks, evidence; `D`: text; `B`: text, resolved); `add_evidence` appends one item; a milestone's status is `open` or `done`.
- `SessionFolders.parse` reads versions 1 to 5 and throws on an unknown version or field.
- `toJson` writes version 5 while a plan holds a new field (`Plan.usesVersion5`: constraints, decisions, requirements, review or a non-empty `next`), else version 4. `description`, `next` and `workdir` appear in the file only when set.

## Side Effects

- `StructureStore`:
  - The constructor creates the parent directory. `history` defaults to `history` beside the file.
  - `read` and `update` take a missing file as an empty structure.
  - `update` reads the file again before applying `op`. When the result equals the structure it read, it writes nothing.
  - Otherwise it snapshots the old text to `history` (none when the file did not exist) and writes through `dev.myair.store.writeAtomically`.
  - `@Synchronized` serializes callers only within one JVM. Two IDEs editing at once can lose a change made within the same few milliseconds.
- `StructureHistory.snapshot`:
  - Writes `history/structure-<yyyyMMdd-HHmmss-SSS>-<source>.json` with `CREATE_NEW`, so a name taken twice fails.
  - Deletes all but the newest `keep` snapshots.
  - `list` throws on an unexpected file name in the directory, and `read` refuses any name that `list` does not return.
- `FolderChanges` `set_workdir` checks the file system (`Path.isDirectory`). Everything else in the package is pure.

## Verification

- Run: `./gradlew test --tests 'dev.myair.folders.*'`
