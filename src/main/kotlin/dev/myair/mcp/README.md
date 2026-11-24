# dev.myair.mcp

## Overview

The My Air tools offered to agents through the IDE's MCP server. They cover finding sessions, starting a session, reading and changing folders, plans, plan managers, undo snapshots, session briefs, reviews and canvases. Four toolsets are registered in `src/main/resources/META-INF/my-air-mcp.xml`, which `plugin.xml` loads through an optional `<depends>` on `com.intellij.mcpServer`. Without the MCP server the rest of the plugin still works. The pure parts (paging and time bounds, preset merging, JSON output) are kept apart from the toolsets so they can be tested.

## Interface

- `FoldersMcpToolset.kt`, `class FoldersMcpToolset : McpToolset`:
  - `my_air_sessions_find(since, until, query, folder, unfiled, offset, limit)`
  - `my_air_session_start(prompt, dir, model, effort, add_dirs, worktree, base, branch, plan_task, preset, folder, profile)`
  - `my_air_folders_get()`
  - `my_air_folders_apply(changes)`
  - `my_air_plan_get(dir)`
  - `my_air_plan_apply(plan, changes)`
  - `my_air_plan_set_manager(plan, session)`
  - `my_air_history_list()`
  - `my_air_history_restore(name)`
- `BriefMcpToolset.kt`, `class BriefMcpToolset : McpToolset`:
  - `my_air_set_brief(goal, tasks, plan_task, folder, title)`
- `ReviewMcpToolset.kt`, `class ReviewMcpToolset : McpToolset`, each tool naming the review folder by its id, else its path; get and apply read git on each call, off the EDT:
  - `my_air_review_get(folder)`
  - `my_air_review_apply(folder, changes)`
  - `my_air_review_report(folder, run, commit, findings, questions)`
- `CanvasMcpToolset.kt`, `class CanvasMcpToolset : McpToolset`, each tool but the last naming the canvas folder by its id, else its path (`withCanvas`):
  - `my_air_canvas_show(folder, openui, title)`
  - `my_air_canvas_get(folder)`
  - `my_air_canvas_apply(folder, changes)`
  - `my_air_canvas_language()`
- `CanvasJson.kt`: `resolveCanvasFolder(structure, canvases, folder)`, `data class CanvasCheck(validated, ok, errors, unresolved, orphaned, note, boards)` with `CanvasCheck.of(validated)`, `CanvasCheck.unchecked(note)` and the notes `NO_PAGE`, `TIMEOUT`, `CLOSED`, `INVALID`, `WARNINGS`; `canvasShowJson(folderId, path, frame, check)`, `canvasGetJson(folderId, path, canvas)`.
- `SessionSearch.kt`:
  - `data class SessionPage(total, offset, sessions, snippets)`
  - `fun findSessions(...)`: a `query` also matches a session found in `content`, the conversation index's answer for it (`SessionIndex.search`, run by the tool off the EDT); `snippets` (and `match` in the JSON) give the text around the hit of each session on the page
  - `fun timeBound(text, now, end)`
  - Constants `DEFAULT_LIMIT = 50`, `MAX_LIMIT = 100` and `PROMPT_CHARS = 300`.
- `SessionStart.kt`:
  - `fun sessionStartPreset(saved, preset, dir, model, effort, addDirs, worktree, base, profile): Preset`
  - `fun firstMessage(start, prompt): String`: the configuration's prompt, then the agent's after a space, or either alone; neither throws
  - `fun SessionFolders.requireTask(id): Pair<Folder, PlanTask>`
- `McpJson.kt`, the shapes of the tool results:
  - `startedJson`
  - `SessionPage.toJson`, adding `profile` for a session of another profile than `default`
  - `SessionFolders.treeJson(reviews, canvases)`: `kind` `review` or `canvas` on those folders only
  - `StructureHistory.toJson`
  - `managerSetJson`
  - `SessionFolders.planJson`
  - `SessionFolders.plansJson`

## Invariants

- Class names in `my-air-mcp.xml` are resolved only at runtime. Renaming or moving a toolset requires updating that file.
- All four toolsets set `isExperimental() = false`, since an experimental toolset may be hidden.
- `BriefMcpToolset.isEnabled()` follows `MyAirSettings.briefs`. `folder` and `title` are refused unless `MyAirSettings.fileSessions` is set.
- `FoldersMcpToolset` reads and changes the state of `ClaudeSessionsService` on the EDT (`onEdt`, `withFolders`), where it lives. `my_air_sessions_find` searches the conversation index on `Dispatchers.IO` first; `my_air_session_start` checks directories and builds the arguments outside the EDT and runs git on `Dispatchers.IO`.
  - `withFolders` reports `foldersError` instead of working on an unreadable `structure.json`.
- The tools never ask the user anything. Agent input errors reach the agent as `McpExpectedError`:
  - an `IllegalArgumentException`;
  - a `dev.myair.actions.GitFailure`, whose message carries git's stderr;
  - a snapshot that does not parse, in `my_air_history_restore`.
