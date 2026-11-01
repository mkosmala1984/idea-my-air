# Project plan across sessions

## TL;DR

- **Goal:** plans of a project that every session sees and updates: each plan's description, its milestones, their tasks with status and next step, and which sessions worked on each task. It replaces tracking in Jira PW, which the user will drop (user's decision).
- **A plan folder is a new kind of folder in the session tree (user's decision):** a folder with a plan, bound to one or more directories (several repositories, user's decision). Under it: milestones › tasks › the sessions of each task, then a `no task` group with its other sessions (user's decision). No separate plan window (user's decision); a Plan card in Session Context stays.
- **Names (user's decision):** "project" keeps meaning a directory, as in Projects mode and `claude`; the new folder kind is a "plan" folder; a brief names its plan task with `plan_task`, apart from its own `tasks`.
- **Store:** the plan lives in its folder in `structure.json` (`~/.my-air`, user's decision), so folders and plans share one store, one history and undo. Format version 3.
- **Any number of plans per directory (user's decision):** plan folders inside the folder bound to a directory are all its plans.
- **Descriptions and prompt (user's decision):** a plan, a milestone and a task each have a name and an editable Markdown description; a session opened in a plan gets the plan's and its task's names and descriptions in its system prompt (`--append-system-prompt-file`). Assumed: the description replaces the plan's `goal`; a resumed session gets the context only after its next compaction, since claude keeps a recorded prompt (`--system-prompt-snapshot`, left on).
- **Tools:** `my_air_plan_get(dir)` returns every plan of the directory, `my_air_plan_apply(plan, changes)` changes one, named by its folder path; `my_air_folders_apply` adds plans and binds directories; `my_air_set_brief` gets an optional `plan_task`.
- **Assumed, change if wrong:** the agent adds tasks and changes statuses without asking (as the PW skill does); deleting or moving milestones and tasks needs the user's ok. Statuses are PW's: pending, in progress, waiting (for the user), blocked, done, canceled, plus `next`. Task ids are unique across all plans (`T12`). A session naming a task shows under it even when filed elsewhere.
- **Risks:** two IDEs changing `structure.json` in the same milliseconds lose one change (as today); a session's task comes only from its brief, so an agent that never names one leaves it under `no task`.
- **Out of scope:** project-nexus (stays separate, user's decision), requirements, ADR pages, sync with Jira.

## Why

A brief belongs to one session and ends with it. Work on a project spans many sessions, in parallel, over days and across repositories; today its state lives in Jira PW, written by a skill through a REST wrapper, slow and outside the IDE. My Air already files every session in the user's folders, so a folder can hold the plan and show which sessions served each task.

## Names

| Thing | Tree row | Code and MCP |
|---|---|---|
| Directory sessions run in | unchanged (Projects mode, a bound folder's grey path) | `Path`, `ClaudeSession.project`, unchanged |
| Folder with a plan | the folder's name, a plan icon, `done/total` on the right | `Folder.plan != null`; menu "Add Plan" |
| Directories of a folder | grey after the name, as today | `Folder.dirs: [String]`, replacing `project` |
| Milestone | `M2 Import  1/3` | `Milestone`, `M<n>` |
| Plan task | status icon, `T4 Mapowanie pól`, `next` in grey | `Task`, `T<n>` |
| Brief task | unchanged | `BriefTask` and the brief's `tasks`, unchanged |
| A brief's plan task | — | `my_air_set_brief(…, plan_task: "T4")` |
| Plan sessions without a task | `no task`, grey italic like `recent` and `sessions` | `NoTask`, an object like `RecentSessions` |

"Task" alone in this spec means a plan task.

## Model

`structure.json` version 3:

```
Folder(id, name, folders, items, dirs: [String], plan: Plan?)   // dirs replaces project; plan: a plan folder
Plan(description: String?, milestones: [Milestone])          // its name is the folder's
Milestone(id: "M<n>", title, done: Boolean, tasks: [Task], description: String?)   // MCP spells done as status open|done
Task(id: "T<n>", title, status, next: String?, description: String?)   // descriptions are Markdown, written only when set
status: pending | in_progress | waiting | blocked | done | canceled
SessionFolders(folders, nextId: Int)                            // allocates M/T ids, never reused
```

- `dirs`: every folder may bind several directories (version 2's `project` reads as a list of one). A directory still binds to one folder; `projectFolder(cwd)` keeps picking the most specific binding.
- A plan folder is a folder with `plan != null`. It cannot lie inside another plan folder; ordinary subfolders inside it are allowed.
- The plans of a directory (`planFoldersOf`): the plan folder its bound folder is or lies in; else every plan folder inside its bound folder, in tree order. So a directory gets several plans by binding an ordinary folder and adding plan folders under it.
- Ids are unique across the whole structure and never reused, so a brief keeps naming the same task after others are deleted, and `T12` alone finds its plan.
- Parsing stays strict: an unknown field, status or op throws.

## Where a session shows

In order: under the task its latest brief names in `plan_task`, when that task exists; else the folder it is filed in; else the folder bound to its project; else `sessions`. A session filed by hand in a folder but naming a task shows under the task. Inside a plan folder, its sessions without a task go to the `no task` group below the milestones.

## MCP tools

- `my_air_plan_get(dir)`: `dir` is any directory (the agent passes its cwd). Returns a JSON array of the directory's plans, each with the folder's path, its directories, goal, milestones and tasks, and for each task the sessions whose brief names it (id, title, last message, live status). No plan: says so and names the op that adds one.
- `my_air_plan_apply(plan, changes)`: `plan` is a folder path from `my_air_plan_get`; a JSON array applied in order, all or none, returning the plan: `set_description`, `add_milestone {title, description?, index?}`, `add_task {milestone, title, description?, index?}`, `update {id, title?, description?, status?, next?}`, `move {id, milestone?, index}`, `delete {id}` (a milestone only when its tasks are all done or canceled). One snapshot per call, source `mcp`.
- `my_air_folders_apply`: `create` takes `dirs` (a list) and `plan: true`; new ops `add_plan {path}`, `bind {path, dir}` and `unbind {dir}`. Its rule (only what the user approved) stays.
- `my_air_set_brief(goal, tasks, plan_task?)`: `plan_task` must exist in the structure; otherwise the call fails listing the open tasks of every plan.
- `my_air_history_*` covers plans with no change, since they are in `structure.json`.

## Agent's rules

Added to `appendedSystemPrompt`, one sentence each, naming no session or project:

1. At the start, call `my_air_plan_get` with the working directory; when there is a plan, tell the user which task fits the request or propose a new one, and name it as `plan_task` in `my_air_set_brief`.
2. Keep the task's status and `next` current with `my_air_plan_apply`; `waiting` when the user has to act, `done` only when the work is finished.
3. Add tasks freely; delete or move milestones and tasks only after the user agreed.

## Plan context in the system prompt

A session tab whose session is in a plan adds `--append-system-prompt-file <file>` next to the shared one-line `--append-system-prompt`: the plan's name, folder path (the `plan` of `my_air_plan_apply`) and description, and when a task is known, the task's and its milestone's names and descriptions. Statuses and next steps stay out, since claude keeps the prompt until a compaction. The session's plan: the task its brief names; else, for a new session started from a plan, milestone or task row, that plan (the session is filed in the plan folder) and task; else the plan folder it shows in. The file lives in `~/.my-air/prompts/<IDE pid>-<uuid>.md` until the tab closes.

## UI

- **Tree, Folders mode:** a plan folder has a plan icon and `done/total` tasks on the right. Children: its subfolders, then milestones (a ✓ when done, `done/total`), tasks (status icon, id and title, `next` in grey), the sessions of each task with today's session row, then `no task`. The urgency dot of a group counts the sessions below it, as today.
- **Menu:** on a folder, "Add Plan" / "Bind Directory..."; on a plan folder, "Add Milestone", "Remove Plan" (only when it has no milestones); on a milestone, "Add Task", rename, mark done, delete; on a task, set status, edit next step, rename, delete. "Edit Description..." on a plan folder, milestone and task (Markdown, shown rendered in tooltips and the Plan card). "New Claude Session" on a plan row starts the session in that plan (and task). Drag and drop reorders milestones and tasks. Every change goes through `StructureStore.update` with source `ui`.
- **Session Context:** a Plan card above the brief: plan folder › milestone › task, its status and `next`, and the other sessions of the task (a click opens one through `ClaudeSessionOpener.open`).
- A task `in_progress` with no live session is marked, since its session may have ended without updating it.

## Tests

- `SessionFoldersTest`: version 2 to 3 (`project` to `dirs`), several directories, a plan folder inside another rejected, id allocation, strict parsing.
- `PlanChangesTest`: each op, its failures, all or none.
- `FolderChangesTest`: `create` with `plan`, `add_plan`, `bind`, `unbind`.
- Scanner test: `plan_task` read from an accepted brief, ignored from a rejected one.
- Placement test: task over filed over bound, `no task`.
- `SessionSystemPromptTest`: the plan tools named.

## Order

1. Model version 3 (`dirs`, `plan`, `nextId`), placement, `my_air_plan_get`, `my_air_plan_apply`, folder ops, with tests.
2. `plan_task` in the brief and the scanner.
3. Tree nodes and menu for plan folders, milestones, tasks and `no task`.
4. Rules in `appendedSystemPrompt`; Plan card in Session Context.
5. Move the user's open PW tasks into plan folders by hand, then stop the `tracking-work-in-pw` skill for them (outside this repository).
