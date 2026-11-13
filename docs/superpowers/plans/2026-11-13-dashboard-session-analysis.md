# Dashboard Session Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

| Task | Status | Commit |
|---|---|---|
| 1 | done | `2aa9f68` |
| 2 | done | `40ee127` |
| 3 | done | `a17f91f` |
| 4 | done | `4fa05b8` |
| 5 | done | `6b8ebbf` |
| 6 | done | `f8a465c`, `742f198` |

## TL;DR

- **Goal:** an Analyze button on the sessions dashboard has Haiku write, under each unfinished session's row, a verdict and what is left to do; sessions analysed and unchanged since are skipped.
- **Approach:** six tasks: transcript path in the scanner, analysis store, digest, `claude -p` runner with a run of 3 in parallel, model changes in `dashboardOf`, editor UI plus docs and a sandbox check.
- **Decisions needing input:** none; the spec is approved.
- **Risks:** the JSON envelope field of `--json-schema` output and whether `--setting-sources project` keeps the OAuth login are checked against the installed `claude` in Task 4 before code relies on them.
- **Out of scope:** what the spec puts out of scope (sections moved by the verdict, Now and settled sessions, Jira/Bitbucket).

**Goal:** Analyze action on the sessions dashboard: per-session Haiku verdict and "Left:" line, stored per session, skipped while current, limited by date.

**Architecture:** Pure pieces (`digestOf`, `parseAnalysis`, `AnalysisStore`, `dashboardOf` changes) are unit-tested; `AnalysisRun` drives an injected `(ClaudeSession) -> Analysis` on a pool of 3 so its concurrency and cancel are tested without `claude`; `DashboardEditor` wires it to the UI.

**Tech Stack:** Kotlin, IntelliJ Platform (IU-263), JUnit 5, Gson, `claude` CLI.

**Spec:** `docs/superpowers/specs/2026-11-13-dashboard-session-analysis-design.md`

## Global Constraints

- English in code, comments, strings and test names.
- No fallbacks: a malformed store file, a missing transcript, a reply not matching the schema throw; failures are shown on the row (R9), never swallowed.
- No process start, wait or file read of analyses on the EDT (R10).
- At most 3 analysis processes at a time, 2 minutes each (R5).
- Store directory `~/.my-air/analysis/` (`Homes.myAir.resolve("analysis")`), one `<sessionId>.json` per session, written with `writeAtomically` (R7).
- Digest limits: 2000 characters per prompt or message, 40000 in total, last 5 assistant texts (R6).
- Date choices 7, 14, 30 days or all, default 14, application property `dev.myair.dashboard.analysisDays` (R11).

## Review Focus

- A transcript removed (worktree deleted, session pruned) between the scan and its analysis: the row shows `analysis failed` with the missing path, the run goes on (test in Task 4).
- A session with no assistant text yet or with prompts made only of skills: the digest still builds and states that there are no agent messages (test in Task 3).
- `claude` answering with an error envelope (`is_error: true`, e.g. not logged in or rate-limited) with exit 0: counts as failed with its `result` text, never stored (test in Task 4).
- Cancel while processes run: each is killed with its descendants, nothing is written for them, the progress label goes away (test in Task 4).
- A session that gets a new message while its analysis runs: the stored `lastMessage` is the one the digest was made from, so the result is stale at once and not shown (test in Task 5).

---

## File Structure

- Modify `src/main/kotlin/dev/myair/claude/ClaudeSession.kt`, `ClaudeSessionScanner.kt`: `transcript` field; `promptText` made `internal`.
- Create `src/main/kotlin/dev/myair/dashboard/Analyses.kt`: `Verdict`, `Analysis`, `AnalysisStore`.
- Create `src/main/kotlin/dev/myair/dashboard/Digest.kt`: `Excerpt`, `excerptOf`, `digestOf`.
- Create `src/main/kotlin/dev/myair/dashboard/Analyzer.kt`: `analysisArgs`, `parseAnalysis`, `analyze`, `AnalysisRun`.
- Modify `src/main/kotlin/dev/myair/dashboard/Dashboard.kt`: `RowAnalysis`, chips, `analysisCandidates`.
- Modify `src/main/kotlin/dev/myair/dashboard/DashboardEditor.kt`: header controls, progress, `Left:` line.
- Tests next to each in `src/test/kotlin/dev/myair/...`.
- Docs: `CLAUDE.md` (dashboard paragraph), `src/main/kotlin/dev/myair/dashboard/README.md`.