- `findSessions` validates its input:
  - it refuses `folder` together with `unfiled=true`;
  - `limit` must be in `1..MAX_LIMIT`, and `offset` must be `>= 0`;
  - `since` and `until` are inclusive, and an `until` date covers the whole day (`timeBound`).
- `sessionStartPreset`:
  - throws on a `preset` name that is unknown, and takes a configuration's first model and effort pair (`firstVariant`);
  - takes `profile` when given, else the configuration's;
  - requires `base` whenever the result has a worktree;
  - drops the preset's base when `worktree=false`.
- `my_air_session_start` refuses a `branch` without a worktree, a `folder` with no working or bound directory at or above it, directories that do not exist, an unreadable `presets.json` when a `preset` is named, and a profile not listed (`ProfileStore.byName`; none given: `default`), which it passes to `startNew`.
- `requireTask` lists the open tasks when an id is unknown.
- `planJson` and `plansJson` require a plan folder (`checkNotNull(folder.plan)`). Milestones carry `progress`; tasks are listed with their sessions, newest first, each with its live `status` (null without a process). `manager` is the plan's entry in `plan-managers.json`, null without one. The plan JSON also gives `constraints`, `decisions`, `requirements` (each with its derived `status`) and `review` with its blockers; `my_air_plan_apply` takes the ops of `PlanChanges`, among them `add_requirement` and `add_evidence`. `my_air_plan_get(dir)` also lists plan folders whose `workdir` holds `dir` (`SessionFolders.planFoldersOf`).
- `managerSetJson` adds a `hint` (set the plan's `workdir`) only when the manager's cwd lies outside the plan's `dirs` and `workdir`.
- `my_air_plan_set_manager` refuses, before changing anything, what `managerRefusal` (`planview/PlanFiles.kt`) names: a session managing a plan already, or a review, unknown (neither scanned nor running here), run by a background job, running outside this IDE, or still starting here.

## Side Effects

- `my_air_folders_apply` and `my_air_plan_apply` write `structure.json` through `ClaudeSessionsService.updateFolders(ChangeSource.MCP)`. `my_air_history_restore` writes it with `ChangeSource.RESTORE`. Each write snapshots the replaced version (see `src/main/kotlin/dev/myair/folders/StructureStore.kt`).
- `my_air_set_brief` stores nothing about the session. The call and its result land in the calling session's transcript. It only creates a missing folder, through `updateFolders(ChangeSource.BRIEF)` with `briefFolder`.
- `my_air_session_start`:
  - reads `~/.my-air/presets.json` (`PresetStore.INSTANCE`) when a `preset` is named;
  - for a worktree, runs `git` on `Dispatchers.IO` (`Git.repo`, `createWorktree`), which creates a worktree and possibly a branch, then records the base (`rememberBase`);
  - opens a new session tab in the calling project (`ClaudeSessionOpener.startNew`), and so starts a `claude` process.
- `my_air_review_apply` stores a changed base as `branch.<branch>.myAirBase` on `Dispatchers.IO` (`storeBase`, refused with its reason), then writes `review.json` (`ReviewService.update`), then calls `ActiveSession.baseStored()`.
- `my_air_canvas_show` writes `canvas.json` (`CanvasService.update` with `addFrame`) first, so the frame is stored whatever the check says, also an invalid one; then it has a canvas page of this IDE (`CanvasRenderers.validator`) parse the document, waiting at most 5 s off the EDT. Without a page, on the timeout or when the page's tab closes first, the result says `validated: false` with a `note`. `my_air_canvas_apply` writes `canvas.json` through `CanvasService.update` (`CanvasChanges.apply`, all or none). The canvas tools do not know their caller; any session may write to any canvas.
- `my_air_review_report` refuses a `commit` that is not 7 to 40 lowercase hex characters, then writes `review.json` (`ReviewService.update`).
- `my_air_plan_set_manager`:
  - files the session in the plan folder (`updateFolders(ChangeSource.MCP)`) and stores it in `~/.my-air/plan-managers.json` (`PlanFiles.adopt`), which the folder history does not cover;
  - detaches the previous manager's process (`SessionTerminals.detach`), rebinds every open plan tab of the plan in this IDE (`PlanEditor.showManager`), opens the plan tab in the calling project, sends a running session `planPrompt(manager = true)` (without a process, it goes after `--` as the first prompt of the manager's next start) and closes its own tab.
- The read tools have no side effects: `my_air_sessions_find`, `my_air_folders_get`, `my_air_plan_get`, `my_air_history_list`, `my_air_review_get`, `my_air_canvas_get` and `my_air_canvas_language`. `my_air_history_list` parses each snapshot file; `my_air_review_get` runs git.

## Verification

- Run: `./gradlew test --tests 'dev.myair.mcp.*'`
