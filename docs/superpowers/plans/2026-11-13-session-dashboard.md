# Sessions Dashboard Implementation Plan

## TL;DR

- **Goal:** one "Sessions" editor tab per IDE with Now / Unfinished (grouped by area) / Finished, bringing back sessions left unfinished days ago.
- **Approach:** 6 tasks: (1) scanner reads the session's last branch and whether its agent asked a question; (2) `Git.branchFate` (unpushed, merged with PR number) and `Git.uncommitted`; (3) a pure, tested model `dashboardOf`; (4) `GitFacts`, the off-EDT reader with R17's cache; (5) the tab: file system, single instance across windows, restore dedupe, action and toolbar button; (6) the panel UI, then docs and a sandbox walk.
- **Decision needing your input:** none.
- **Risk:** Task 4's cache keys decide whether a session with unsaved work can slip into Finished; its tests pin the triggers. Merged detection depends on the last `git fetch`.
- **Out of scope:** as in the spec (Bitbucket/Jira APIs, cross-IDE single instance, pinned list, tree, taskbar).

| Task | Status | Commit |
|---|---|---|
| 1 | done | `19265fb` |
| 2 | done | `6829ae7` |
| 3 | done | `fd47a8e` |
| 4 | done | `11d0675` |
| 5 | done | `da60a41` |
| 6 | done | `b4a0005` |

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** an editor tab that lists, for all sessions not settled, what needs the user now, what was left unfinished (by area of work, with signal chips) and what is finished (merged or without signals, settled in bulk).

**Architecture:** the scanner gains two fields; `Git` gains two read functions; a pure `dashboardOf` classifies sessions from service state plus per-session git facts; `GitFacts` reads those facts per work tree root on a background executor with a stamp-based cache; `DashboardEditor`/`DashboardPanel` render the model in the composer look and follow `ClaudeSessionsService`.

**Tech Stack:** Kotlin, IntelliJ Platform 263 (Swing, `FileEditorProvider`, `DeprecatedVirtualFileSystem`), real `git` on PATH, JUnit 5 without the IntelliJ test framework.

**Spec:** `docs/superpowers/specs/2026-11-13-session-dashboard-design.md`

## Global Constraints

- English in code, comments, strings and test names; test names are backtick sentences.
- No git command on the EDT (R15). Read-only git calls pass `--no-optional-locks` (R17).
- Fail loudly: unknown formats throw; no fallbacks that hide a missing value (CLAUDE.md principles).
- Tests may use IDE-shipped libraries but no platform services; keep logic that needs tests out of IDE-service code.
- Classes named in `plugin.xml` are found at runtime only; register every new one there.
- Commit trailers: `Plan-Task: <n>/6` and `Plan: docs/superpowers/plans/2026-11-13-session-dashboard.md`, one block.

## Review Focus

1. A session that wrote files and ended its process while its work tree's facts were already cached for a sibling session: it must not land in Finished with uncommitted files (Task 4 test `a session with a newer last message rereads its roots`).
2. A shared main checkout dirty with files no session of the list wrote: none of those sessions gets `uncommitted` (Task 3 test via `SessionGit`, Task 4 test `only files the session wrote count as uncommitted`).
3. A branch merged and then given a new commit: not merged, `unpushed` stays (Task 2 test).
4. A session whose worktree folder was removed but whose branch still exists: its branch is read from the project's checkout (Task 4 test `a removed worktree reads its branch in the project checkout`).
5. Two windows restored with the dashboard each: only one tab remains (Task 5 manual check, R2).

---

### Task 1: Scanner reads the last branch and whether the agent asked

**Files:**
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSession.kt` (constructor)
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSessionScanner.kt` (`Messages`, `Message`, `message()`, `toSession`, `Transcript.EMPTY`)
- Test: `src/test/kotlin/dev/myair/claude/ClaudeSessionScannerTest.kt`

**Interfaces:**
- Produces: `ClaudeSession.lastBranch: String? = null`, `ClaudeSession.asks: Boolean = false`; `Messages(last, model, effort, branch: String?, asks: Boolean)`.