### Task 1: The scanner records the transcript file that counts

**Files:**
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSession.kt:23-29`, `src/main/kotlin/dev/myair/claude/ClaudeSessionScanner.kt:153-160` and `:549`
- Test: `src/test/kotlin/dev/myair/claude/ClaudeSessionScannerTest.kt`

**Interfaces:**
- Produces: `ClaudeSession.transcript: Path?` (default `null`; set by the scanner to the `.jsonl` the session was built from, null only for sessions not scanned); `internal fun promptText(o: JsonObject): String?` reachable from `dev.myair.dashboard` (move it out of the companion to a top-level `internal fun` in the same file if needed).

- [x] **Step 1: Extend the test `a transcript copied into another project directory is one session, the copy with the latest message, else the longest`** with `assertEquals(<path of the copy with the latest message>, session.transcript)` for both of its cases.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.claude.ClaudeSessionScannerTest` — expected FAIL (unresolved `transcript`).
- [x] **Step 3: Add `val transcript: Path? = null` to `ClaudeSession`, pass `transcript = file` in `Transcript.toSession`, make `promptText` internal.**
- [x] **Step 4: Run** the same command — expected PASS; then `./gradlew test` — expected PASS.
- [x] **Step 5: Commit** `git commit -m "Scanner records the transcript file a session was read from"` with the trailers `Plan-Task: 1/6` and `Plan: docs/superpowers/plans/2026-11-13-dashboard-session-analysis.md`.

### Task 2: Analysis store

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/Analyses.kt`
- Test: `src/test/kotlin/dev/myair/dashboard/AnalysesTest.kt`

**Interfaces:**
- Produces:
  - `enum class Verdict(val label: String) { DONE("done"), OPEN("open"), UNCLEAR("unclear") }`, JSON values the labels.
  - `data class Analysis(val lastMessage: Instant, val verdict: Verdict, val left: String, val analyzedAt: Instant)` with `init { require(verdict == Verdict.DONE || left.isNotBlank()) }`.
  - `class AnalysisStore(val dir: Path) { fun readAll(): Map<String, Analysis>; fun write(sessionId: String, analysis: Analysis) }`; `readAll` of a missing dir is empty; a file that does not parse or misses a field throws naming the file. Instants as ISO-8601 strings.

- [x] **Step 1: Write tests** `a written analysis reads back by session id` (two sessions, `readAll()` equals the map written), `a malformed analysis file throws naming it` (`assertFailsWith<IllegalStateException>` whose message contains the file name), `an open verdict needs what is left` (`assertFailsWith<IllegalArgumentException> { Analysis(t, Verdict.OPEN, " ", t) }`), all in a `@TempDir`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.dashboard.AnalysesTest` — expected FAIL (unresolved).
- [x] **Step 3: Implement** with Gson and `writeAtomically`.
- [x] **Step 4: Run** — expected PASS.
- [x] **Step 5: Commit** "Store of session analyses in ~/.my-air/analysis", trailers `Plan-Task: 2/6`, `Plan: …`.

