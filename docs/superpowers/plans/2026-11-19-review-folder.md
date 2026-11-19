# Review folder Implementation Plan

## TL;DR

- **Goal:** build the Review folder of the spec: a folder whose tab shows a branch's summary, explanation, reviewers, merged findings with triage and questions, its manager session beside it.
- **Approach:** 11 tasks: a spike on `claude --bg` first (it can stop the plan), then the pure model and logic with tests (JSON, ops, merging, derived facts, profiles, plan mirror), then the service and MCP tools, then UI (New Review, tab, panel, tree), then docs and a sandbox walk.
- **Needs your input:** none. Task 1 found `claude --bg` ignores `--session-id`; ruled with you: a report names its run (`W<n>`), and the run's session is read from the registry by job id after the start.
- **Risks:** the UI tasks (9, 10) are the largest and are checked mostly by hand in `runIde`; reviewer jobs cost tokens during the sandbox walk (use a profile of one cheap reviewer).
- **Out of scope:** as in the spec: no Bitbucket calls from the plugin, no PR comment replies, gutter marks, dashboard, taskbar, profile editor.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

| Task | Status | Commit |
|---|---|---|
| 1 | blocked: --session-id ignored by claude --bg | `98a88b0` |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |
| 6 | | |
| 7 | | |
| 8 | | |
| 9 | | |
| 10 | | |
| 11 | | |

**Goal:** a Review folder kind with its own editor tab, reviewers run as `claude --bg` jobs reporting through MCP, findings merged and triaged, blockers mirrored into a linked plan.

**Architecture:** a review folder is an ordinary folder of `structure.json`; its data is `~/.my-air/reviews/<folderId>/review.json`, read and written only through `ReviewService` (application service). Pure logic (`review/`) is tested in JUnit; the tab (`reviewview/`) mirrors `planview/` (virtual file system, files service, editor with `SessionPane`, opener).

**Tech Stack:** Kotlin, IntelliJ Platform 263 (local IU), Gson, JUnit 5, `git` and `claude` CLIs.

**Spec:** `docs/superpowers/specs/2026-11-18-review-folder-design.md`

## Global Constraints

- Code, comments, strings, test names in English; tests are backtick sentences, plain JUnit 5, no platform services.
- No fallbacks: unknown JSON keys, formats, ops, fields, severities and states throw naming the file or change; `check`/`require` over silent defaults.
- `structure.json` format unchanged; review data never deleted by the plugin (R3).
- `review.json` `format` 1; `review-profiles.json` read cached by modification time.
- Files under `~/.my-air` written with `writeAtomically`.
- Classes registered in `plugin.xml` / `my-air-mcp.xml` must be updated on a move.
- Run `./gradlew test buildPlugin` green before every commit of a code task.
- The My Air plan `my-air/plans/Węzeł Review brancha-PR – propozycja` holds the requirements; keep its task status current.
- Every commit's trailer block ends with `Plan-Task: <n>/11` and `Plan: docs/superpowers/plans/2026-11-19-review-folder.md`.

## Review Focus

1. A reviewer reporting twice, or a report naming a run the review does not have → second replaces first (R17), unknown run refused; tests in Task 3.
2. Two reviewers reporting the same issue three lines apart, plus a third four lines apart → two findings, not one or three; tests in Task 3.
3. `review.json` edited by hand into a bad state (unknown key, finding `fixed` without commit) → load throws naming the file, the panel shows the error, nothing is overwritten; tests in Task 2, error display in Task 10.
4. HEAD rewritten (rebase, force push) so the reviewed commit is gone → header says the commit is unknown instead of a count, `maybeFixed` is false for findings at that commit and no exception escapes the refresh; tests in Task 4.
5. A linked plan deleted or its blocker removed by hand → mirror stops for a plan gone, and recreates a blocker whose id no longer exists in the plan instead of updating a missing id; tests in Task 6.

---

### Task 1: Spike: `claude --bg` takes what a reviewer needs

**Files:**
- Modify: this plan (record the result under this task)

- [x] **Step 1: Run in a scratch git repository**

```bash
claude --bg --session-id 11111111-2222-3333-4444-555555555555 --model haiku --append-system-prompt "Reply with the word ok and stop." -- "say ok"
```