Rules (spec R8e, R11): over message lines of the main conversation (the ones `message()` already accepts: not sidechain, not `isMeta`), `branch` is the `gitBranch` of the latest one, `"HEAD"` or absent read as null. `asks` becomes `text.trimEnd().endsWith('?')` on an `assistant` line (not `<synthetic>`) whose `message.content` has a `text` item, using the last such item; becomes false on a `user` line whose content is a string or holds a `text` item (a human prompt); every other line keeps it (tool results, tool-use-only replies, `local_command`). Read `content` with the existing streaming `JsonReader` in `message()`, keeping only the last `text` item's string; no tree parsing.

- [ ] **Step 1: Write the failing tests** (reuse `user(...)`, `reply(...)` helpers; add a `text(reply: String, branch: String?)` helper building an assistant line with `"content":[{"type":"text","text":...}]` and `"gitBranch"`)

```kotlin
@Test
fun `the last branch is the gitBranch of the latest message of the main conversation, HEAD as none`() {
    // user on main, assistant on TR-1-x, sidechain line on other → lastBranch "TR-1-x"; then a line with "HEAD" → null
}

@Test
fun `a session asks when its agent's last text ends with a question mark, until the human answers`() {
    // assistant text "Zatwierdzasz?\n" → asks true; assistant tool_use-only line → still true;
    // user tool_result line → still true; user prompt "tak" → false; assistant text "Done." → false;
    // sidechain assistant "?" → unchanged; <synthetic> reply → unchanged
}
```

- [ ] **Step 2: Run them, expect FAIL** (unresolved `lastBranch`/`asks`)

Run: `./gradlew test --tests dev.myair.claude.ClaudeSessionScannerTest`

- [ ] **Step 3: Implement** the fields and the parsing rules above; `toSession` copies `messages.branch` and `messages.asks`.

- [ ] **Step 4: Run the class, expect PASS**, then `./gradlew test` (the scan cache's class hash changes; nothing else should break).

- [ ] **Step 5: Commit** `Read the last branch and an open question from transcripts`

---

### Task 2: Branch fate and uncommitted files in `Git`

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/Git.kt`
- Test: `src/test/kotlin/dev/myair/actions/GitTest.kt`

**Interfaces:**
- Produces:

```kotlin
/** What became of a branch: commits its upstream (else its base) lacks, and the merge into its base that holds its tip. */
data class BranchFate(val unpushed: Int, val merged: Merged?) {
    data class Merged(val sha: String, val pr: Int?)
}
// in object Git:
fun branchFate(root: Path, branch: String): BranchFate?   // null: no refs/heads/<branch>, or its base is the branch or its upstream
fun uncommitted(root: Path): Set<Path>                    // absolute paths, modified/added/deleted/untracked, --no-optional-locks
```

Algorithm of `branchFate` (spec "Merged", R10, R8c): base = `storedBase(root, branch) ?: repo(root).default`; upstream = `%(upstream)` of `refs/heads/<branch>` when it names a commit. `unpushed` = `rev-list --count <upstream ?: base>..refs/heads/<branch>`. Merged only when `merge-base --is-ancestor <tip> <base>`; then walk `log --first-parent --merges --format=%H %P%x1f%s <base> ^<tip>` and take the first commit whose 2nd parent has tip as ancestor and 1st parent does not; `pr` from `\(pull request #(\d+)\)` in the subject. Merged with unpushed > 0 cannot happen by construction; do not special-case it.

- [ ] **Step 1: Write the failing tests** (existing `git`/`init`/`clone` helpers)

```kotlin
@Test fun `a branch merged by a Bitbucket merge commit is merged with its pull request number`()
// feature with 1 commit; on main: merge --no-ff -m "Merged in feature (pull request #12)" → merged.pr == 12, merged.sha == the merge, unpushed == 0 (base used, no upstream)

@Test fun `a branch merged by a merge without a pull request number is merged with no number`()

@Test fun `a branch given a new commit after its merge is not merged and has one unpushed commit`()

@Test fun `a branch never merged counts commits its upstream lacks, else commits its base lacks`()
// clone(); feature tracking origin/feature with 2 local commits → unpushed 2; a local branch without upstream with 3 commits off main → 3

@Test fun `a missing branch and a branch equal to its base have no fate`()
// branchFate(repo, "nope") == null; repo without origin, branchFate(repo, "main") == null

@Test fun `uncommitted lists modified and untracked files, not committed ones`()
```

- [ ] **Step 2: Run, expect FAIL**: `./gradlew test --tests dev.myair.actions.GitTest`
- [ ] **Step 3: Implement** `BranchFate`, `branchFate`, `uncommitted` as above.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `Read what became of a branch and the uncommitted files of a work tree`

