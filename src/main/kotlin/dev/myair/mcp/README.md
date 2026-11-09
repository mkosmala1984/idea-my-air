# dev.myair.mcp

## Overview

The My Air tools offered to agents through the IDE's MCP server. They cover finding sessions, starting a session, and reading and changing folders, plans, undo snapshots and session briefs. Two toolsets are registered in `src/main/resources/META-INF/my-air-mcp.xml`, which `plugin.xml` loads through an optional `<depends>` on `com.intellij.mcpServer`. Without the MCP server the rest of the plugin still works. The pure parts (paging and time bounds, preset merging, JSON output) are kept apart from the toolsets so they can be tested.

## Interface

- `FoldersMcpToolset.kt`, `class FoldersMcpToolset : McpToolset`:
  - `my_air_sessions_find(since, until, query, folder, unfiled, offset, limit)`
  - `my_air_session_start(prompt, dir, model, effort, add_dirs, worktree, base, branch, plan_task, preset, folder)`
  - `my_air_folders_get()`
  - `my_air_folders_apply(changes)`
  - `my_air_plan_get(dir)`
  - `my_air_plan_apply(plan, changes)`
  - `my_air_history_list()`
  - `my_air_history_restore(name)`
- `BriefMcpToolset.kt`, `class BriefMcpToolset : McpToolset`:
  - `my_air_set_brief(goal, tasks, plan_task, folder, title)`
- `SessionSearch.kt`:
  - `data class SessionPage(total, offset, sessions)`
  - `fun findSessions(...)`
  - `fun timeBound(text, now, end)`
  - Constants `DEFAULT_LIMIT = 50`, `MAX_LIMIT = 100` and `PROMPT_CHARS = 300`.
- `SessionStart.kt`:
  - `fun sessionStartPreset(saved, preset, dir, model, effort, addDirs, worktree, base): Preset`
  - `fun SessionFolders.requireTask(id): Pair<Folder, PlanTask>`
- `McpJson.kt`, the shapes of the tool results:
  - `startedJson`
  - `SessionPage.toJson`
  - `SessionFolders.treeJson`
  - `StructureHistory.toJson`
  - `SessionFolders.planJson`
  - `SessionFolders.plansJson`

## Invariants

- Class names in `my-air-mcp.xml` are resolved only at runtime. Renaming or moving a toolset requires updating that file.
- Both toolsets set `isExperimental() = false`, since an experimental toolset may be hidden.
- `BriefMcpToolset.isEnabled()` follows `MyAirSettings.briefs`. `folder` and `title` are refused unless `MyAirSettings.fileSessions` is set.
- `FoldersMcpToolset` runs every tool body on the EDT (`onEdt`, `withFolders`), where the state of `ClaudeSessionsService` lives.
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
  - throws on a `preset` name that is unknown;
  - requires `base` whenever the result has a worktree;
  - drops the preset's base when `worktree=false`.
- `my_air_session_start` refuses a `branch` without a worktree and a `folder` with no working or bound directory at or above it.
- `requireTask` lists the open tasks when an id is unknown.
- `planJson` and `plansJson` require a plan folder (`checkNotNull(folder.plan)`). Tasks are listed with their sessions, newest first.

## Side Effects

- `my_air_folders_apply` and `my_air_plan_apply` write `structure.json` through `ClaudeSessionsService.updateFolders(ChangeSource.MCP)`. `my_air_history_restore` writes it with `ChangeSource.RESTORE`. Each write snapshots the replaced version (see `src/main/kotlin/dev/myair/folders/StructureStore.kt`).
- `my_air_set_brief` stores nothing about the session. The call and its result land in the calling session's transcript. It only creates a missing folder, through `updateFolders(ChangeSource.BRIEF)` with `briefFolder`.
- `my_air_session_start`:
  - reads `~/.my-air/presets.json` (`PresetStore.INSTANCE`) when a `preset` is named;
  - for a worktree, runs `git` on `Dispatchers.IO` (`Git.repo`, `createWorktree`), which creates a worktree and possibly a branch, then records the base (`rememberBase`);
  - opens a new session tab in the calling project (`ClaudeSessionOpener.startNew`), and so starts a `claude` process.
- The read tools have no side effects: `my_air_sessions_find`, `my_air_folders_get`, `my_air_plan_get` and `my_air_history_list`. `my_air_history_list` parses each snapshot file.

## Verification

- Run: `./gradlew test --tests 'dev.myair.mcp.*'`