Expected: exit 0, stdout naming a job id; `~/.claude/sessions/<pid>.json` with `kind` `bg`, that `sessionId` and a `jobId`; `claude logs <jobId>` showing `ok`.

- [x] **Step 2: Record** the exact stdout format (to write `parseJobId`), whether `--session-id` was honoured, whether `--effort` is accepted with `--bg`, and stop the job with `claude stop <jobId>`.

- [x] **Step 3: Decide.** All honoured → continue. Any refused → stop here, set the row `blocked: <flag> refused by claude --bg`, report to the user; do not work around it.

**Result:** (claude 2.1.328, run in this worktree: a scratch repository outside it was refused with "Workspace not trusted. Run `claude` in <dir> once and accept the trust prompt", exit 1, so the spike used the already trusted worktree)

`claude --bg --session-id <uuid> --model haiku --append-system-prompt "Reply with the word ok and stop." -- "say ok"`: exit 0. stdout, verbatim:

```
backgrounded · f2b8ed26
  claude agents             list sessions
  claude attach f2b8ed26    open in this terminal
  claude logs f2b8ed26      show recent output
  claude stop f2b8ed26      stop this session
```

stderr: `warning: --bg manages the session id; ignoring --session-id (use --resume <id> to continue an existing session)` and `Starting background service…`.

- `--session-id`: **refused** (warning above; exit 0). The session got id `f2b8ed26-573c-468a-8919-2a760501c116`, not the requested one; no transcript with the requested uuid exists. The job id is the first 8 hex characters of the session id.
- `--model haiku`: honoured (banner "Haiku 5.5").
- `--append-system-prompt`: honoured (the text is in the transcript; the reply was `ok`, which "say ok" alone would also give).
- `--effort low`: honoured (separate run, banner "Haiku 5.5 with low effort"; default without it: medium).
- `~/.claude/sessions/52568.json`: `kind` `bg`, `jobId` `f2b8ed26`, `sessionId` `f2b8ed26-573c-468a-8919-2a760501c116`, `status` `idle` after the reply.
- Transcript `~/.claude/projects/<encoded cwd>/<sessionId>.jsonl` exists under the generated session id.
- `claude logs <jobId>`: the rendered terminal screen with ANSI escapes (shows `say ok` and `● ok`), not plain text.
- `claude stop <jobId>` and `claude rm <jobId>`: both jobs stopped and removed.
- Job id format: stdout line 1 matches `^backgrounded · ([0-9a-f]{8})$` (separator is U+00B7); the session id must be read afterwards from `~/.claude/sessions/<pid>.json` by `jobId`.


---

### Task 2: Review model and its files (R1, R2, R3)

**Files:**
- Create: `src/main/kotlin/dev/myair/store/Reviews.kt`
- Test: `src/test/kotlin/dev/myair/store/ReviewsTest.kt`