---

### Task 3: The dashboard model

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/Dashboard.kt`
- Create: `src/main/kotlin/dev/myair/dashboard/README.md` (package index, as other packages have)
- Test: `src/test/kotlin/dev/myair/dashboard/DashboardTest.kt`

**Interfaces:**
- Consumes: `ClaudeSession.asks`, `BranchFate` (Tasks 1–2), `SessionFolders.placeOf/taskOf/ancestry/planFolderAt/find`, `LiveSession`, `SessionStatus`, `PlanStatus`.
- Produces:

```kotlin
/** Git facts of one session: a file it wrote is uncommitted; its branch's fate (null: no branch, no ref, or its base). */
data class SessionGit(val uncommitted: Boolean, val fate: BranchFate?)

sealed interface Chip {
    data class BriefTasks(val done: Int, val total: Int) : Chip   // "brief 2/5"
    data object Uncommitted : Chip                                // "uncommitted"
    data object Unpushed : Chip                                   // "unpushed"
    data class PlanTask(val id: String, val status: PlanStatus) : Chip // "T12 waiting"
    data object Asks : Chip                                       // "asks"
    data class Merged(val pr: Int?) : Chip                        // "PR #7617" / "merged"
    data object Pending : Chip                                    // git not read yet
    data class GitError(val message: String) : Chip
}
fun chipText(chip: Chip): String

data class Row(val session: ClaudeSession, val chips: List<Chip>)
data class Area(val name: String, val rows: List<Row>)
data class Dashboard(val now: List<ClaudeSession>, val unfinished: List<Area>, val finished: List<Row>, val recent: Int, val older: Int)

/** [git]: per session id; absent = not read yet (Pending), failure = GitError. [sessions] arrive newest first. */
fun dashboardOf(
    sessions: List<ClaudeSession>, live: Map<String, LiveSession>, settled: Set<String>, unread: Set<String>,
    folders: SessionFolders, git: Map<String, Result<SessionGit>>, now: Instant,
): Dashboard
```

Rules: R3–R9, R12, R15, R16 of the spec. Area (R7): `placeOf` → if `planFolderAt` → that plan folder; else the ancestry element at index `min(depth, 2) - 1`; name = names of the ancestry down to it joined by `" / "`; `placeOf` null → last path segment of `session.project`. `recent`/`older`: sessions not settled with `lastMessage` within / before 2 days of `now`. A merged session's row gets `Chip.Merged` (Finished, or Unfinished when `Uncommitted`). Pending or GitError rows never go to Finished.

- [ ] **Step 1: Write the failing tests** (helpers: `session(id, time, ...)`, `live(status)` as in `PinnedSessionsTest`; a `SessionFolders` built from `Folder`s)

```kotlin
@Test fun `settled sessions appear nowhere`()                                       // R3
@Test fun `now lists needing input, then working, then unread, newest first, each once`() // R4, R5
@Test fun `a session with a signal is unfinished, one without is finished`()        // R6
@Test fun `each signal makes its chip`()                                            // R8 a–e, R12 texts via chipText
@Test fun `a merge cancels brief, unpushed, plan task and asks, but not uncommitted`() // R9
@Test fun `areas are the plan folder, else the folder at depth two, else the project`() // R7, ordering by newest
@Test fun `a session whose git is not read is pending and never finished`()         // R15
@Test fun `a git failure shows its message and keeps the session unfinished`()      // R16
@Test fun `a shared checkout's dirt reaches only sessions the facts mark uncommitted`() // Review Focus 2
```

- [ ] **Step 2: Run, expect FAIL**: `./gradlew test --tests dev.myair.dashboard.DashboardTest`
- [ ] **Step 3: Implement** `Dashboard.kt` and the README.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `Classify sessions for the dashboard`

---

### Task 4: Reading git facts off the EDT with R17's cache

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/GitFacts.kt`
- Test: `src/test/kotlin/dev/myair/dashboard/GitFactsTest.kt`

**Interfaces:**
- Consumes: `Git.head`, `Git.uncommitted`, `Git.branchFate`, `SessionGit`.
- Produces:

```kotlin
/** Git facts per session, read per work tree root and reused while the root's stamps and its sessions are unchanged. Not thread-safe: one reader thread. */
class GitFacts {
    /** How many root reads ran; for tests. */
    var rootReads = 0; private set
    /** Blocking; call off the EDT. [sessions]: the ones not in Now and not settled. */
    fun read(sessions: List<ClaudeSession>): Map<String, Result<SessionGit>>
}
```

Per session: file roots = `Git.head(parent of each changedFile, nearest existing)?.root`, files outside a repository dropped; branch root = `Git.head(workDir)?.root ?: Git.head(project)?.root` (refs are shared by every work tree of a repository, so the project's checkout finds a removed worktree's branch). Per root, one read: `uncommitted(root)` and `branchFate(root, b)` for each distinct `lastBranch` of its sessions. Cache key per root: mtimes of `HEAD` and `index` in the root's git dir, `packed-refs` and `refs/heads/<b>` (each branch) in the common dir, plus the upstream and base refs those branches name (resolved at the read and kept in the entry), plus the set of `(session id, lastMessage, changedFiles)` it serves. Git dirs come from `git rev-parse --git-dir --git-common-dir` once per root path. A root whose read throws gives `Result.failure` to every session it serves.

- [ ] **Step 1: Write the failing tests** (real git in `@TempDir`, as `GitTest`; sessions built with `changedFiles`, `workDir`, `project`, `lastBranch`)

```kotlin
@Test fun `only files the session wrote count as uncommitted`()
@Test fun `a second read with nothing changed runs no root read`()            // rootReads unchanged
@Test fun `a commit in the work tree rereads its root`()                     // HEAD/index stamp
@Test fun `a session with a newer last message rereads its roots`()          // Review Focus 1
@Test fun `a session joining the list rereads its roots`()                   // "left Now"
@Test fun `files in two repositories are each checked in their own`()        // R8b
@Test fun `a removed worktree reads its branch in the project checkout`()    // Review Focus 4
@Test fun `a failing root fails every session it serves`()
```

- [ ] **Step 2: Run, expect FAIL**: `./gradlew test --tests dev.myair.dashboard.GitFactsTest`
- [ ] **Step 3: Implement** `GitFacts`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `Read the dashboard's git facts per work tree with a stamp cache`

---

### Task 5: The dashboard tab: file system, single instance, opening

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/DashboardFiles.kt` (`DashboardVirtualFile`, `DashboardFileSystem`, `DashboardFiles` application service)
- Create: `src/main/kotlin/dev/myair/dashboard/OpenDashboardAction.kt`
- Create: `src/main/kotlin/dev/myair/dashboard/DashboardEditor.kt` (provider and an editor showing a placeholder until Task 6)
- Modify: `src/main/resources/META-INF/plugin.xml` (virtualFileSystem `myair-dashboard`, fileEditorProvider, action `dev.myair.OpenSessionsDashboard` "Open Sessions Dashboard" in `ToolsMenu`)
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt` (toolbar: `ActionManager.getInstance().getAction("dev.myair.OpenSessionsDashboard")` beside the new-session actions)
- Test: `src/test/kotlin/dev/myair/dashboard/DashboardFilesTest.kt`

**Interfaces:**
- Produces: `DashboardFiles.getInstance().file: DashboardVirtualFile` (one per IDE); `fun dashboardTabsToClose(openIn: List<Pair<String, Boolean>>): List<String>` (pure: given windows in restore order with whether each holds the dashboard, the window ids whose tab must close: all holding it but the first), used on project open.

Pattern: copy `PlanVirtualFile`/`PlanFileSystem` (`LightVirtualFile`, `OptionallyIncluded`, `isIncludedInEditorHistory = false`, path `sessions`, protocol `myair-dashboard`). `OpenDashboardAction`: search `ProjectManager.getInstance().openProjects` for a `FileEditorManager` whose `isFileOpen(file)`; found → `ProjectUtil.focusProjectWindow(p, true)` and `openFile(file, true)` there; else open in the action's project. R2 dedupe: a `ProjectActivity` (or `FileEditorManagerListener` on file opened) that, when the dashboard file opens in a project while another open project already shows it, closes it in the newer one; the pure `dashboardTabsToClose` decides.