### Task 3: Digest of a session

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/Digest.kt`
- Test: `src/test/kotlin/dev/myair/dashboard/DigestTest.kt`

**Interfaces:**
- Consumes: `promptText` (Task 1), `Chip`/`chipText` (`Dashboard.kt`).
- Produces:
  - `data class Excerpt(val prompts: List<String>, val lastTexts: List<String>)`.
  - `fun excerptOf(lines: Sequence<String>): Excerpt` — human prompts in order (`type` `user`, not `isMeta`, not `isSidechain`, through `promptText`), and the text items of the last 5 `assistant` lines outside sidechains and `isMeta` that carry a `text` item (joined per line with `\n`), oldest first. Lines that are not JSON throw.
  - `fun digestOf(session: ClaudeSession, chips: List<Chip>, plan: String?, excerpt: Excerpt): String` — sections in this order: `Title:`, `Goal:` and `Brief tasks:` (`[status] content` per task, omitted without a brief), `Plan task:` (`plan`, e.g. `T12 in progress`, omitted when null), `Signals:` (`chipText` joined by `, `, `none` when empty), `Files written:` (one per line), `Human prompts:` (numbered), `Last agent messages:` (`(none)` when empty). Each prompt and message cut to 2000 characters (`…` appended when cut); while the total exceeds 40000, the oldest prompt is dropped and `(<n> earlier prompts left out)` stands in their place.

- [x] **Step 1: Write tests:** `excerpt keeps human prompts and the last five agent texts, leaving out sidechains, meta lines and tool-only replies` (7 assistant text lines, one sidechain user line, one `isMeta` user line, one assistant line with only `tool_use` → 5 texts, the last 5 by order); `digest names title, brief, signals, files, prompts and agent messages in order` (assert `indexOf` ordering of the section headers and `brief 1/2` present); `long prompts are cut and the oldest dropped to fit forty thousand characters` (30 prompts of 3000 chars → each cut to 2000 + `…`, result length ≤ 40000, contains `earlier prompts left out`, last prompt kept); `a session without agent messages still gets a digest` (contains `Last agent messages:\n(none)`).
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.dashboard.DigestTest` — expected FAIL.
- [x] **Step 3: Implement** `excerptOf` and `digestOf`.
- [x] **Step 4: Run** — expected PASS.
- [x] **Step 5: Commit** "Digest of a session for its analysis", trailers `Plan-Task: 3/6`, `Plan: …`.

### Task 4: Running Haiku and a run of analyses

**Files:**
- Create: `src/main/kotlin/dev/myair/dashboard/Analyzer.kt`
- Test: `src/test/kotlin/dev/myair/dashboard/AnalyzerTest.kt`

**Interfaces:**
- Consumes: `Analysis`, `Verdict` (Task 2); `excerptOf`, `digestOf` (Task 3); `claudeExecutable()` (`editor/SessionTerminals.kt:241`).
- Produces:
  - `val ANALYSIS_SCHEMA: String` — JSON Schema: object, required `verdict` (enum `done`,`open`,`unclear`) and `left` (string), no other properties.
  - `fun analysisPrompt(digest: String): String` — asks what remains to be done in the session, judged by the last messages over the first ones, `left` in one or two sentences in the language of the human prompts, empty when done.
  - `fun analysisArgs(claude: Path, digest: String): List<String>` — `claude -p --model haiku --no-session-persistence --output-format json --json-schema <ANALYSIS_SCHEMA> --tools "" --strict-mcp-config --setting-sources project -- <analysisPrompt(digest)>`.
  - `fun parseAnalysis(stdout: String, lastMessage: Instant, now: Instant): Analysis` — reads the envelope; `is_error: true` throws with its `result`; the structured output field missing or not matching throws with the raw output.
  - `fun analyze(claude: Path, digest: String, lastMessage: Instant, timeout: Duration = Duration.ofMinutes(2)): Analysis` — process in `Homes.myAir.resolve("analysis")` (created), stdin closed, stdout/stderr on futures as `suggestBranchName`; timeout or non-zero exit kills the process with its descendants and throws with stderr.
  - `class AnalysisRun(sessions: List<ClaudeSession>, analyzeOne: (ClaudeSession) -> Analysis, executor: Executor, onResult: (ClaudeSession, Result<Analysis>) -> Unit, onEnd: () -> Unit)` with `val total: Int`, `val done: Int` (atomic), `fun start()`, `fun cancel()`; at most 3 `analyzeOne` at a time; `cancel` stops scheduling, interrupts running ones (their processes killed via `Thread` interrupt → `destroyForcibly` on descendants and the process), reports no result for them, then `onEnd` once.
  - `fun analyzeSession(session: ClaudeSession, chips: List<Chip>, plan: String?): Analysis` — reads `checkNotNull(session.transcript)` lines, builds the digest, calls `analyze(claudeExecutable().toPath(), …, session.lastMessage)`.

