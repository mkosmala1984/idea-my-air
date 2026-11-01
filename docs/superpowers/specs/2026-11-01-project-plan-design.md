# Project plan across sessions

## TL;DR

- **Goal:** one plan per project that every session sees and updates: the project's goal, its milestones, their tasks with status and next step, and which sessions worked on each task. It replaces tracking in Jira PW, which the user will drop (user's decision).
- **Store (user's decision):** `~/.my-air/plans/<hash of the main checkout>.json`, one per repository (worktrees share it), shared by every IDE, with snapshots for undo, built like `structure.json` (`StructureStore`, `StructureHistory`).
- **Tools:** `my_air_plan_get(project)` and `my_air_plan_apply(project, changes)` (all or none, like `my_air_folders_apply`); `my_air_set_brief` gets an optional `task`.
- **Session to task:** read from the transcript, as the brief is: the latest accepted `my_air_set_brief` with `task` names the session's task. The plan file holds no session ids.
- **Agent's rules** (in `appendedSystemPrompt`): read the plan at the start, pick or create the task with the user, name it in the brief, keep its status and next step current. **Assumed, change if wrong:** the agent adds tasks and changes statuses without asking (as the PW skill does); deleting or moving milestones and tasks needs the user's ok.
- **UI:** a Plan card in Session Context (milestone, task, status, next step, other sessions of the task); a Project Plan tool window with milestones › tasks › sessions.
- **Risks:** a plan edited by two IDEs in the same milliseconds loses one change (as folders do); agents may not follow the rules, which only a prompt enforces.
- **Out of scope:** project-nexus (stays separate, user's decision), requirements, ADR pages, sync with Jira, plans for directories outside a git repository.

## Why

A brief belongs to one session and ends with it. Work on a project spans many sessions, in parallel and over days; today its state lives in Jira PW, written by a skill through a REST wrapper, slow and outside the IDE. My Air already sees every session of a project, so it can hold the plan and show which sessions served each task.

## Model

```
Plan(project: String, goal: String?, milestones: [Milestone])          // project: the main checkout, absolute
Milestone(id: "M<n>", title, status: open|done, tasks: [Task])
Task(id: "T<n>", title, status, next: String?)
status: pending | in_progress | waiting | blocked | done | canceled    // waiting: for the user; blocked: on someone else
```

Ids are numbered per plan and never reused, so a session's brief keeps naming the same task after others are deleted. `next` is one sentence. The file carries `version` and is parsed strictly: an unknown field or status throws.

## Store

- `PlanStore(file)`: `read()`, `update(source, op)`, re-reading the file before each change and writing atomically (`writeAtomically`), the replaced version going to `plans/history/<hash>-<time>-<source>.json` first. Same shape as `StructureStore`; share the history class if its naming allows.
- `hash`: of the main checkout's absolute path as `Git.mainRoot` gives it, so a path given from a worktree resolves to the same plan.

## MCP tools

- `my_air_plan_get(project)`: `project` is any directory in the repository (the agent passes its cwd). Returns the plan, and for each task the sessions whose brief names it: id, title, last message, live status. A repository without a plan returns an empty plan with its main checkout.
- `my_air_plan_apply(project, changes)`: a JSON array applied in order, all or none, returning the plan after it: `set_goal`, `add_milestone {title, index?}`, `add_task {milestone, title, index?}`, `update {id, title?, status?, next?}`, `move {id, milestone?, index}`, `delete {id}` (a milestone with tasks only when they are all done or canceled).
- `my_air_set_brief(goal, tasks, task?)`: `task` must name a task of the session's plan; the tool checks it against the plan of the project the IDE resolves from the call (fails with the list of task ids otherwise).
- History: extend `my_air_history_list` / `my_air_history_restore` with the plan snapshots, or add `my_air_plan_history_*`; decide in the plan by what the folder history code allows without branching.

## Session to task

`ClaudeSessionScanner` reads `task` from accepted `my_air_set_brief` calls into `Brief.task`. A session's task is its latest brief's. `ClaudeSessionsService` groups sessions by `(project, task)` for the tool and the UI. `/clear` starts a new session id: it has no task until its agent names one.

## Agent's rules

Added to `appendedSystemPrompt`, one line each, with no value of one session:

1. At the start, call `my_air_plan_get` with the working directory; tell the user which task fits the request, or propose a new one, and name it in `my_air_set_brief`.
2. Keep the task's status and `next` current with `my_air_plan_apply`; `waiting` when the user has to act, `done` only when the work is finished.
3. Add tasks freely; delete or move milestones and tasks only after the user agreed.

## UI

- **Session Context:** a Plan card above the brief: milestone › task, its status and `next`, and the other sessions of the task (a click opens one through `ClaudeSessionOpener.open`).
- **Project Plan tool window:** the project of the selected tab (or chosen in a combo): goal, milestones with `done/total`, tasks with status icon and `next`, under each task its sessions with the status icons of the tree. Hand edits (rename, status, reorder) go through `PlanStore.update` with source `ui`.
- A task `in_progress` with no live session is marked, since its session may have ended without updating it.

## Tests

- `PlanTest`: parse/serialize round trip, strict parsing, id allocation, each change op and its failures, all-or-none.
- `PlanStoreTest`: re-read before update, snapshot before write (real files, as `StructureStore`'s test).
- Scanner test: `task` read from an accepted brief, ignored from a rejected one.
- `SessionSystemPromptTest`: the plan tools named.

## Order

1. Model, store, `my_air_plan_get`, `my_air_plan_apply`, with tests.
2. `task` in the brief, scanner, grouping in the service.
3. Rules in `appendedSystemPrompt`; Plan card in Session Context.
4. Project Plan tool window.
5. Move the user's open PW tasks of `idea-my-air` into the plan by hand, then stop the `tracking-work-in-pw` skill for repositories with a plan (outside this repository).
