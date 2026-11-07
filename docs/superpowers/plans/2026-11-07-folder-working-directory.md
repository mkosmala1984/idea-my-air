# Folder Working Directory Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `9199fcd1db` |
| 2 | done | `b0ec9f1512` |
| 3 | done | `3b8daa85ab` |
| 4 | done | `deb79dd613` |
| 5 | done | `1e250cb829` |

## TL;DR

- **Goal:** any folder can name a working directory (`Folder.workdir`) that new sessions started from it and its subfolders run in, worktrees included, without binding it.
- **Approach:** one pure resolver (`startFolder`: nearest folder up the path with `workdir` or bound dirs, `workdir` first) used by the tree menu, the tooltip and `my_air_session_start`; a session started from a folder row is filed in that folder (`startNew(folderId = ...)`).
- **Decisions needing input:** none; all settled in the spec.
- **Risks:** filing on start changes where sessions started from bound folders land (agreed); `structure.json` with a `workdir` breaks builds before this one, loudly.
- **Out of scope:** prompt panel, presets, Same Settings, bind semantics.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A folder of the session tree carries an optional working directory used to start sessions and worktrees from it and its subfolders.

**Architecture:** `Folder.workdir: String?` stored in `structure.json` v4 (written only when set). `SessionFolders.startFolder` picks the deciding folder from an ancestry; `Folder.startDir` its directory. Tree actions, renderer and MCP all go through these two.

**Tech Stack:** Kotlin, IntelliJ Platform 263, Gson, JUnit 5 (`./gradlew test`).

**Spec:** `docs/superpowers/specs/2026-11-07-folder-working-directory.md`

## Global Constraints

- `structure.json` stays `VERSION = 4`; `workdir` is optional in `FOLDER_FIELDS[4]` and written only when non-null.
- A folder's `workdir` is one path, never blank; several folders may share one; it may equal any bound dir.
- In one folder `workdir` beats `dirs.first()`; a nearer folder beats a farther one; a missing `workdir` is still the answer (no fallback upward).
- Menu texts: `Set Working Directory...`, `Clear Working Directory`. Row marker: `⌂ `. Tooltip: `New sessions start in: <path> (from <folder path>)`.
- MCP op: `{"op":"set_workdir","path":<folder path>,"dir":<directory or null>}`; tree JSON key `"workdir"`; `my_air_session_start` parameter `folder`.
- Code, comments, strings in English. Commits carry `Plan-Task: N/5` and `Plan: docs/superpowers/plans/2026-11-07-folder-working-directory.md` trailers.

## Review Focus

1. A `workdir` equal to a directory bound elsewhere must not trip the "directories bound twice" invariant (test in Task 1).
2. A session filed on start has no transcript yet; it must stay filed through refreshes and show in the folder before its first scan (`assign` does not check scans; verify manually in Task 5).
3. A task row of a plan folder whose ancestor has a `workdir`: directory from the ancestor, placement by `PlanStart`, not by the plan folder filing being replaced (test in Task 2).
4. `my_air_session_start` with `folder` naming a folder whose resolved directory is gone: must fail with "not directories", not start elsewhere (existing `missingDirs` check; test the resolver returns the gone path in Task 1).
5. Paths typed by an agent in `set_workdir` with a trailing separator or other spelling: stored as `Path.of(dir).toString()`, same as the chooser stores (test in Task 4).

---

### Task 1: Model, storage and resolver

**Files:**
- Modify: `src/main/kotlin/dev/myair/folders/SessionFolders.kt` (`Folder`, init invariants, `FOLDER_FIELDS`, `folder()`, new ops)
- Test: `src/test/kotlin/dev/myair/folders/SessionFoldersTest.kt`

**Interfaces:**
- Produces:
  - `Folder(..., val plan: Plan? = null, val workdir: String? = null)` (last parameter).
  - `val Folder.startDir: String?` = `workdir ?: dirs.firstOrNull()`.
  - `fun startFolder(ancestry: List<Folder>): Folder?` (top-level in `SessionFolders.kt`): the last folder of `ancestry` (top-down) whose `startDir != null`.
  - `fun SessionFolders.startFolder(folderId: String): Folder?` = `startFolder(ancestry(folderId))`.
  - `fun SessionFolders.setWorkdir(folderId: String, dir: String?): SessionFolders` (unknown folder: unchanged, like `bind`).

- [x] **Step 1: Write the failing tests**