- [x] **Step 1: Check the real CLI once** (`cd ~/.my-air && claude -p --model haiku --no-session-persistence --output-format json --json-schema '<schema>' --tools "" --strict-mcp-config --setting-sources project -- "Reply verdict done, left empty."`) — expected exit 0 and the envelope's field holding `{"verdict":"done","left":""}`; record the field name in `parseAnalysis`'s KDoc and whether `~/.claude/CLAUDE.md` loaded (ask "what instructions do you have about language?" in a second call). If sign-in fails with `--setting-sources project`, stop and report: that changes R4.
- [x] **Step 2: Write tests:** `a structured reply parses into an analysis` (envelope literal from Step 1); `an error envelope fails with its text` (`is_error: true`, `result: "Not logged in"` → message contains it); `a reply off the schema fails with the raw output`; `a slow claude is killed after the timeout` (fake `.cmd` in `@TempDir` running `ping -n 30 127.0.0.1`, timeout 1 s → throws within 5 s); `a failing claude reports its stderr` (fake `.cmd` writing to stderr, `exit /b 3`); `a run keeps at most three analyses going and reports each` (10 sessions, `analyzeOne` latching on a counter, max seen = 3, 10 results, `onEnd` once); `a cancelled run reports nothing further and ends once`; `a missing transcript fails that session only` (`analyzeSession` with a deleted path through a run of 2 → one failure naming the path, one success from a stub).
- [x] **Step 3: Run** `./gradlew test --tests dev.myair.dashboard.AnalyzerTest` — expected FAIL.
- [x] **Step 4: Implement** `Analyzer.kt`. `analyze` takes the executable as a parameter so the tests pass the fake `.cmd`.
- [x] **Step 5: Run** — expected PASS.
- [x] **Step 6: Commit** "Run Haiku over a session digest, three at a time", trailers `Plan-Task: 4/6`, `Plan: …`.

### Task 5: The dashboard model carries analyses

**Files:**
- Modify: `src/main/kotlin/dev/myair/dashboard/Dashboard.kt`
- Test: `src/test/kotlin/dev/myair/dashboard/DashboardTest.kt`

**Interfaces:**
- Consumes: `Analysis`, `Verdict` (Task 2).
- Produces:
  - `sealed interface RowAnalysis { data object None; data class Current(val verdict: Verdict, val left: String); data class Failed(val message: String) }`; `Row(session, chips, analysis: RowAnalysis = RowAnalysis.None)`.
  - `Chip.Verdict(val verdict: Verdict)` (text its label) and `Chip.AnalysisFailed(val message: String)` (text `analysis failed`), appended after the other chips; neither counts as an open signal (the partition of `dashboardOf` ignores them, as it ignores `Merged`).
  - `dashboardOf(…, analyses: Map<String, Analysis>, failures: Map<String, String>, now)` — an analysis counts only when its `lastMessage` equals the session's (R3); a failure shows until replaced.
  - `fun analysisCandidates(board: Dashboard, days: Int?, now: Instant): List<ClaudeSession>` — rows of Unfinished and Finished without `Chip.Merged` or `Chip.Pending`, analysis `None` or `Failed`, `lastMessage` within `days` (null: all), newest first.