**Interfaces:**
- Produces:
  - `enum class Severity { blocker, major, minor, nit }` (ordinal order = severity, highest first), `enum class FindingState { open, accepted, rejected, fixed, posted }`
  - `data class Report(val run: String, val reviewer: String, val commit: String, val line: Int, val severity: Severity, val summary: String, val failureScenario: String)`
  - `data class Finding(val id: String, val file: String, val category: String, val reports: List<Report>, val state: FindingState = FindingState.open, val reason: String? = null, val fixCommit: String? = null, val comment: String? = null)` with `val line get()` (newest report's) and `val severity get()` (highest of reports); `init` requires `reason` for `rejected`, `fixCommit` for `fixed`, `comment` for `posted`, `reports` not empty
  - `data class Run(val id: String, val reviewer: String, val session: String?, val job: String?, val range: String, val started: String, val reported: String? = null)`
  - `data class Chapter(val title: String, val text: String, val files: List<String>)`, `data class Question(val id: String, val text: String, val reviewer: String?, val resolved: Boolean = false)`
  - `data class BranchReview(branch, base, dir: Path, pr: Int?, plan: String?, manager: PlanManager?, summary: String?, chapters, runs, findings, questions, next: Map<String, Int>, blockers: Map<String, String> = emptyMap())` with `fun nextId(prefix: String): Pair<String, BranchReview>`
  - `fun BranchReview.toJson(): String`, `fun parseReview(text: String, source: Path): BranchReview`
  - `class ReviewFiles(val root: Path)` with `fun readAll(): Map<String, Result<BranchReview>>` (folder id → review, failures kept per review), `fun write(folderId: String, review: BranchReview)`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a review round-trips through json`()            // every field set, parse(toJson(r)) == r
@Test fun `an unknown key throws naming the file`()          // assertThrows, message contains source path and the key
@Test fun `a format other than 1 throws`()
@Test fun `a rejected finding needs a reason`()              // also fixed→fixCommit, posted→comment
@Test fun `a finding takes its line from the newest report and the highest severity`()
@Test fun `ids are numbered per prefix and never reused`()   // nextId("F") twice → F1, F2; next survives json
@Test fun `readAll keeps a broken review as a failure beside good ones`() // temp dir, two folders
@Test fun `blockers is written only when not empty`()        // json has no "blockers" key for an empty map
```

- [ ] **Step 2: Run** `./gradlew test --tests dev.myair.store.ReviewsTest` → FAIL (unresolved references).
- [ ] **Step 3: Implement** in `Reviews.kt`; JSON with Gson tree API and an explicit key set per object (as `parsePresets` does: `PRESET_KEYS`, `only`), `failure_scenario` as the JSON key of `failureScenario`, dates as ISO strings. `ReviewFiles.write` uses `writeAtomically(root.resolve(folderId).resolve("review.json"), …)`.
- [ ] **Step 4: Run** the class → PASS; `./gradlew test` → PASS.
- [ ] **Step 5: Commit** `Review model and review.json files`.

---

### Task 3: Ops, reports and merging (R14, R16, R17, R23, R25)

**Files:**
- Create: `src/main/kotlin/dev/myair/review/ReviewChanges.kt`, `src/main/kotlin/dev/myair/review/Findings.kt`
- Test: `src/test/kotlin/dev/myair/review/ReviewChangesTest.kt`, `src/test/kotlin/dev/myair/review/FindingsTest.kt`

**Interfaces:**
- Consumes: Task 2 model.
- Produces:
  - `data class IncomingFinding(val file: String, val line: Int, val category: String, val severity: Severity, val summary: String, val failureScenario: String)`
  - `fun mergeFindings(review: BranchReview, run: Run, commit: String, incoming: List<IncomingFinding>): BranchReview`
  - `object ReviewChanges { fun apply(review: BranchReview, changes: String): BranchReview; fun report(review: BranchReview, run: String, commit: String, findings: String, questions: String, now: Instant): BranchReview }`
  - Typed ops used by the UI: `fun BranchReview.setState(id: String, state: FindingState, reason: String? = null, commit: String? = null, comment: String? = null): BranchReview`, `setSummary(text: String?)`, `setChapters(List<Chapter>)`, `setPr(Int?)`, `addQuestion(text, reviewer: String?)`, `resolveQuestion(id)`, `addRun(Run)`, `setJob(runId, job)`, `setSession(runId, session)`, `removeRun(runId)` (only an unreported run)

`mergeFindings` (the rule the tests pin):

```text
1. drop every report whose run == run.id (R17), then drop findings left with no report
2. for each incoming finding, in order: candidates = findings with same file and category
   and |candidate.line - incoming.line| <= 3; join the nearest (ties: lowest id); else new F<next>
3. a joined finding keeps its state, reason, fixCommit and comment
```

- [ ] **Step 1: Write the failing tests**

```kotlin
// FindingsTest
@Test fun `two reports three lines apart in one file and category merge`()     // lines 10 and 13 → one finding, 2 reports
@Test fun `a report four lines away or of another category stays apart`()       // 10 and 14; same line other category
@Test fun `a joined finding takes the nearest candidate`()
@Test fun `a second report of the same run replaces the first`()                // run W1 reports F at 10, then nothing → no findings
@Test fun `a merged finding keeps its triage`()                                  // accepted F1 gets a new report, stays accepted
// ReviewChangesTest
@Test fun `ops apply in order and all or none`()                                 // second op invalid → review unchanged, throws
@Test fun `an unknown op or field throws naming the change`()
@Test fun `set_state rejected without a reason throws`()                         // likewise fixed without commit, posted without comment
@Test fun `a report naming no run of the review is refused`()
@Test fun `a report finding without a severity or with an unknown one records nothing`()
@Test fun `a report sets reported on its run and adds its questions`()           // Q<n> ids, reviewer = run's reviewer
```

- [ ] **Step 2: Run** both classes → FAIL.
- [ ] **Step 3: Implement**; JSON parsing of `changes`, `findings`, `questions` as `PlanChanges` does (`FIELDS` map op → allowed keys, a failure message naming the change's index and op).
- [ ] **Step 4: Run** → PASS; full `./gradlew test` → PASS.
- [ ] **Step 5: Commit** `Review ops, reports and finding merge`.

---

### Task 4: Derived facts (R21, R28, R29, R30) and the range diff

**Files:**
- Create: `src/main/kotlin/dev/myair/review/ReviewFacts.kt`
- Modify: `src/main/kotlin/dev/myair/actions/Git.kt` (add `changedLines`, `commitsBetween`)
- Test: `src/test/kotlin/dev/myair/review/ReviewFactsTest.kt`, `src/test/kotlin/dev/myair/actions/GitTest.kt`

**Interfaces:**
- Produces:
  - `enum class RunStatus { running, reported, failed }`; `fun runStatus(run: Run, liveJobs: Set<String>): RunStatus` (a run with `job == null` counts as running: it is starting)
  - `fun maybeFixed(finding: Finding, changed: Map<String, List<IntRange>>?): Boolean` (`changed` is for the finding's newest commit; null = commit unknown → false)
  - `fun blockerCount(review: BranchReview): Int`, `fun toDecide(review: BranchReview): Int` (open findings not blocker), `fun reviewedCommit(review: BranchReview): String?` (commit of the newest reported run)
  - `data class Precision(val kept: Int, val triaged: Int, val reviews: Int)`; `fun precision(reviews: Collection<BranchReview>): Map<String, Precision>` (kept = accepted+fixed+posted reports' findings, triaged = kept+rejected, counted per reviewer name per finding)
  - `fun Git.changedLines(root: Path, from: String): Map<String, List<IntRange>>?` — old-side line ranges of `git diff -U0 <from>` (work tree included); null when `from` is no commit
  - `fun Git.commitsBetween(root: Path, from: String): Int?` — `git rev-list --count <from>..HEAD`, null when `from` is no commit

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a run is running while its job is live and unreported`()     // and running with job null; reported once reported; failed when job gone
@Test fun `a finding is maybe fixed when a changed range comes within three lines`() // range 14..14 vs line 11 → true; 15..15 → false
@Test fun `an unknown commit makes nothing maybe fixed`()                // changed = null → false
@Test fun `blockers are blocker-severity findings open or accepted`()
@Test fun `precision counts kept over triaged per reviewer across reviews`() // 2 reviews, reviewer a: 3 kept 1 rejected → Precision(3,4,2)
// GitTest (real git in temp dir)
@Test fun `changedLines gives old-side ranges since a commit, uncommitted edits included`()
@Test fun `changedLines and commitsBetween are null for a commit that is gone`()
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (`@@ -a,b` parsing; `b` 0 means a deletion after line `a`, range `a..a`). **Step 4: Run** → PASS, full tests PASS.
- [ ] **Step 5: Commit** `Review facts: run status, maybe fixed, blockers, precision`.

---

### Task 5: Profiles, prompts and reviewer start (R9, R18, R20, R27)

**Files:**
- Create: `src/main/kotlin/dev/myair/store/ReviewProfiles.kt`, `src/main/kotlin/dev/myair/review/ReviewPrompts.kt`, `src/main/kotlin/dev/myair/review/Reviewers.kt`
- Test: `src/test/kotlin/dev/myair/store/ReviewProfilesTest.kt`, `src/test/kotlin/dev/myair/review/ReviewPromptsTest.kt`

**Interfaces:**
- Produces:
  - `data class Reviewer(val name: String, val prompt: String, val model: String? = null, val effort: String? = null)`, `data class ReviewProfile(val name: String, val reviewers: List<Reviewer>)`
  - `fun parseProfiles(text: String): List<ReviewProfile>` (unknown keys throw, effort in `EFFORTS`, names unique); `class ReviewProfileStore(val file: Path) { fun read(): Result<List<ReviewProfile>> }` (missing file → success, empty; cached by mtime)
  - `fun reviewerPrompt(folderPath: String, run: String, review: BranchReview, range: String): String` — names folder path, run id, branch, base, range; tells it to review only the range and call `my_air_review_report` once at the end with that run id; one line (no newline)
  - `fun reviewManagerPrompt(folderPath: String, review: BranchReview): String` — R9's rules
  - `fun reviewerArgs(reviewer: Reviewer, prompt: String): List<String>` = `--bg [--model m] [--effort e] --append-system-prompt <prompt> -- <reviewer.prompt>` (no `--session-id`: `claude --bg` ignores it, Task 1)
  - `fun parseJobId(stdout: String): String` — the first line must match `^backgrounded · ([0-9a-f]{8})$` (U+00B7 separator, Task 1's literal); anything else throws naming the output
  - `fun rangeFor(review: BranchReview, mergeBase: String, head: String, newOnly: Boolean): String` (`<reviewed>..<head>` when `newOnly` and a reviewed commit exists, else `<mergeBase>..<head>`)
  - `object Reviewers { fun start(dir: Path, args: List<String>): String }` — runs `claude` with `args` in `dir` off the EDT, 30 s timeout, non-zero exit throws with stderr, returns `parseJobId(stdout)`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `profiles parse and an unknown key throws`()
@Test fun `a missing profiles file means no profiles`()
@Test fun `reviewer args put the prompt last after a double dash`()
@Test fun `the reviewer prompt names folder, run, range and the report tool on one line`()
@Test fun `the manager prompt names both review tools and forbids starting reviewers`()
@Test fun `the range is new commits only when asked and a reviewed commit exists`()
@Test fun `parseJobId reads the id claude prints and throws on other output`()   // literal from Task 1
```

- [ ] **Step 2–4:** run → FAIL, implement, run → PASS, full tests PASS.
- [ ] **Step 5: Commit** `Review profiles, prompts and reviewer start`.

---

### Task 6: Plan mirror (R31)

**Files:**
- Create: `src/main/kotlin/dev/myair/review/ReviewMirror.kt`
- Test: `src/test/kotlin/dev/myair/review/ReviewMirrorTest.kt`

**Interfaces:**
- Consumes: `folders.Plan`, `folders.Review`/`Blocker`/`ReviewStatus`, `PlanChanges.apply(structure, folderId, changes, today)`.
- Produces: `data class Mirror(val changes: String, val blockers: Map<String, String>)`; `fun mirrorToPlan(review: BranchReview, plan: Plan, liveJobs: Set<String>): Mirror?` — null when nothing would change. Changes JSON for `PlanChanges`: one `set_review` (status per R31, commit = `reviewedCommit`), `add_blocker` for a blocker finding without a mapped id or whose mapped id is missing from the plan, `update` (`text`, `resolved`) for mapped ones. Since `add_blocker` returns no id, `blockers` maps new ones by predicting `B<plan.next["B"] ?: 1>` in order; a test pins that against `PlanChanges`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a blocker finding becomes a plan blocker and its id is recorded`()      // apply changes with PlanChanges; plan has B1 with text "F1 a.kt:3 race"
@Test fun `a fixed or rejected blocker finding resolves its plan blocker`()
@Test fun `plan blockers the review did not make are left alone`()
@Test fun `a mapped blocker deleted from the plan is added again`()
@Test fun `the review status is in progress while a run runs, done after a report, else not done`()
@Test fun `nothing to change gives no mirror`()
```

- [ ] **Step 2–4:** FAIL, implement, PASS, full tests PASS.
- [ ] **Step 5: Commit** `Review blockers mirrored into the linked plan`.

---

### Task 7: ReviewService and MCP tools (R1, R3, R14–R17, R31, R34)

**Files:**
- Create: `src/main/kotlin/dev/myair/review/ReviewService.kt`, `src/main/kotlin/dev/myair/mcp/ReviewMcpToolset.kt`, `src/main/kotlin/dev/myair/review/ReviewJson.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (service), `src/main/resources/META-INF/my-air-mcp.xml` (toolset), `src/main/kotlin/dev/myair/editor/SessionSystemPrompt.kt` (name the tools), `src/test/kotlin/dev/myair/editor/SessionSystemPromptTest.kt`
- Test: `src/test/kotlin/dev/myair/review/ReviewJsonTest.kt`

**Interfaces:**
- Consumes: Tasks 2–6.
- Produces:
  - `@Service(APP) class ReviewService` with `val reviews: Map<String, Result<BranchReview>>` (folder ids in the structure only, R3), `fun review(folderId: String): BranchReview` (throws for a failure), `fun update(folderId: String, op: (BranchReview) -> BranchReview): BranchReview` (EDT; writes, mirrors to the plan through `ClaudeSessionsService.updateFolders(ChangeSource.AUTO)` when `plan` names a folder with a plan, notifies), `fun subscribe(disposable, listener: () -> Unit)`, `val facts: Map<String, ReviewGit>` where `data class ReviewGit(val state: GitState?, val head: String?, val sinceReviewed: Int?, val changed: Map<String, Map<String, List<IntRange>>?>, val error: String?)` (changed keyed by commit), on each `ClaudeSessionsService` notification it gives every run with a job and no session the `sessionId` of the live entry whose `job` equals it (`setSession`) and files that session in the review folder (R22); refreshed off the EDT on `ClaudeSessionsService` notifications and on file events under `~/.my-air/reviews` (a `VirtualFileManager` / NIO watch as `ClaudeHomeWatcher` debounces), latest read wins
  - `fun reviewJson(path: String, review: BranchReview, git: ReviewGit?, liveJobs: Set<String>): String` (R15: derived `maybe_fixed` and run `status`)
  - MCP: `my_air_review_get(folder)`, `my_air_review_apply(folder, changes)`, `my_air_review_report(folder, run, commit, findings, questions)`; `folder` is a folder path; a folder that is no review throws `no review folder at '<path>'`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `review json carries maybe fixed and run status`()
@Test fun `both prompts name every folder tool`()   // existing test extended: ReviewMcpToolset's tools too
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; descriptions of the three tools list every op and field, as `my_air_plan_apply`'s does. **Step 4:** PASS, full tests PASS, `./gradlew buildPlugin` PASS.
- [ ] **Step 5: Commit** `ReviewService and the review MCP tools`.

---

### Task 8: New Review (R4, R5, R6)

**Files:**
- Create: `src/main/kotlin/dev/myair/reviewview/NewReview.kt`
- Modify: `src/main/kotlin/dev/myair/toolwindow/FolderEditing.kt` (or the tree's menu group where New Claude Session sits: actions on session, worktree, project and folder rows; Add Plan hidden on a review folder, R1), `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/dev/myair/reviewview/NewReviewTest.kt`

**Interfaces:**
- Produces:
  - `fun reviewParent(structure: SessionFolders, place: String?): String?` — `place` itself, or the plan folder's parent when `place` is or lies in a plan folder; also returns the plan folder id: `data class ReviewPlace(val parent: String?, val plan: String?)`
  - `fun reviewDir(worktrees: List<LinkedWorktree>, mainRoot: Path, mainBranch: String?, branch: String, worktreesDir: Path): Pair<Path, Boolean>` (dir, needs a new worktree)
  - `fun startReview(project: Project, branch: String, dirHint: Path, place: String?, pr: Int?)` — EDT, git off the EDT: creates the worktree when needed (`Git.addWorktree`), the folder `Review: <branch>` (`createFolder`, a name taken gets ` (2)`…), `review.json` with `base = Git.baseOf(dir)`, then `ReviewOpener.open` (Task 9)
  - Action `NewReviewAction` asking the branch (searchable popup of local branches, only on project/folder rows) and an optional PR number (`Messages.showInputDialog`, digits or empty)

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a review from inside a plan goes beside the plan and links it`()
@Test fun `a review elsewhere goes into the place`()
@Test fun `the dir is the worktree holding the branch, else the main checkout holding it, else a new worktree`()
```

- [ ] **Step 2–4:** FAIL, implement, PASS, full tests PASS.
- [ ] **Step 5: Commit** `New Review: folder, worktree and review.json`.

---

### Task 9: Review tab and its manager (R7, R8, R9)

**Files:**
- Create: `src/main/kotlin/dev/myair/reviewview/ReviewFiles.kt` (`ReviewVirtualFile`, `ReviewFileSystem` `myair-review://<folderId>`, `ReviewTabs` service), `ReviewEditor.kt` (provider + editor), `ReviewOpener.kt`, `ReviewTabsProviders.kt` (title, icon)
- Modify: `src/main/kotlin/dev/myair/planview/ShownSessions.kt` (`sessionFileOf`, `tabFileOf` also for review tabs), `src/main/kotlin/dev/myair/editor/ClaudeSessionOpener.kt` (a review's manager opens its review), `src/main/kotlin/dev/myair/service/ClaudeSessionsService.kt` (move the manager on `/clear`, as `PlanManagerStore.moveSession`), `plugin.xml`
- Test: `src/test/kotlin/dev/myair/reviewview/ReviewFilesTest.kt`

**Interfaces:**
- Consumes: `managerStart(existing, newId, cwd, project, hasTranscript)` from `PlanFiles.kt` (reuse, not copy), `PromptFiles.write`, `reviewManagerPrompt` (Task 5), `ReviewService.update`.
- Produces: `class ReviewTabs { fun file(folderId): ReviewVirtualFile; fun manager(folderId): ClaudeSessionVirtualFile?; fun startManager(folderId: String, ideProject: Project): ClaudeSessionVirtualFile; fun reviewOf(file: ClaudeSessionVirtualFile): ReviewVirtualFile? }`; `fun parseReviewTabPath(path: String): String`; `object ReviewOpener { fun open(project: Project, folderId: String) }`; `ReviewEditor(project, file): FileEditor, SessionPaneHost` exposing `val panel: ReviewPanel` slot for Task 10 (a placeholder label until then).

`startManager` stores the manager in `review.json` (`manager`), starts it in `review.dir` with `--append-system-prompt-file <PromptFiles.write(prompts, reviewManagerPrompt(...))>`, files a new one in the review folder (`ChangeSource.AUTO`).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a review tab path round-trips its folder id`()
@Test fun `a manager entry resumes in its stored directory and a new one starts with the prompt file`() // pure part: args of startManager via a function managerArgs(...)
```

- [ ] **Step 2–4:** FAIL, implement, PASS, full tests PASS, `buildPlugin` PASS.
- [ ] **Step 5: Check in `runIde`:** Open Review on a review folder opens one tab (a second Open focuses it), the manager starts in the review's dir, the tab survives an IDE restart, `/clear` in the manager keeps the tab bound.
- [ ] **Step 6: Commit** `Review tab with its manager session`.

---

### Task 10: Review panel (R10–R13, R19, R21, R22, R24–R30, R32)

**Files:**
- Create: `src/main/kotlin/dev/myair/reviewview/ReviewView.kt` (pure model), `ReviewPanel.kt` (Swing), `ReviewEdits.kt` (inline edits of summary, reason, commit)
- Modify: `ReviewEditor.kt` (hosts the panel)
- Test: `src/test/kotlin/dev/myair/reviewview/ReviewViewTest.kt`

**Interfaces:**
- Consumes: `ReviewService`, `ReviewProfileStore`, `ReviewFacts`, `Reviewers.start`, `rangeFor`, `reviewerArgs`, `reviewerPrompt`, `showBaseDiff(project, state, selected)`, `SessionPane.send`, `ClaudeSessionOpener.open`/`startNew(..., folderId)`, `Composer.kt` (`Rounded`, `Chip`, `CARD`, `BAR`), `styledHtml`, `PaneFont`.
- Produces: `data class ReviewView(header: Header, summary: String?, chapters: List<ChapterView>, unexplained: List<FileChange>, runs: List<RunView>, findings: List<FindingView>, questions: List<Question>, resolvedQuestions: Int)`; `fun reviewViewOf(review, git: ReviewGit?, liveJobs, precision: Map<String, Precision>, filter: FindingFilter, sessions: Map<String, ClaudeSession>): ReviewView`; `enum class FindingFilter { all, blockers, open }`; `data class Header(branch, base, pr: Int?, prUrl: String?, reviewed: String?, head: String?, sinceReviewed: Int?, reviewedGone: Boolean)`; `fun bitbucketPrUrl(originUrl: String, pr: Int): String?` (null for a non-Bitbucket origin).

Panel behaviour, card by card (each folds; edits only from menus; rebuild only the card whose part of `ReviewView` changed, as `PlanPanel` does):
- Header: R10; Error card instead of everything when `ReviewService.reviews[id]` is a failure (Review Focus 3).
- Summary: Markdown; `...` → Edit Summary; Publish → `pane.send("Set the description of PR #<pr> to the review's summary (my_air_review_get).")`, disabled without `pr`.
- Explanation: chapters with files `+a −r` (grey "not changed" for files not in `state.changes`), then "Not explained"; file click → `showBaseDiff`.
- Reviewers: profile chooser (error / "no profiles in <file>" per R19), Run with New commits only / Whole branch when R27 applies; Run records each `Run` (`addRun`) before `Reviewers.start` off the EDT, then `setJob`; a start that throws removes its run (`removeRun`, else it would count as starting for good) and shows the exception's message on that reviewer's row until the next Run. Rows: status, findings count, duration, precision (`7/10 · 5 reviews`), click opens the session; grouped by run start, newest first.
- Findings: filter chips All / Blockers / Open with counts; head `<n> blockers · <m> to decide`; rows per R24 (`maybe fixed` dim per R28); menu per R25/R26 (Fix with Claude → `startNew(project, sessionProject, review.dir, args = listOf("--", findingMessage(f)), folderId = id)` and `setState(accepted)`; Post to PR → `pane.send(...)`; Open Diff).
- Questions: open ones with Resolve, resolved folded under a count.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `files no chapter names go to not explained`()
@Test fun `a chapter file that did not change is marked`()
@Test fun `findings filter to blockers and to open ones`()
@Test fun `a finding shows how many reviewers of the latest runs reported it over how many ran`() // 3/4
@Test fun `the header tells a reviewed commit that is gone`()
@Test fun `a bitbucket origin gives a pr url and another origin none`()  // git@bitbucket.org:ws/repo.git and https forms; github → null
```

- [ ] **Step 2–4:** FAIL, implement, PASS, full tests PASS, `buildPlugin` PASS.
- [ ] **Step 5: Check in `runIde`** with a profile of one reviewer (`haiku`, prompt `/code-review low`) on a small branch: Run starts a job, the row turns reported, findings appear merged, Accept/Reject/Fix with Claude work, the summary edits and Publish sends the manager a message.
- [ ] **Step 6: Commit** `Review panel: summary, explanation, reviewers, findings, questions`.

---

### Task 11: Tree row, docs and the sandbox walk (R22, R33, R35)

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt` and its renderer (review icon, blocker count on the right in the needs-input colour), the tree menu (Open Review), `src/main/resources/icons/review.svg` + `review_dark.svg`, `CLAUDE.md`, `src/main/kotlin/dev/myair/review/README.md`, `src/main/kotlin/dev/myair/reviewview/README.md`

- [ ] **Step 1:** Review folder rows get the icon and blocker count (from `ReviewService`), their sessions (manager, reviewers, fix sessions) stay their children (they are filed there).
- [ ] **Step 2:** `CLAUDE.md`: a paragraph on the review folder (files, service, tools, tab, profiles), package list gains `review` and `reviewview`; READMEs list the files and functions as the other packages' do.
- [ ] **Step 3: Walk in `runIde`** every requirement marked `checked:` below; fill Evidence.
- [ ] **Step 4:** `./gradlew test buildPlugin` PASS.
- [ ] **Step 5: Commit** `Review folder in the tree; docs`.

---

## Requirements evidence

Requirements and their evidence live in the My Air plan `my-air/plans/Węzeł Review brancha-PR – propozycja` (task ids: plan Task n is `T<217+n>`, so Task 1 is `T218`). The agent closing a task adds one `add_evidence` item per requirement it proves, through `my_air_plan_apply`, and sets the task done there too.
