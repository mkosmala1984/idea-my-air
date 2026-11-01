# Project plan: implementation

| Task | Status | Commit |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |

## TL;DR

- **Goal:** implement the spec `docs/superpowers/specs/2026-11-01-project-plan-design.md` on branch `project-plan`.
- **Approach:** five tasks, each with tests and one commit: model, MCP, brief, tree, prompt and Session Context.
- **Decisions needing input:** none beyond the spec.
- **Risks:** a `structure.json` written as version 3 cannot be read by an older installed My Air: Folders mode there shows the parse error until it is updated.
- **Out of scope:** moving PW tasks into plans (the user's step 5 of the spec).

## Task 1: model version 3

- [ ] `folders/Plan.kt`: `Plan(goal, milestones)`, `Milestone(id, title, done, tasks)`, `PlanTask(id, title, status, next)`, `PlanStatus` (JSON names lowercase).
- [ ] `Folder.dirs` replaces `project`, `Folder.plan`; `SessionFolders.nextId`; invariants: a directory binds once, no plan folder inside another, M/T ids unique, well-formed and below `nextId`.
- [ ] Plan operations on `SessionFolders` (`addPlan`, `removePlan`, `bind`, `unbind`, `addMilestone`, `addTask`, `editMilestone`, `editTask`, `moveMilestone`, `moveTask`, `deleteItem`), each a no-op on an id another IDE removed.
- [ ] `taskOf(session)`, `planFolderOf(dir)`, `placeOf` counting the task's plan folder.
- [ ] Parse versions 1-3 strictly; write version 3. Tests in `SessionFoldersTest` and `PlanTest`.

## Task 2: MCP

- [ ] `PlanChanges.apply(structure, folderId, changes)`: `set_goal`, `add_milestone`, `add_task`, `update`, `move`, `delete`, all or none.
- [ ] `FolderChanges`: `create` with `dirs` and `plan`, `add_plan`, `bind`, `unbind`.
- [ ] `my_air_plan_get`, `my_air_plan_apply` in `FoldersMcpToolset`; `planJson`; `treeJson` with `dirs` and `plan`.
- [ ] Tests: `PlanChangesTest`, `FolderChangesTest`, `FolderQueriesTest`.

## Task 3: brief's plan task

- [ ] `Brief.planTask`, read by `Brief.next` from `plan_task`; `my_air_set_brief(goal, tasks, plan_task?)` checks the task exists.
- [ ] Tests: `BriefTest` / scanner.

## Task 4: tree

- [ ] `MilestoneRow`, `PlanTaskRow`, `NoTask` nodes; `foldersTree` places sessions by task; renderer; summaries; drops (reorder milestones and tasks, no session onto a task).
- [ ] Menu: Add Plan, Remove Plan, Bind Directory..., Unbind Directory..., Add Milestone, Add Task, Rename..., Delete..., Mark Done / Reopen, Set Status, Edit Next Step...
- [ ] Tests in `SessionTreeTest`.

## Task 5: prompt and Session Context

- [ ] Plan rules in `appendedSystemPrompt`; `SessionSystemPromptTest` names the plan tools.
- [ ] Plan card in Session Context.
- [ ] CLAUDE.md.