- [x] **Step 1: Write tests:** `an analysis of an older last message is not shown` (R3); `a current analysis adds its verdict chip and does not keep a session unfinished` (session without signals + `OPEN` analysis stays in Finished with `Chip.Verdict(OPEN)`); `a failed analysis shows until the next result`; `candidates leave out merged, pending, current and too old sessions` (days 14: sessions at 3 d current → out, 3 d none → in, 20 d none → out, merged → out, pending → out; days null → the 20 d one in) (R1, R11).
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.dashboard.DashboardTest` — expected FAIL.
- [x] **Step 3: Implement**; update the existing call sites of `dashboardOf` (editor, tests) with empty maps.
- [x] **Step 4: Run** — expected PASS.
- [x] **Step 5: Commit** "Dashboard rows carry their analysis", trailers `Plan-Task: 5/6`, `Plan: …`.

### Task 6: Analyze in the dashboard, docs, sandbox check

**Files:**
- Modify: `src/main/kotlin/dev/myair/dashboard/DashboardEditor.kt`, `src/main/kotlin/dev/myair/dashboard/README.md`, `CLAUDE.md` (the "Sessions dashboard" paragraph)

**Interfaces:**
- Consumes: everything above.

- [x] **Step 1: Header controls.** A `ComboBox` of `7 days`, `14 days`, `30 days`, `All` bound to `PropertiesComponent.getInstance()` key `dev.myair.dashboard.analysisDays` (`7`/`14`/`30`/`all`, default `14`), and an `ActionLink` `Analyze` that becomes `Cancel` during a run, with a grey `Analyzing <done>/<total>` beside it (R2, R11).
- [x] **Step 2: Store reads off the EDT.** `AnalysisStore(Homes.myAir.resolve("analysis")).readAll()` through a `LatestRead` on the existing single-thread executor at every `update()`; the map lands into `analyses` and rebuilds. Failures live in an in-memory map cleared per session when its next result lands.
- [x] **Step 3: Run.** On Analyze: `analysisCandidates(model(), days, Instant.now())`; empty → a balloon-free grey `Nothing to analyze` for this rebuild only; else an `AnalysisRun` on `AppExecutorUtil.createBoundedApplicationPoolExecutor("Session analysis", 3)` with `analyzeOne = { analyzeSession(it, chipsOf(it), planOf(it)) }` (chips and plan taken from the row the click saw), `onResult` writing the store on the pool thread, then `invokeLater` updating maps and rebuilding; `dispose()` cancels the run (R2).
- [x] **Step 4: Row.** Verdict and failure chips through `chipText`, failure in red with its message as tooltip (as `GitError`); with `RowAnalysis.Current` not `DONE` a second line `Left: <left>` in `UIUtil.getContextHelpForeground()`, indented under the title (R8, R9).
- [x] **Step 5: Build and test:** `./gradlew test buildPlugin` — expected BUILD SUCCESSFUL.
- [x] **Step 6: Docs.** Add to the CLAUDE.md dashboard paragraph one sentence set: Analyze (days chooser, `AnalysisRun`, 3 at a time, `claude -p --model haiku` with the flags of R4 in `~/.my-air/analysis/`), the store and the R3 rule, `ClaudeSession.transcript`; the README's Interface list gains `Analyses.kt`, `Digest.kt`, `Analyzer.kt`.
- [ ] **Step 7: Sandbox check** (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`): open the dashboard, pick 7 days, Analyze; watch the counter, rows get chips and `Left:` lines one by one; Cancel mid-run stops it; reopen the dashboard: results still there, Analyze offers only the rest; send a message in an analysed session: its analysis disappears. Record each in the evidence table.
- [x] **Step 8: Commit** "Analyze sessions from the dashboard", trailers `Plan-Task: 6/6`, `Plan: …`.

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 5, 6 | test `DashboardTest` › `candidates leave out merged, pending, current and too old sessions`; not done: Analyze clicked in runIde |
| R2 | 4, 6 | test `AnalyzerTest` › `a cancelled run reports nothing further and ends once`; not done: Cancel and closing the tab in runIde |
| R3 | 5 | test `DashboardTest` › `an analysis of an older last message is not shown` |
| R4 | 4 | test `AnalyzerTest` › `the arguments run haiku without tools, MCP servers or user settings, the prompt going through stdin`; test `AnalyzerTest` › `a structured reply parses into an analysis`; checked: real `claude` answered two real sessions (OPEN with a Polish Left line, DONE), signed in with `--setting-sources project`; commit `742f198` |
| R5 | 4 | test `AnalyzerTest` › `a run keeps at most three analyses going and reports each`; test `AnalyzerTest` › `a slow claude is killed after the timeout` |
| R6 | 1, 3 | test `ClaudeSessionScannerTest` › `a transcript copied into another project directory is one session, the copy with the latest message, else the longest`; test `DigestTest` › `long prompts are cut and the oldest dropped to fit forty thousand characters`; test `DigestTest` › `excerpt keeps human prompts and the last five agent texts, leaving out sidechains, meta lines and tool-only replies` |
| R7 | 2, 6 | test `AnalysesTest` › `a written analysis reads back by session id`; not done: second IDE seeing a result |
| R8 | 5, 6 | test `DashboardTest` › `a current analysis adds its verdict chip and does not keep a session unfinished`; not done: Left line seen in runIde |
| R9 | 4, 5, 6 | test `DashboardTest` › `a failed analysis shows until the next result`; test `AnalyzerTest` › `an error envelope fails with its text`; test `AnalyzerTest` › `a missing transcript fails that session only` |
| R10 | 6 | commit `f8a465c` (runs on `AnalysisRun`'s pool, store read through `LatestRead`) |
| R11 | 5, 6 | test `DashboardTest` › `candidates leave out merged, pending, current and too old sessions`; not done: chooser kept across a restart |
