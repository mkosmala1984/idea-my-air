# Session Context Panel Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `af8686e` |
| 2 | done | `019d0b3` |
| 3 | done | `ff031c2` |
| 4 (background tasks, added 2026-10-25) | done | `e9eac81` |
| 5 (final review fixes) | done | see git log |

## TL;DR

- **Goal:** a third pane in the Claude Sessions tool window, between the pinned list (top) and the tree (bottom), describing the session of the selected session tab: environment (project, branch, worktree, Desktop, added dirs), outputs (Markdown and HTML files the session wrote, anywhere, as Claude Desktop's Outputs section lists them), artifacts it published, and changes (`+added −removed` in its cwd).
- **Approach:** everything from transcripts and `git`, as the rest of the plugin. Artifacts: the `toolUseResult` of an Artifact publish (`artifact_id`, `url`, `title`, `path`; 44 such lines in current transcripts, all with these four keys), new field `ClaudeSession.artifacts`. Outputs: `changedFiles` with extension `md` or `html`, any folder (plans in `docs/superpowers`, `progress.md`, a PR description in the scratchpad), the newest first (by first write), 5 shown then "Show more". Branch and line counts from `git` off the EDT. UI with the Kotlin UI DSL (`collapsibleGroup`), refreshed on tab selection and on every service refresh. An output opens in the editor, an artifact in the browser.
- **Decided (2026-10-25):** the selected session tab; Changes = `git diff HEAD`; Outputs = written `md`/`html` files; the pane can be hidden with a View Options toggle; executed inline on `main`.
- **Out of scope:** the Context section (tokens, window, model/effort), Connectors, "This computer / Save", "Commit changes"; artifacts and outputs of subagents (their transcripts in `subagents/` are not scanned).
- **Risks:** the `toolUseResult` shape of Artifact may change in a new `claude` release; a line with `artifact_id` but no `url`/`title` throws. A non-existent cwd (removed worktree) must not run `git`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a pane showing the environment, outputs, artifacts and git changes of the session in the selected session tab.

**Architecture:** the scanner adds `artifacts` to `ClaudeSession`; `Git.state(dir)` reads branch and diff counts; a pure `SessionContext` value built by `contextOf`; `SessionContextPanel` (Swing, UI DSL) rebuilt from it; `SessionsPanel` nests a second `OnePixelSplitter` to fit it between the list and the tree.

**Tech Stack:** Kotlin, IntelliJ Platform (UI DSL v2, `FileEditorManagerListener`, `BrowserUtil`), Gson, JUnit 5, real `git`.

**Spec:** this conversation's screenshot (Environment and Changes sections), Claude Desktop's Outputs section (second screenshot), the request for artifacts, and the TL;DR above.

## Global Constraints

- Code, comments, strings in English; no fallbacks: a failing `git` call throws with its stderr (`Git.run`); an Artifact result without `url`, `title` or `path` throws.
- Tests are plain JUnit 5, no platform services; logic needing tests stays out of Swing/IDE code.
- `git` runs never on the EDT.

## Review Focus

1. Session whose cwd no longer exists (removed worktree): pane shows "Folder no longer exists", no `git` run; an output click shows the existing "File no longer exists" dialog. Test in Task 2 (`contextOf` with `cwdExists = false`).
2. Cwd that is not a git repository (e.g. home dir): no branch row, Changes hidden. Test in Task 2 (`Git.state` returns null).
3. An artifact republished several times (same `artifact_id`, new title): one row, latest title, position of its first publish. Test in Task 1.
4. A file edited several times, or code files mixed in: one row per file, only `md`/`html`, the most recently first-touched on top. Test in Task 2.
5. Switching tabs quickly: a `git` result for the previous session must not overwrite the pane. Guard in Task 3 (result applied only if its session id is still the shown one).

---

### Task 1: artifacts in the scanner

**Files:**
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSession.kt` (`data class Artifact`, field, KDoc line)
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSessionScanner.kt` (`Transcript`, `read`, `toSession`)
- Test: `src/test/kotlin/dev/myair/claude/ClaudeSessionScannerTest.kt`

**Interfaces:**
- Produces: `data class Artifact(val id: String, val title: String, val url: String, val path: Path)`; `ClaudeSession.artifacts: List<Artifact>` (default empty), in the order first published, one per `id`, latest `title`/`url`/`path` of it.

- [x] **Step 1: Write the failing test** `artifacts are the published ones of the main conversation, one per id with its latest title`: user lines with `toolUseResult` `{artifact_id:"a", url:"https://claude.ai/artifact/A", title:"Plan", path:"C:\\x\\plan.html", ...}`, then `b`, then `a` again with title `"Plan v2"`, plus a sidechain line with `artifact_id:"c"`. Assert `artifacts.map { it.id to it.title } == listOf("a" to "Plan v2", "b" to ...)`. Second test: a line with `artifact_id` and no `url` throws `IllegalStateException`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.claude.ClaudeSessionScannerTest` — FAIL (unresolved `artifacts`).
- [x] **Step 3: Implement.** Hint `"artifact_id":"` in the line (cheap, as other hints); then `json(line)`, skip sidechains, read `toolUseResult` with `checkNotNull` on each of the four keys; keep a `LinkedHashMap<String, Artifact>` in `Transcript` (replacing a value keeps its position).
- [x] **Step 4: Run** the same command — PASS, whole class green.
- [x] **Step 5: Commit** `Read the artifacts a session published from its transcript`.

### Task 2: git state and the pane's model

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/Git.kt`
- Create: `src/main/kotlin/dev/myair/toolwindow/SessionContext.kt`
- Test: `src/test/kotlin/dev/myair/actions/GitTest.kt`, `src/test/kotlin/dev/myair/toolwindow/SessionContextTest.kt`

**Interfaces:**
- Consumes: `ClaudeSession.project/cwd/desktop/addedDirs/changedFiles/artifacts`.
- Produces:
  - `data class GitState(val branch: String, val added: Int, val removed: Int)` in `Git.kt`; `branch` is the short name or `detached at <sha7>`.
  - `fun Git.state(dir: Path): GitState?` — null when `rev-parse --is-inside-work-tree` says no (exit 128 allowed via `ok`); otherwise `branch --show-current`, empty → `detached at ` + `rev-parse --short=7 HEAD`; counts summed from `diff HEAD --numstat` (binary rows `-\t-` count 0). An unborn HEAD (`rev-parse --verify -q HEAD` exits 1) gives `GitState(<branch>, 0, 0)` without the diff.
  - `data class SessionContext(val project: String, val projectPath: Path, val worktree: Boolean, val desktop: Boolean, val addedDirs: List<Path>, val outputs: List<Path>, val artifacts: List<Artifact>, val cwdExists: Boolean, val git: GitState?)` and `fun contextOf(session: ClaudeSession, cwdExists: Boolean, git: GitState?): SessionContext` — `worktree = session.cwd != session.project`; `outputs = session.changedFiles.filter { it.extension.lowercase() in OUTPUT_EXTENSIONS }.reversed()` with `val OUTPUT_EXTENSIONS = setOf("md", "html")` (`changedFiles` keeps first-touch order and no duplicates).

- [x] **Step 1: Write failing tests.** `GitTest`: `state of a repository names its branch and counts uncommitted lines` (commit a 3-line file, change 1 line and add 2 → `GitState("main", 3, 1)`); `state of a detached head names its commit`; `state of a repository without commits is its branch with no changes`; `state outside a repository is null`. `SessionContextTest`: a session with cwd `<main>/.claude/worktrees/x` and project `<main>` named `my-air` → `worktree == true`, `project == "my-air"`; changed files `<cwd>/docs/superpowers/plans/p.md`, `<cwd>/src/A.kt`, `C:/tmp/scratch/pr-description.MD` → `outputs == listOf(C:/tmp/scratch/pr-description.MD, <cwd>/docs/superpowers/plans/p.md)`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.actions.GitTest --tests dev.myair.toolwindow.SessionContextTest` — FAIL.
- [x] **Step 3: Implement** the signatures above.
- [x] **Step 4: Run** the same command — PASS.
- [x] **Step 5: Commit** `Read the branch and uncommitted line counts of a session's folder`.

### Task 3: the pane

**Files:**
- Create: `src/main/kotlin/dev/myair/toolwindow/SessionContextPanel.kt`
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt` (splitter)
- Modify: `CLAUDE.md` (artifacts under "Where the data comes from"; one paragraph on the pane under "How the pieces fit")

**Interfaces:**
- Consumes: `contextOf`, `Git.state`, `ClaudeSessionsService.sessions` / `subscribe`.
- Produces: `class SessionContextPanel(project: Project, parent: Disposable) : JPanel(BorderLayout())`.

- [x] **Step 1: Current session.** The shown session id = `(FileEditorManager.getInstance(project).selectedFiles.firstOrNull() as? ClaudeSessionVirtualFile)?.sessionId`; re-read on `FileEditorManagerListener.FILE_EDITOR_MANAGER` `selectionChanged` (project bus, `parent` disposable) and on `service.subscribe(parent)` (`sessionId` follows `/clear` through re-targeting). The session: `service.sessions.firstOrNull { it.id == id }`; a new tab whose transcript is not scanned yet shows "Starting...".
- [x] **Step 2: Content.** `panel { collapsibleGroup(...) ... }` rebuilt into the `JPanel` on each update (it is small), sections in this order:
  - Environment: folder icon + project name · path (grey); branch icon + branch, "Worktree" on the right when `worktree`; "Desktop" when `desktop`; one row per added dir. `cwdExists == false` adds "Folder no longer exists".
  - Outputs (only when non-empty): one `link` per file with a file-type icon, text = file name, tooltip = full path; the first 5, then a "Show more" / "Show less" link toggling the rest (state kept per panel, not per session); opening it in the editor as `SessionsPanel.openSelected` opens a `ChangedFile` (extract that branch to a shared `openFile(project, path)` rather than duplicating it).
  - Artifacts (only when non-empty): one `link` per artifact, text = `title`, tooltip = `url`, `BrowserUtil.browse(url)`.
  - Changes (only when `git != null`): `+added` in `FileStatus.ADDED.color`, `−removed` in `FileStatus.DELETED.color`.
  - Empty state: "No session tab selected".
- [x] **Step 3: Git off the EDT.** On each update render `contextOf(..., git = null)` at once, then `executeOnPooledThread { Git.state(cwd) }` only when `cwd.exists()`; back on the EDT (`invokeLater`, `ModalityState.any()`) apply only if the shown id still equals the one the job started for (Review Focus 5).
- [x] **Step 4a: Toggle.** View Options gets `Show Session Context` (`ToggleAction`, persisted as `dev.myair.showContext` in `PropertiesComponent`, default on), placed after `Show All Sessions`; off removes the pane and its splitter so the tree takes its space; on puts it back.
- [x] **Step 4: Layout.** In `SessionsPanel.init`: the existing `OnePixelSplitter(true, "dev.myair.pinnedSplitter", 0.25f)` gets as `secondComponent` a new `OnePixelSplitter(true, "dev.myair.contextSplitter", 0.3f)` with `SessionContextPanel` (in a scroll pane) first and the tree second.
- [ ] **Step 5: Verify manually** in the sandbox (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`): a session tab → pane fills; a plain file tab → empty state; this planning session (`951e0da3`) → Outputs lists `2026-10-25-session-context-panel.md`, a click opens it; a session that published an artifact (e.g. `24508b9f` in `E:/dev/projects/claude-projects`) → its title, a click opens the browser; a worktree session → "Worktree" and its branch; edit a file in its cwd → Changes matches `git diff HEAD --shortstat` after the next refresh; a session whose worktree folder was deleted → "Folder no longer exists", no error in `idea.log`.
- [x] **Step 6: Run** `./gradlew test buildPlugin` — all green.
- [x] **Step 7: Commit** `Show the environment, outputs, artifacts and changes of the selected session tab`.
