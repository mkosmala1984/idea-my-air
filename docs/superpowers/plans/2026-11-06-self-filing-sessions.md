# Sessions that file and title themselves: implementation plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `929b61b` |
| 2 | done | `eb724c6` |
| 3 | done | `439c026` |
| 4 | done | `4d859e8` |
| 5 | blocked: sandbox check by hand not run (needs the new build installed or a sandbox driven by the user) | |

## TL;DR

- Goal: with each `my_air_set_brief` call the agent names the session's folder and title. The tree places and titles sessions from the transcript, and nobody has to approve it.
- Approach: `Brief` gains `title` and `folder` (a folder id read from the tool's result) and keeps `plan_task`, `title` and `folder` when a call leaves them out. `SessionFolders` gains `unfiled` (an explicit "nowhere") and a version 4 format, and `placeOf` gains the brief's folder. The tool creates a missing folder one level below an existing one.
- Decisions for you: none open. Assumptions (spec TL;DR): "nowhere" beats the project binding; turning the setting off leaves places and titles already given; `structure.json` version 4 cannot be read by an older My Air.
- Risks: badly named folders, mitigated by the one-level rule, case-insensitive matching and undo. Sticky `plan_task` changes today's meaning, where leaving it out cleared it.
- Out of scope: batch sorting of old sessions, writing titles back to `claude`, Desktop sessions.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** sessions file and title themselves through `my_air_set_brief`.

**Architecture:** As with `plan_task` today, the session's place and title derive from its transcript. The only write is a folder the brief creates, which goes to `structure.json` through `ClaudeSessionsService.updateFolders(ChangeSource.BRIEF)`. The explicit "nowhere" lives in `structure.json` next to `items`, because only the user's drop sets it.

**Tech Stack:** Kotlin, IntelliJ Platform (MCP server toolset API), Gson, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-06-self-filing-sessions-design.md`

## Global Constraints

- `title`: non-blank, at most 60 chars (`Brief.TITLE_CHARS = 60`).
- Brief tool result text: `ok`, or `ok, folder <id> at '<path>'`, plus ` (created)` when the call created the folder. Any other text of an accepted call throws in the scanner.
- `structure.json` `VERSION = 4`; root fields `version`, `nextId`, `folders`, `unfiled` (always written).
- New `ChangeSource.BRIEF` (label `brief`).
- Setting `MyAirSettings.Options.fileSessions`, default `true`; effective only while `briefs` is on.
- `appendedSystemPrompt` stays one line without `"`, `<`, `>`.
- Code, comments and strings in English; unknown formats throw, nothing is skipped silently.

## Review Focus

- A folder named by a brief is later deleted: the session falls back to its project's folder or `sessions` and does not throw. Task 2 tests it.
- A folder named by a brief is renamed or moved: the session follows it (id, not path). Task 2 tests it.
- A brief folder that later ends up inside a plan folder (the user moved it there): it is ignored, the session goes to its project's folder. Task 2 tests it.
- A session filed by hand and then dropped on `sessions`: `items` loses it, `unfiled` gains it, and filing it again removes it from `unfiled`. Task 2 tests it.
- A real MCP result is `content: [{"type":"text","text":"ok, ..."}]`, while scanner tests use `content: "ok"`. Both shapes must parse. Task 1 tests both.

---

### Task 1: The brief carries a title and a folder

**Files:**
- Modify: `src/main/kotlin/dev/myair/claude/Brief.kt`
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSessionScanner.kt:138-160` (`Transcript.next`, `toSession`)
- Test: `src/test/kotlin/dev/myair/claude/BriefTest.kt`, `src/test/kotlin/dev/myair/claude/ClaudeSessionScannerTest.kt`

**Interfaces:**
- Produces: `data class Brief(goal: String?, tasks: List<BriefTask>, planTask: String? = null, title: String? = null, folder: String? = null)`. `folder` is a folder id.
- Produces: `Brief.input(goal: String, tasks: String, planTask: String? = null, title: String? = null): Brief` validates the title.
- Produces: `Brief.next(previous: Brief?, tool: String, input: JsonObject, result: String): Brief`. `result` is the text of the tool's result.
- Produces: `Brief.folderResult(id: String, path: String, created: Boolean): String` and `const val Brief.OK = "ok"`. The tool returns these texts (Task 4).

- [x] **Step 1: Write the failing tests in `BriefTest`**

```kotlin
@Test
fun `a title is non-blank and at most 60 chars`() {
    assertEquals("Migracja AGC", Brief.input("g", "[]", title = "Migracja AGC").title)
    assertThrows<IllegalArgumentException> { Brief.input("g", "[]", title = " ") }
    assertThrows<IllegalArgumentException> { Brief.input("g", "[]", title = "x".repeat(61)) }
}

@Test
fun `the folder comes from the result, and fields left out keep their previous value`() {
    val first = Brief.next(null, "mcp__idea__my_air_set_brief", input("""{"goal":"g","tasks":"[]","plan_task":"T3","title":"A","folder":"rtm/fixes"}"""), Brief.folderResult("f1", "rtm/fixes", created = true))
    assertEquals(Brief("g", emptyList(), "T3", "A", "f1"), first)
    val second = Brief.next(first, "mcp__idea__my_air_set_brief", input("""{"goal":"h","tasks":"[]"}"""), Brief.OK)
    assertEquals(Brief("h", emptyList(), "T3", "A", "f1"), second)
}

@Test
fun `an accepted call with an unknown result throws`() {
    assertThrows<IllegalStateException> { Brief.next(null, "mcp__idea__my_air_set_brief", input("""{"goal":"g","tasks":"[]"}"""), "done") }
}

@Test
fun `a Desktop plan keeps title, folder and plan task`() {
    val brief = Brief("g", emptyList(), "T3", "A", "f1")
    assertEquals(brief.copy(goal = null, tasks = listOf(BriefTask("x", TaskStatus.PENDING))),
        Brief.next(brief, Brief.UPDATE_PLAN, input("""{"items":[{"content":"x","status":"pending"}]}"""), "Plan shown (1 steps)."))
}
```

(`input` parses a JSON literal into a `JsonObject`; add it as a private helper if the class has none.) The last test fixes the current behaviour that `items` replaces the whole brief: from now on `items` replaces `goal` and `tasks` only.

- [x] **Step 2: Write the failing scanner tests in `ClaudeSessionScannerTest`**

Extend `briefCall` with optional `extra: String = ""` JSON fields in its input, and `result` with an optional `text: String = "ok"` and `array: Boolean = false` (`content: [{"type":"text","text":...}]`).

```kotlin
@Test
fun `the brief's folder is read from the result in either content shape`() {
    val s = parse(write("s1", user("\"prompt\""),
        briefCall("t1", "G", "[]", extra = ""","folder":"rtm/fixes""""), result("t1", text = "ok, folder f1 at 'rtm/fixes' (created)", array = true)))!!
    assertEquals("f1", s.brief!!.folder)
}

@Test
fun `a custom title beats the brief's, which beats the AI title`() {
    // ai-title line, then a brief with title "Brief title": title is "Brief title"; then a custom-title line: title is the custom one
}
```

Write the second test with the transcript's real `ai-title` and `custom-title` lines, as other tests in the file build them.

- [x] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests dev.myair.claude.BriefTest --tests dev.myair.claude.ClaudeSessionScannerTest`
Expected: compilation fails on `title`, `folder`, `folderResult` and `OK`.

- [x] **Step 4: Implement**

- In `Brief.kt`, `next` for `my_air_set_brief`: `input(...)` from the call's fields, then `copy(planTask = planTask ?: previous?.planTask, title = title ?: previous?.title, folder = folderOf(result) ?: previous?.folder)`. `folderOf` returns null for `OK`, the id for `^ok, folder (\S+) at '.*'( \(created\))?$`, and throws `IllegalStateException` for anything else. `items` keeps `planTask`, `title` and `folder` of `previous`. The `done`/`start` branch already copies.
- In `ClaudeSessionScanner.Transcript.next`, pass the result text of `item["content"]`: a string as is, an array as the `text` of its single `text` item. Any other shape throws through `line.check`.
- In `toSession`, the title is `c.customTitle ?: brief?.title ?: c.aiTitle ?: <first prompt>`.

- [x] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.myair.claude.*'`
Expected: PASS. Fix existing tests that relied on `items` dropping `planTask` only if the spec says so; it does not, so they should pass as they are.

- [x] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/myair/claude src/test/kotlin/dev/myair/claude
git commit -m "Read a session's title and folder from its brief" -m "Plan-Task: 1/5
Plan: docs/superpowers/plans/2026-11-06-self-filing-sessions.md"
```

### Task 2: Folders place a session by its brief and remember "nowhere"

**Files:**
- Modify: `src/main/kotlin/dev/myair/folders/SessionFolders.kt` (`SessionFolders`, `placeOf`, `assign`, `toJson`, `parse`)
- Modify: `src/main/kotlin/dev/myair/folders/StructureStore.kt` (`ChangeSource.BRIEF`)
- Test: `src/test/kotlin/dev/myair/folders/SessionFoldersTest.kt`

**Interfaces:**
- Consumes: `Brief.folder` (Task 1).
- Produces: `SessionFolders(folders, nextId = 1, unfiled: List<String> = emptyList())`.
- Produces: `fun SessionFolders.unfile(sessionId: String): SessionFolders` takes the session out of its folder and adds it to `unfiled`. `assign(id, folderId)` with a non-null folder also removes it from `unfiled`. `assign(id, null)` leaves `unfiled` as it is.
- Produces: `fun SessionFolders.briefFolder(path: String, newId: () -> String): Pair<SessionFolders, Folder>` returns the folder at `path` (created when missing) and the structure holding it. It throws `IllegalArgumentException` per the spec's table.
- Produces: `ChangeSource.BRIEF`.

- [x] **Step 1: Write the failing tests**

- `placeOf follows the brief's folder below hand filing and nowhere, above the project`: build a structure with folder `app` bound to `/app`, folder `rtm` with subfolder `fixes` (id `fx`), and a session in `/app` whose brief has `folder = "fx"`, then assert, in order:
  - `placeOf` is `fx`;
  - after `assign(s, "rtm")` it is `rtm`;
  - after `unfile(s)` it is `null`;
  - after `assign(s, "fx")` it is `fx` and `unfiled` is empty.
- `a brief's folder deleted or in a plan folder is ignored`: after `delete("fx")` the place is `app`. With `fx` under a plan folder (`moveFolder` into one), the place is `app`.
- `a brief's folder follows a rename`: after `rename("fx", "bugs")` the place is still `fx`.
- `briefFolder finds or creates one level below an existing folder`:
  - `rtm/Fixes` returns `fx` with the structure unchanged (`==`);
  - `rtm/new` creates `new` under `rtm` with the id from `newId`;
  - `top` throws (top level);
  - `rtm/a/b` throws (missing parent `rtm/a`);
  - a path at or inside a plan folder throws.
- `version 4 round-trips unfiled, and version 3 reads with none`: `parse(toJson())` equals the structure with `unfiled = listOf("s1")`; a version 3 literal parses with `unfiled` empty.
- `a session both filed and unfiled throws`: `assertThrows<IllegalArgumentException> { SessionFolders(listOf(Folder("f", "f", items = listOf(Item(SESSION, "s")))), unfiled = listOf("s")) }`.

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests dev.myair.folders.SessionFoldersTest`
Expected: compilation fails on `unfile`, `briefFolder` and the `unfiled` parameter.

- [x] **Step 3: Implement**

`placeOf`: `taskOf(session)?.first?.id ?: folderOf(session.id) ?: if (session.id in unfiled) null else session.brief?.folder?.takeIf { find(it) != null && planFolderAt(it) == null } ?: projectFolder(session.project)?.id`.

Other changes:
- `init` requires no duplicates in `unfiled` and no overlap with filed items.
- `VERSION = 4`, `FOLDER_FIELDS[4] = FOLDER_FIELDS[3]`.
- `parse` reads `unfiled` only for version 4 and requires the root fields `version`, `nextId`, `folders`, `unfiled`; `nextId` is read for versions 3 and 4.
- `toJson` writes `unfiled`.
- Update the KDoc of `SessionFolders` and of `placeOf` to the spec's order.

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.myair.folders.*' --tests 'dev.myair.mcp.*'`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/myair/folders src/test/kotlin/dev/myair/folders
git commit -m "Place a session in its brief's folder unless the user filed or unfiled it" -m "Plan-Task: 2/5
Plan: docs/superpowers/plans/2026-11-06-self-filing-sessions.md"
```

### Task 3: Dropping a session on `sessions` unfiles it explicitly

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt:160-196` (`dropChange` and its KDoc)
- Test: `src/test/kotlin/dev/myair/toolwindow/SessionTreeTest.kt`

**Interfaces:**
- Consumes: `SessionFolders.unfile` (Task 2).

- [x] **Step 1: Write the failing test**

`a session dropped on sessions is unfiled, a bound project's too`: drop a filed session and a session of a bound project on the `UnfiledSessions` node. The drop is not null, and applying it gives `unfiled` containing both ids, `folderOf` null for both, and `placeOf` null for both.

- [x] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests dev.myair.toolwindow.SessionTreeTest`
Expected: FAIL. The drop is null for the bound project's session.

- [x] **Step 3: Implement**

In `dropChange`, remove the refusal `folderId == null && dragged.any { projectFolder(...) != null }`. With `folderId == null`, fold with `unfile(id)`; otherwise use `assign(id, folderId)`. Update the KDoc sentence about bound projects.

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.myair.toolwindow.*'`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/myair/toolwindow/SessionTree.kt src/test/kotlin/dev/myair/toolwindow/SessionTreeTest.kt
git commit -m "Keep a session dropped on sessions there" -m "Plan-Task: 3/5
Plan: docs/superpowers/plans/2026-11-06-self-filing-sessions.md"
```

### Task 4: The tool, the setting and the prompt

**Files:**
- Modify: `src/main/kotlin/dev/myair/mcp/BriefMcpToolset.kt`
- Modify: `src/main/kotlin/dev/myair/MyAirSettings.kt` (option, getter, checkbox)
- Modify: `src/main/kotlin/dev/myair/editor/SessionSystemPrompt.kt` (`appendedSystemPrompt`)
- Modify: `src/main/kotlin/dev/myair/editor/ClaudeSessionEditor.kt:74-82`
- Modify: `src/main/kotlin/dev/myair/mcp/FoldersMcpToolset.kt` (`my_air_history_list` description: source `brief`)
- Test: `src/test/kotlin/dev/myair/editor/SessionSystemPromptTest.kt`

**Interfaces:**
- Consumes: `Brief.input(..., title)`, `Brief.OK`, `Brief.folderResult` (Task 1); `SessionFolders.briefFolder`, `ChangeSource.BRIEF` (Task 2).
- Produces: `MyAirSettings.fileSessions: Boolean` (`briefs && state.fileSessions`).
- Produces: `appendedSystemPrompt(briefs: Boolean, filing: Boolean): String`.

- [x] **Step 1: Write the failing tests in `SessionSystemPromptTest`**

```kotlin
@Test
fun `filing is asked for only with briefs and filing on`() {
    assertTrue("my_air_folders_get first" in appendedSystemPrompt(briefs = true, filing = true))
    assertFalse("my_air_folders_get first" in appendedSystemPrompt(briefs = true, filing = false))
    assertFalse("my_air_folders_get first" in appendedSystemPrompt(briefs = false, filing = false))
}
```

Make the existing tests iterate over every `(briefs, filing)` pair that can occur: `(true, true)`, `(true, false)` and `(false, false)`.

- [x] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests dev.myair.editor.SessionSystemPromptTest`
Expected: compilation fails on `filing`.

- [x] **Step 3: Implement**

- `appendedSystemPrompt` adds, when `filing`, one sentence. It must contain the phrase `my_air_folders_get first` and cover the spec's three rules: title at most 60 chars in the user's language, set first and changed with the goal; read the folders first, prefer an existing folder, create one level below an existing folder only when none fits, no plan folders; the user's filing wins.- `ClaudeSessionEditor` passes `MyAirSettings.getInstance().fileSessions`.
- `MyAirSettings`: `var fileSessions by property(true)`, getter `val fileSessions get() = briefs && state.fileSessions`, and the checkbox "Agents file and title their sessions (folder and title in my_air_set_brief)" `.enabledIf(<the briefs checkbox>.selected)`.
- `my_air_set_brief(goal, tasks, plan_task, folder: String? = null, title: String? = null)`:
  - The description adds what `folder` and `title` do, that both are kept until replaced, and the folder rules.
  - It requires `MyAirSettings.getInstance().fileSessions` when either is given.
  - It calls `Brief.input(goal, tasks, plan_task, title)`.
  - With `folder`, on the EDT, it runs `ClaudeSessionsService.getInstance().updateFolders(ChangeSource.BRIEF) { it.briefFolder(folder) { UUID.randomUUID().toString() }.also { (_, f) -> found = f } .first }`, with `created` being whether the structure changed. It returns `Brief.folderResult(found.id, folders.pathOf(found.id), created)`. Without `folder` it returns `Brief.OK`.
  - `IllegalArgumentException` becomes `McpExpectedError`, as today.

- [x] **Step 4: Run all tests and build**

Run: `./gradlew test buildPlugin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "Let an agent file and title its own session through its brief" -m "Plan-Task: 4/5
Plan: docs/superpowers/plans/2026-11-06-self-filing-sessions.md"
```

### Task 5: Document it and check it in the sandbox

**Files:**
- Modify: `CLAUDE.md` (the Tab store paragraph on `pins.json`/tree, the plan folders paragraph, and the transcripts paragraph on titles)

- [x] **Step 1: Update `CLAUDE.md`**

Cover the title order, `Brief.folder` from the result, the `placeOf` order with `unfiled`, `structure.json` version 4, `ChangeSource.BRIEF`, and the `fileSessions` setting. State it in the file's existing sentence style.

- [ ] **Step 2: Check in the sandbox by hand**

Run: `env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`. Open a new session in a project with a bound folder that has a subfolder, and ask it for something. Expected:
- after its first brief the session shows under the folder it chose, titled by the brief;
- `/rename x` makes it `x`;
- dragging it to `sessions` keeps it there after its next brief;
- a brief naming a new subfolder adds that folder, and `my_air_history_list` shows a `brief` snapshot.

- [x] **Step 3: Commit**

```bash
git add CLAUDE.md docs/superpowers/plans/2026-11-06-self-filing-sessions.md
git commit -m "Describe sessions that file and title themselves" -m "Plan-Task: 5/5
Plan: docs/superpowers/plans/2026-11-06-self-filing-sessions.md"
```
