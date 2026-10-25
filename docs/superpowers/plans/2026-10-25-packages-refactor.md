# Packages and smaller files

## TL;DR

- **Goal:** split the flat `dev.myair` (24 files, 2.9k lines) into feature packages and break up the files that mix unrelated things, with no change in behaviour.
- **Approach:** 9 packages by feature, not by layer. A move is `git mv` + `package` line + imports, one task per package, each closed by `./gradlew clean test buildPlugin`. The three mixed files get split: `SessionsToolWindowFactory.kt` (600 lines → 7 files), `ClaudeSessionEditor.kt` (8 declarations → 4 files), `ClaudeSessionsService.kt` (board and tab re-targeting moved out).
- **Decisions needing your input:**
  1. Packages by feature (`folders`, `toolwindow`, `tabs`...) as below, or by layer (`model`, `store`, `ui`)? Recommendation: by feature, because every change so far (folders, taskbar, worktree) touched one feature across layers.
  2. Moving private helpers across packages makes them `internal`. Fine?
  3. Is splitting `ClaudeSessionsService` in scope? It is the only step with real behavioural risk (refresh order, listener timing). Without it, the refactor is moves only.
  4. One commit per task (11), or one squashed commit?
- **Risks:** `plugin.xml` and `my-air-mcp.xml` name classes by FQCN, and a typo there shows only at runtime. Task 11 checks this by starting the sandbox. Persisted state keeps its keys: `PropertiesComponent` keys are strings, `MyAirSettings` uses `@State(name)`, and Gson writes field names, so no package name lands in a file.
- **Out of scope:** renames of classes and functions, logic changes, `taskbar/` (.NET), new tests beyond moving the existing ones.

## Target layout

| Package | Files (new ones marked *) | From |
|---|---|---|
| `dev.myair.claude` | `ClaudeSession.kt`*, `ClaudeSessionScanner.kt`, `LiveSessionRegistry.kt`, `ClaudeHomeWatcher.kt` | `ClaudeSession` leaves `ClaudeSessionScanner.kt` |
| `dev.myair.store` | `AtomicFiles.kt`* (`writeAtomically`, `rename`, Win32 constants), `TabStore.kt`, `PinnedSessions.kt`, `Seen.kt` | `writeAtomically` leaves `TabStore.kt`; every store uses it |
| `dev.myair.folders` | `SessionFolders.kt`, `StructureStore.kt`* (`ChangeSource`, `StructureStore`), `StructureHistory.kt`, `FolderChanges.kt` | the store leaves `SessionFolders.kt` |
| `dev.myair.mcp` | `FoldersMcpToolset.kt`, `SessionSearch.kt`* (`findSessions`, `timeBound`, `SessionPage`, limits), `McpJson.kt`* (`toJson`, `treeJson`, history JSON) | `FolderQueries.kt` split into query and JSON |
| `dev.myair.service` | `ClaudeSessionsService.kt`, `TaskbarBoard.kt`, `BoardWriter.kt`* (`updateBoard`, board file, unread), `TabRetargeting.kt`* (`retargetTabs`, `retarget`) | decision 3 |
| `dev.myair.editor` | `ClaudeSessionVirtualFile.kt`* (file, tab title and icon providers), `ClaudeSessionEditor.kt` (provider + editor), `ClaudeSessionOpener.kt`* (`ClaudeSessionOpener`, `FolderTarget`, `projectBase`), `SessionIcons.kt`* (`SessionStatus.icon`, `UNREAD_ICON`) | `ClaudeSessionEditor.kt` split |
| `dev.myair.tabs` | `SessionHosts.kt`, `TabsSync.kt` | moves only |
| `dev.myair.actions` | `NewClaudeSessionAction.kt`* , `NewClaudeSessionInWorktreeAction.kt`, `FocusClaudeSessionAction.kt`, `Worktrees.kt` (pure parts), `Git.kt`* (`GitRepo`, `Git`) | `NewClaudeSessionAction` leaves the editor file; `Git` leaves `Worktrees.kt` |
| `dev.myair.toolwindow` | see below | `SessionsToolWindowFactory.kt` split |
| `dev.myair` | `MyAirSettings.kt` | stays: app-wide setting |

`dev.myair.toolwindow`, from the 600-line file:

| File | Holds | ~lines |
|---|---|---|
| `SessionsToolWindowFactory.kt` | the factory | 10 |
| `SessionsPanel.kt` | layout, toolbar, render, open, subscriptions | 250 |
| `FolderEditing.kt` | `newFolder`, `renameFolder`, `deleteFolder`, `askName`, `FolderAction` as a class taking the panel's tree and service | 80 |
| `FolderDrop.kt` | `TransferHandler` | 45 |
| `DropLineTree.kt` | `DropLineTree`, `insertLine` | 50 |
| `PinMarks.kt` | `Mark`, `PIN`, `SETTLE`, `PinsToggle`, `TrailerList` | 50 |
| `SessionRenderers.kt` | `appendSession`, `PinnedRenderer`, `SessionRenderer` | 65 |
| `SessionTree.kt`, `SessionTrailer.kt` | moved as they are | |

Tests move into the package of the class they test (`src/test/kotlin/dev/myair/<pkg>/`).

## Tasks

| Task | Status | Commit |
|---|---|---|
| 1 | done | `0886fd2` |
| 2 | done | `5d0cf99` |
| 3 | done | `316289c` |
| 4 | done | `c445fb5` |
| 5 | done | `ced1bf9` |
| 6 | done | `9e10f29` |
| 7 | done | `13eab08` |
| 8 | done | `c8a38b6` |
| 9 | done | `8dba70a` |
| 10 | done | `b197f14` |
| 11 | blocked: build, 111/0 tests, XML FQCNs and plugin load in the sandbox checked; clicking through the UI needs a person |  |

Each task: `git mv` the files, fix the `package` lines and imports, make the private declarations used across packages `internal`, update the FQCNs in `plugin.xml` / `my-air-mcp.xml`, run `./gradlew clean test buildPlugin`, then commit. Use `clean`: incremental Kotlin compilation left stale classes after a signature change on 2026-10-25 (`NoSuchMethodError` in tests).

Leaves first, so each task only adds imports to files not yet moved:

- [x] **1. `claude`**: split `ClaudeSession` out of the scanner, move the 4 files and their 4 tests.
- [x] **2. `store`**: extract `AtomicFiles.kt`, move the stores and `TabStoreTest`, `PinnedSessionsTest`, `SeenTest`.
- [x] **3. `folders`**: extract `StructureStore.kt`, move the model, history and changes, plus their tests.
- [x] **4. `mcp`**: split `FolderQueries.kt`, move the toolset, update `my-air-mcp.xml`.
- [x] **5. `tabs`**: move `SessionHosts`, `TabsSync` (+ listeners in `plugin.xml`).
- [x] **6. `editor`**: split `ClaudeSessionEditor.kt` into the 4 files, update `plugin.xml`.
- [x] **7. `actions`**: move the actions, split `Git` out of `Worktrees.kt`, update `plugin.xml`; `GitTest`, `WorktreesTest`.
- [x] **8. `toolwindow`**: split the panel file as in the table. `SessionsPanel` passes itself or narrow lambdas to `FolderEditing` and `FolderDrop`. No logic changes. `InsertLineTest`, `SessionTreeTest`, `ShortAgeTest` move with it.
- [x] **9. `service`**: move `ClaudeSessionsService`, `TaskbarBoard` (decision 3: extract `BoardWriter` and `TabRetargeting`; `ClaudeSessionsServiceTest` tests `retarget` and moves with it).
- [x] **10. Docs**: update the file and class names in `CLAUDE.md` ("Where the data comes from", "How the pieces fit").
- [ ] **11. Proof**: `./gradlew clean test buildPlugin` with the same test count as before (111). Check every FQCN in both XML files: `grep -o 'dev\.myair\.[A-Za-z.]*'` against the compiled classes in `build/classes`. Start the sandbox, then check: the tool window opens; Folders mode, drag and drop, New Claude Session; Settings › Tools › My Air; the MCP tools are listed. `idea.log` shows no `ClassNotFoundException` / `PluginException`.

## Why not more

- `ClaudeSessionScanner.kt` (183) and `SessionFolders.kt` (190) stay whole: each is one class with one job, and its size is its parsing or its tree operations.
- No interfaces or DI introduced for the stores: tests already use the real classes on temp dirs, which is what makes them worth having.
