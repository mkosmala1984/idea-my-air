# Project plan across sessions

## TL;DR

- **Goal:** one plan per project that every session sees and updates: the project's goal, its milestones, their tasks with status and next step, and which sessions worked on each task. It replaces tracking in Jira PW, which the user will drop (user's decision).
- **A project is a new kind of folder in the session tree (user's decision):** a folder with a plan, bound to one or more directories (several repositories, user's decision). Under it: milestones › tasks › the sessions of each task, then a "No task" group with its other sessions (user's decision). No separate plan window (user's decision); a Plan card in Session Context stays.
- **Store:** the plan lives in its folder in `structure.json` (`~/.my-air`, user's decision), so folders and plans share one store, one history and undo. Format version 3.
- **Tools:** `my_air_plan_get(dir)` and `my_air_plan_apply(dir, changes)` for milestones and tasks; `my_air_folders_apply` creates project folders and binds directories; `my_air_set_brief` gets an optional `task`.
- **Session to task:** read from the transcript, as the brief is. Task ids are unique across all plans (`T12`), so a brief's `task` needs no project.
- **Assumed, change if wrong:** the agent adds tasks and changes statuses without asking (as the PW skill does); deleting or moving milestones and tasks needs the user's ok. Statuses are PW's: pending, in progress, waiting (for the user), blocked, done, canceled, plus `next`.
- **Risks:** two IDEs changing `structure.json` in the same milliseconds lose one change (as today); a session's task comes only from its brief, so an agent that never names one leaves it under "No task".
- **Out of scope:** project-nexus (stays separate, user's decision), requirements, ADR pages, sync with Jira.

## Why

A brief belongs to one session and ends with it. Work on a project spans many sessions, in parallel, over days and across repositories; today its state lives in Jira PW, written by a skill through a REST wrapper, slow and outside the IDE. My Air already files every session in the user's folders, so a folder can hold the plan and show which sessions served each task.

## Model

`structure.json` version 3:

```
Folder(id, name, folders, items, projects: [String], plan: Plan?)   // projects replaces project; plan: a project folder
Plan(goal: String?, milestones: [Milestone])
Milestone(id: "M<n>", title, status: open|done, tasks: [Task])
Task(id: "T<n>", title, status, next: String?)
status: pending | in_progress | waiting | blocked | done | canceled
SessionFolders(folders, nextId: Int)                                // allocates M/T ids, never reused
```

- `projects`: every folder may bind several directories (version 2's `project` reads as a list of one). A directory still binds to one folder; `projectFolder(cwd)` keeps picking the most specific binding.
- A project folder is a folder with `plan != null`. It cannot lie inside another project folder; ordinary subfolders inside it are allowed.
- Ids are unique across the whole structure and never reused, so a brief keeps naming the same task after others are deleted, and `T12` alone finds its plan.
- Parsing stays strict: an unknown field, status or op throws.

## Where a session shows

In order: under the task its latest brief names, when that task exists; else the folder it is filed in; else the folder bound to its project; else `sessions`. A session filed by hand in a folder but naming a task shows under the task. Inside a project folder, its sessions without a task go to the "No task" group below the milestones.

## MCP tools

- `my_air_plan_get(dir)`: `dir` is any directory (the agent passes its cwd); the project folder is the one `projectFolder(dir)` finds, or the project folder above it. Returns the folder's path, its directories, goal, milestones and tasks, and for each task the sessions whose brief names it (id, title, last message, live status). No project folder: says so and names the tool that creates one.
- `my_air_plan_apply(dir, changes)`: a JSON array applied in order, all or none, returning the plan: `set_goal`, `add_milestone {title, index?}`, `add_task {milestone, title, index?}`, `update {id, title?, status?, next?}`, `move {id, milestone?, index}`, `delete {id}` (a milestone only when its tasks are all done or canceled). One snapshot per call, source `mcp`.
- `my_air_folders_apply`: `create` takes `projects` (a list) and `plan: true`; new ops `bind {path, project}` and `unbind {project}`. Its rule (only what the user approved) stays.
- `my_air_set_brief(goal, tasks, task?)`: `task` must exist in the structure; otherwise the call fails with the open tasks of the plan found from the IDE's project.
- `my_air_history_*` covers plans with no change, since they are in `structure.json`.

## Agent's rules

Added to `appendedSystemPrompt`, one sentence each, naming no session or project:

1. At the start, call `my_air_plan_get` with the working directory; when there is a plan, tell the user which task fits the request or propose a new one, and name it in `my_air_set_brief`.
2. Keep the task's status and `next` current with `my_air_plan_apply`; `waiting` when the user has to act, `done` only when the work is finished.
3. Add tasks freely; delete or move milestones and tasks only after the user agreed.

## UI

- **Tree, Folders mode:** a project folder has its own icon and `done/total` tasks on the right. Children: its subfolders, then milestones (a ✓ when done, `done/total`), tasks (status icon, title, `next` in grey), the sessions of each task with today's session row, then "No task". The urgency dot of a group counts the sessions below it, as today.
- **Menu:** on a folder, "Convert to Project" / "Bind Directory..."; on a project folder, "Add Milestone"; on a milestone, "Add Task", rename, mark done, delete; on a task, set status, edit next step, rename, delete. Drag and drop reorders milestones and tasks. Every change goes through `StructureStore.update` with source `ui`.
- **Session Context:** a Plan card above the brief: project › milestone › task, its status and `next`, and the other sessions of the task (a click opens one through `ClaudeSessionOpener.open`).
- A task `in_progress` with no live session is marked, since its session may have ended without updating it.

## Tests

- `SessionFoldersTest`: version 2 to 3, several directories, a project folder inside another rejected, id allocation, strict parsing.
- `PlanChangesTest`: each op, its failures, all or none.
- `FolderChangesTest`: `create` with `plan`, `bind`, `unbind`.
- Scanner test: `task` read from an accepted brief, ignored from a rejected one.
- Placement test: task over filed over bound, "No task".
- `SessionSystemPromptTest`: the plan tools named.

## Order

1. Model version 3 (`projects`, `plan`, `nextId`), placement, `my_air_plan_get`, `my_air_plan_apply`, folder ops, with tests.
2. `task` in the brief and the scanner.
3. Tree nodes and menu for project folders, milestones and tasks.
4. Rules in `appendedSystemPrompt`; Plan card in Session Context.
5. Move the user's open PW tasks into project folders by hand, then stop the `tracking-work-in-pw` skill for them (outside this repository).