- [ ] **Step 1: Failing test** `only the first window restored keeps the dashboard` for `dashboardTabsToClose`.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew test --tests dev.myair.dashboard.DashboardFilesTest`
- [ ] **Step 3: Implement** the files, action, provider (`FileEditorPolicy.HIDE_DEFAULT_EDITOR`, type id `myair-dashboard`) and registrations.
- [ ] **Step 4: Run** `./gradlew test buildPlugin`, expect PASS and a zip.
- [ ] **Step 5: Manual check in `runIde`** (command from CLAUDE.md): Tools › Open Sessions Dashboard opens the tab; again focuses it; from a second project window it brings the first window forward (R1); restart with the tab open in two windows leaves one (R2); Recent Files does not list it.
- [ ] **Step 6: Commit** `Open the sessions dashboard as one tab per IDE`

---

### Task 6: The dashboard panel, docs and sandbox walk

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/DashboardPanel.kt`
- Modify: `src/main/kotlin/dev/myair/dashboard/DashboardEditor.kt` (host the panel, subscribe, run `GitFacts` on a bounded single-thread executor with `LatestRead`)
- Modify: `CLAUDE.md` (a paragraph under "How the pieces fit": the dashboard, its sections, signals, `GitFacts` cache), `src/main/kotlin/dev/myair/dashboard/README.md`

**Interfaces:**
- Consumes: `dashboardOf`, `chipText`, `GitFacts.read`, `DashboardFiles`, `ClaudeSessionOpener.open(project, session)`, `ClaudeSessionsService.updatePins { it.settle(ids) }`, `sessionIcon` (`SessionIcons.kt`), composer pieces (`prompt/Composer.kt`: `Rounded`, `Chip`, `CARD`, `BAR`), `PaneFont`.

Behaviour: layout of the spec's "Layout"; header counts `recent`/`older`; Now rows with status icon, title, age; Unfinished: a card per `Area`, rows with icon, title, age, chips (Pending as a muted "…", GitError red with the message as tooltip); click → `ClaudeSessionOpener.open`; hover Settle → `updatePins { it.settle(listOf(id)) }` (R13); Finished folded with count, unfolded rows plus Settle All → `Messages.showYesNoDialog("Settle N sessions?")` on the ids shown when clicked (R14). On every service notification: build the model with the last facts, request a facts read for the sessions not in Now and not settled; when facts land, rebuild. Rebuild only the sections whose part of `Dashboard` changed (compare `now`, `unfinished`, `finished` with the previous model).

- [ ] **Step 1: Implement** the panel and the editor wiring.
- [ ] **Step 2: Run** `./gradlew test buildPlugin`, expect PASS.
- [ ] **Step 3: Sandbox walk** (`runIde`): R4/R5 with a working session and an unread one; R12 chips on a session with an unfinished brief and on one with uncommitted files in its worktree; R13 click opens, Settle removes it (R3); R14 Settle All with confirmation; R15 rows show "…" briefly after opening; R17: commit the uncommitted file, the row loses `uncommitted` after the next refresh; a merged branch (`git fetch` in rtm-cloud) shows `PR #<n>` under Finished.
- [ ] **Step 4: Update** `CLAUDE.md` and the package README; fill the Requirements evidence below.
- [ ] **Step 5: Run** the full `./gradlew test` (not filtered) and, when available, `my_air_spec_check` with this plan's absolute path; fix what it reports.
- [ ] **Step 6: Commit** `Show the sessions dashboard`

---

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 5 | not done: sandbox walk not run |
| R2 | 5 | not done: sandbox walk not run |
| R3 | 3, 6 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R4 | 3 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R5 | 3 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R6 | 3 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R7 | 3 | commit `fd47a8e`; not done: no test of plan or depth-2 areas |
| R8 | 1, 3, 4 | commit `19265fb`; commit `11d0675`; test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R9 | 3 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R10 | 2 | test `GitTest` › `a branch merged by a pull request merge is merged with its number, and a commit after the merge is new work` |
| R11 | 1, 4 | commit `19265fb`; commit `11d0675` |
| R12 | 3, 6 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R13 | 6 | commit `b4a0005`; not done: sandbox walk not run |
| R14 | 6 | commit `b4a0005`; not done: sandbox walk not run |
| R15 | 3, 6 | test `DashboardTest` › `sessions split into now, unfinished by signal and finished, merged work ending all but uncommitted files` |
| R16 | 3, 4 | commit `fd47a8e` |
| R17 | 2, 4, 6 | commit `11d0675`; not done: no test, sandbox walk not run |