```kotlin
@Test
fun `version 4 round-trips a working directory and writes none when unset`() {
    val set = tree.setWorkdir("fix", "E:\\dev\\rtm")
    assertEquals(set, SessionFolders.parse(set.toJson()))
    assertFalse("workdir" in tree.toJson())
}

@Test
fun `a working directory may repeat and may equal a bound directory, but not be blank`() {
    val shared = tree.bind("rtm", "E:\\dev\\rtm").setWorkdir("fix", "E:\\dev\\rtm").setWorkdir("tf", "E:\\dev\\rtm")
    assertEquals("E:\\dev\\rtm", shared.find("fix")!!.workdir)
    assertThrows<IllegalArgumentException> { tree.setWorkdir("fix", " ") }
}

@Test
fun `the nearest folder with a working directory or a bound one decides, its working directory first`() {
    val f = tree.bind("rtm", "/bound").createFolder("fix", "deep", "deep")
    assertEquals("/bound", f.startFolder("deep")!!.startDir)
    val w = f.setWorkdir("fix", "/work")
    assertEquals("fix", w.startFolder("deep")!!.id)                                   // nearer wins
    assertEquals("/work", w.setWorkdir("rtm", "/other").startFolder("deep")!!.startDir)
    assertEquals("/other", w.setWorkdir("rtm", "/other").startFolder("rtm")!!.startDir) // workdir beats own binding
    assertEquals("/gone", tree.setWorkdir("tf", "/gone").startFolder("tf")!!.startDir) // kept though missing
    assertNull(tree.startFolder("tf"))
}
```

(Fixture `tree`: `rtm(mig, fix)`, `tf`. Add `import kotlin.test.assertFalse`.)

- [x] **Step 2: Run** `./gradlew test --tests dev.myair.folders.SessionFoldersTest` — expected: compile failure on `setWorkdir`/`startFolder`.

- [x] **Step 3: Implement** the Interfaces above. In `init`, `all.mapNotNull { it.workdir }.forEach { require(it.isNotBlank()) { "blank working directory in ..." } }`; do not add workdirs to the "bound twice" list. `FOLDER_FIELDS[4]` gains `"workdir"`; `folder()` passes `json.get("workdir")?.asString` (any version: only v4 lists the field, `only` rejects it elsewhere). Update the `VERSION` doc comment: version 4 later added an optional `workdir`, written only when set. Update `Folder`'s KDoc: [workdir] is where sessions started from it or below start, holding nothing.

- [x] **Step 4: Run** `./gradlew test --tests dev.myair.folders.SessionFoldersTest` — expected: PASS, including `json round-trips in the user's order` unchanged.

- [x] **Step 5: Commit** `git commit -m "Give a folder an optional working directory and resolve where its sessions start"` with the trailers `Plan-Task: 1/5`, `Plan: ...`.

### Task 2: Start sessions from the tree in the resolved directory, filed in the clicked folder

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt:161-169` (`nodeDir`)
- Modify: `src/main/kotlin/dev/myair/actions/NewClaudeSessionAction.kt:24-38` (`NewSessionInDirAction`)
- Modify: `src/main/kotlin/dev/myair/actions/NewClaudeSessionInWorktreeAction.kt:54-70` (`startInWorktree`)
- Modify: `src/main/kotlin/dev/myair/editor/ClaudeSessionOpener.kt:63-82` (`startNew`)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt:224-225`
- Test: `src/test/kotlin/dev/myair/toolwindow/SessionTreeTest.kt`

**Interfaces:**
- Consumes: `startFolder(ancestry: List<Folder>)`, `Folder.startDir` (Task 1).
- Produces:
  - `nodeDir(path)` for `Folder`/`MilestoneRow`/`PlanTaskRow`/`NoTask`: `startFolder(<Folders on path>)?.startDir?.let(Path::of)`.
  - `ClaudeSessionOpener.startNew(..., plan: PlanStart? = null, folderId: String? = plan?.folderId)`: files the new id in `folderId` (one `updateFolders` call, `assign`) when non-null.
  - `startInWorktree(..., plan: PlanStart? = null, branch: String? = null, askBranch: Boolean = false, folderId: String? = plan?.folderId)`, passed through to `startNew`.
  - `NewSessionInDirAction(text, inWorktree, dir, plan, folder: () -> String?)`; `SessionsPanel` passes `{ editing.selectedFolder()?.id }`, so only a `Folder` row files (plan rows keep `PlanStart`; project rows and projects mode pass null).

- [x] **Step 1: Extend the test** `a project row and a folder in a bound project give a directory for a new session, other nodes none` (rename to `... a folder's working directory or bound project gives a directory ...`) with a structure where `empty` gets `setWorkdir("empty", Path.of("/work").toString())` and its child `p` is bound to `/rtm`:

```kotlin
assertEquals(Path.of("/rtm"), nodeDir(pathTo(1, 0)))      // p: nearer binding wins over parent's workdir
assertEquals(Path.of("/work"), nodeDir(pathTo(1)))        // empty: its own workdir
val worked = bound.setWorkdir("p", Path.of("/gone").toString())
// rebuild root from `worked`, then:
assertEquals(Path.of("/gone"), nodeDir(pathTo(1, 0, 0)))  // sub: p's workdir beats p's binding, kept though missing
```

Also add a plan case: a plan folder below a folder with `workdir` `/work`, one milestone and task; `nodeDir` of the task row is `/work`.

- [x] **Step 2: Run** `./gradlew test --tests dev.myair.toolwindow.SessionTreeTest` — expected: FAIL (`nodeDir` ignores `workdir`).

- [x] **Step 3: Implement** the Interfaces. Update `nodeDir`'s KDoc to name the working directory. `MCP`'s `startNew` call is updated in Task 4, not here (default `folderId = plan?.folderId` keeps today's behaviour).

- [x] **Step 4: Run** `./gradlew test` — expected: PASS.

- [x] **Step 5: Commit** `"Start sessions from a folder in its working directory and file them there"`, trailers `Plan-Task: 2/5`.

### Task 3: Tree menu, row and tooltip

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/FolderEditing.kt` (next to `bindDirectory`)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt:208-209` (actions), `:440-449` (`readBranches`)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionRenderers.kt:130-142`

**Interfaces:**
- Consumes: `setWorkdir`, `startFolder(List<Folder>)`, `startDir`, `pathOf(id)`.
- Produces: `FolderEditing.setWorkingDirectory()` (folder chooser, stores `Path.of(file.path).toString()`, `update { it.setWorkdir(id, dir) }`), `FolderEditing.clearWorkingDirectory()`.

- [x] **Step 1: Actions.** In `planActions` after `Unbind Directory...`: `FolderAction("Set Working Directory...", null, on { it is Folder }, ::canEditFolders, editing::setWorkingDirectory)` and `FolderAction("Clear Working Directory", null, on { it is Folder && it.workdir != null }, ::canEditFolders, editing::clearWorkingDirectory)`.

- [x] **Step 2: Branches.** `readBranches` reads `item.dirs.map(Path::of) + listOfNotNull(item.workdir?.let(Path::of))` for a `Folder`, so `branchOf` and `isGone` know the working directory.

- [x] **Step 3: Row.** After the bound dirs: when `item.workdir != null`, `append("  ⌂ " + dirLabel(Path.of(it)), if (isGone(Path.of(it))) GRAY_ITALIC_ATTRIBUTES else GRAYED_ATTRIBUTES)`. Icon unchanged.

- [x] **Step 4: Tooltip.** `val chain = node.path.map { (it as DefaultMutableTreeNode).userObject }.filterIsInstance<Folder>()`; `startFolder(chain)?.let { "New sessions start in: ${it.startDir} (from ${chain.take(chain.indexOf(it) + 1).joinToString("/") { f -> f.name }})" }`. A plan folder puts the line into `describedTooltip(listOf(line), description)`; other folders set `toolTipText = line`.

- [x] **Step 5: Run** `./gradlew test buildPlugin` — expected: PASS. Manual check is in Task 5.

- [x] **Step 6: Commit** `"Set, show and clear a folder's working directory in the tree"`, trailers `Plan-Task: 3/5`.

### Task 4: MCP

**Files:**
- Modify: `src/main/kotlin/dev/myair/mcp/McpJson.kt:41-48, 81-84`
- Modify: `src/main/kotlin/dev/myair/folders/FolderChanges.kt`
- Modify: `src/main/kotlin/dev/myair/mcp/FoldersMcpToolset.kt:69-125, 127-147`
- Modify: `src/main/kotlin/dev/myair/editor/SessionSystemPrompt.kt:24`
- Test: `src/test/kotlin/dev/myair/folders/FolderChangesTest.kt`, `src/test/kotlin/dev/myair/mcp/FolderQueriesTest.kt`

**Interfaces:**
- Consumes: `setWorkdir`, `SessionFolders.startFolder(id)`, `startDir`, `startNew(..., folderId)` (Task 2).
- Produces: `"workdir"` in both maps of `McpJson`; op `set_workdir` with fields `path`, `dir`.

- [x] **Step 1: Failing tests.**

```kotlin
// FolderChangesTest
@Test
fun `set_workdir sets, replaces and clears an existing directory, as Path spells it`(@TempDir dir: Path) {
    val path = dir.toString()
    val set = apply("""[{"op":"set_workdir","path":"rtm","dir":${Gson().toJson(path + File.separator)}}]""")
    assertEquals(path, set.findByPath("rtm")!!.workdir)
    assertNull(apply("""[{"op":"set_workdir","path":"rtm","dir":null}]""", set).findByPath("rtm")!!.workdir)
    assertThrows<IllegalArgumentException> { apply("""[{"op":"set_workdir","path":"rtm","dir":${Gson().toJson(dir.resolve("gone").toString())}}]""") }
    assertThrows<IllegalArgumentException> { apply("""[{"op":"set_workdir","path":"nope","dir":null}]""") }
}

// FolderQueriesTest
@Test
fun `the folder tree names each folder's working directory, null when unset`() { /* treeJson: "workdir" equals the set path; null on another folder */ }
```

- [x] **Step 2: Run** `./gradlew test --tests dev.myair.folders.FolderChangesTest --tests dev.myair.mcp.FolderQueriesTest` — expected: FAIL.

- [x] **Step 3: Implement.**
  - `FolderChanges`: `"set_workdir" to setOf("path", "dir")`; `dir = change.nullableString("dir")?.let { Path.of(it).toString() }`, `require(dir == null || Path.of(dir).isDirectory()) { "'$dir' is not a directory" }`, `folders.setWorkdir(folders.at(path).id, dir)`.
  - `McpJson`: `"workdir" to folder.workdir` after `"dirs"` in `treeJson` and `planMap`.
  - `my_air_folders_get` description: add "workdir (a directory new sessions started from it or a subfolder run in, unless a nearer folder has one or a bound directory; holds no sessions)".
  - `my_air_folders_apply` description: add `{"op":"set_workdir","path":"rtm/fixes","dir":"E:\\dev\\rtm"}` (dir null clears; an existing directory; any number of folders may share one).
  - `my_air_session_start`: new parameter `@McpDescription("A folder path such as rtm/fixes: the session is filed there and, without dir, runs in the working directory or bound directory of that folder or the nearest folder above it.") folder: String? = null`. In `onEdt`: `val filed = folder?.let { requireNotNull(service.folders.findByPath(it)) { "no folder '$it'" } }`; `val fromFolder = filed?.let { requireNotNull(service.folders.startFolder(it.id)?.startDir) { "no folder at or above '$folder' has a working or bound directory" } }`; pass `dir ?: fromFolder` to `sessionStartPreset`. Call `startNew(..., plan, folderId = filed?.id ?: plan?.folderId)`.
  - `SessionSystemPrompt.kt:24`: `"my_air_session_start starts a new session in a tab, only when the user asks for one; folder files it and picks its directory."`

- [x] **Step 4: Run** `./gradlew test` — expected: PASS (`SessionSystemPromptTest` included).

- [x] **Step 5: Commit** `"Read, set and start in a folder's working directory over MCP"`, trailers `Plan-Task: 4/5`.

### Task 5: Docs and end-to-end check

**Files:**
- Modify: `CLAUDE.md` (the "Plan folders" bullet and the `NewClaudeSessionInWorktreeAction` sentence about the tree's menu on a folder)

- [x] **Step 1: CLAUDE.md.** In the tree-menu sentence ("The tree's menu on a project row, or on a folder bound to a project or lying in one ...") state: the directory is the `workdir` or first bound dir of the nearest folder up the path that has either (`startFolder`, `startDir`), and a session started from a folder row is filed in it. In "Plan folders": `Folder.workdir` (optional, in v4, written only when set; holds no sessions, may repeat), `set_workdir`, `my_air_session_start`'s `folder`.

- [x] **Step 2: Build** `./gradlew test buildPlugin` — expected: BUILD SUCCESSFUL.

- [x] **Step 3: Sandbox check** (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`): on a scratch folder tree, set a working directory on a parent folder that is a repository bound elsewhere; on a subfolder, the menu shows both new-session actions; the row shows `⌂ <dir> [branch]`; the tooltip names the parent; New Claude Session starts in that directory and the tab's session appears under the subfolder at once and stays after the next refresh; New Claude Session in Worktree... creates the worktree in that repository and files the session in the subfolder; Clear Working Directory hides the actions again. Record what was seen in the commit message body.

- [x] **Step 4: Commit** `"Describe folder working directories"`, trailers `Plan-Task: 5/5`.
