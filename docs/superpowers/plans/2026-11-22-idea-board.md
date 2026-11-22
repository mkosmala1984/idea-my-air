# Idea Board Implementation Plan

## TL;DR

- **Goal:** the idea board of the spec: a third folder kind whose ideas Claude seeds, expands, crosses and judges with one `claude -p --json-schema` call per step, a medal computed in Kotlin, an autopilot that runs only while an IDE runs, and a gold idea turned into a Claude Code project folder with a session on a click.
- **Approach:** 14 tasks: a spike on long MCP calls and on the R14 command line first (it can change Task 10), then pure code with tests (board file and lock, score/evolve/rate, prompts and envelopes, changes, placement, project writer), then the step runner and `IdeaService`, autopilot, MCP tools, the tab and the panel, `calibrateJudge`, and a sandbox walk.
- **Needs your input (format additions the spec's JSON lacks, Task 2 updates R4/R6 wording in the same commit):** `judging` entries and `autopilot.lease` carry `ideStart` beside the pid (`ideAlive` needs both, pids are reused); an idea gets `projectError` (R30 stores the failure "on the idea"); `autopilot` gets `lastError` (the `error` state of R21 shows it); `manager` is a top-level key (it is in the spec's example, not in R4's list).
- **Needs your input too:** the original's Swedish reference ideas are translated to English in `calibrateJudge` (code strings are English), and the fixed prompt flow is the original's default "Inventor" template.
- **If the spike finds a 40 s MCP call times out:** `my_air_ideas_apply` validates and returns at once with the queued changes; each LLM change's outcome lands as ideas or as an entry of a new top-level `log` key (last 50: `at`, `op`, `ok`, `text`), shown in the header and by `my_air_ideas_get`. That changes R4 and R33; Task 10 stops and asks before doing it.
- **Risks:** R14 has no temperature flag, so the judge runs at `claude`'s default where the original used 0.2 (calibration in Task 13 is the check); `SessionsPanel.kt` has an unrelated uncommitted change and Task 11 must edit it (ask first); sandbox walks spend real tokens.
- **Out of scope:** as in the spec: canvas, scouts, guests and announcements, spin-off boards, trends and the report, exhibition mode, other providers, per-board prompt flows, Swedish UI, `vault/`, running with no IDE.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

| Task | Status | Commit |
|---|---|---|
| 1 | | |
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
| 12 | | |
| 13 | | |
| 14 | | |

**Goal:** a board folder kind with its own tab, Claude-driven idea evolution judged by a calibrated jury, an autopilot, MCP access, and gold ideas handed off as Claude Code projects.

**Architecture:** an idea board is an ordinary folder of `structure.json`; its data is `~/.my-air/ideas/<folderId>/board.json`, changed only by a function applied under a cross-process lock (`BoardFiles.update`). Every LLM step is a blocking `claude -p` call through a function type (`ClaudeRun`), so the step runner (`IdeaSteps`) is tested with canned envelopes; `IdeaService` (app service) runs steps on a pool of 3 and is the only owner of the UI state; `IdeaAutopilot` ticks every 30 s like `ScheduledRuns`. The tab (`ideasview/`) mirrors `reviewview/` on `FolderTabs` and `ManagerTabEditor`.

**Tech Stack:** Kotlin, IntelliJ Platform 263 (local IU), Gson, JUnit 5, `claude` and `git` CLIs.

**Spec:** `docs/superpowers/specs/2026-11-22-idea-board-design.md`

**Ported source:** `E:\dev\projects\personal\idea-generator-2` (MIT, "Copyright (c) 2026 Anders Bjarby"): prompts and schemas in `server/llm.ts`, brief and CLAUDE.md layout in `server/project.ts`, `public/js/score.js`, `public/js/evolve.js`, `rateCheck` in `server/autopilot.ts`, reference ideas in `tools/calibrate-judge.ts`, the default flow in `server/db.ts` (`DEFAULT_TEMPLATES[0]`). Copy prompt and schema text verbatim unless a step says otherwise; do not paste it into this plan.

## Global Constraints

- Code, comments, strings and test names in English; tests are backtick sentences, plain JUnit 5, no platform services.
- No fallbacks: unknown keys, missing fields, unknown enum values, off-schema answers, unknown ops and fields throw naming the file or the change. Nothing is clamped (scores, titles, counts); the original's `clampScore`, `slice` and `-2` suffix are not ported.
- `structure.json` keeps its version (R2); `board.json` `version` is 1.
- Every write under `~/.my-air/ideas` goes through `BoardFiles.update`/`create` (lock + `writeAtomically`), never on the EDT; git, `claude` and file reads never run on the EDT.
- `claude -p` exactly as R14, through `runClaudeRaw`, with the board's profile env (`profileEnv`), timeout 3 minutes per call; at most 3 calls at once per IDE (R13).
- Defaults: models `sonnet` per role; `paceSeconds` 60, `stepsPerHour` 12, `usdPerDay` 1.0, `mutationRate` 0.2, `autoProjectOnGold` true; settings `ideasUsdPerDay` 3.0 and `ideaProjectsRoot` "" (empty: Create Project disabled).
- Medal thresholds 9/11/13, override fitness 6/10/12/14, tournament k = 3, evolution stats window 8, judging takeover 5 minutes, backoff 30 s doubling to 15 min, tick 30 s.
- R26 asks for "the 3 strongest" but allows two ideas: the schema says 1–3, as the original.
- R36's pass rule is the spec's (no weak medal, strong mean ≥ 9), not the original script's gap rule.
- When a task adds or renames a public class or function, it updates its package `README.md`; when it changes behaviour described in `docs/internals/ideas.md`, it updates that file in the same commit.
- Classes named in `plugin.xml` and `my-air-mcp.xml` are found at runtime only.
- `./gradlew test buildPlugin` green before every commit of a code task.
- Commit only this plan's files: `SessionsPanel.kt` carries an unrelated uncommitted change; before Task 11 edits it, ask the user whether to commit or stash that change first. `git add` by explicit path only.
- Every commit's trailer block ends with `Plan-Task: <n>/14` and `Plan: docs/superpowers/plans/2026-11-22-idea-board.md`.

## Review Focus

1. The panel, an MCP call and another IDE change the same board at once → no change is lost (R5); test in Task 2 (two threads, real files).
2. A parent deleted (or a pair made used) while its expand or combine call runs → the result is dropped with the reason, its cost still counted; test in Task 8.
3. The model answers off the schema (2 ideas instead of 3, score 0, 6 or `"4"`, missing field, no `structured_output`, `is_error`, non-zero exit) → nothing is added or `judgeError` is stored, cost counted when reported; tests in Tasks 4 and 8.
4. An IDE killed mid-judgement or holding the autopilot lease → after its pid is gone (or 5 minutes for judging) another IDE takes over, and a live holder is never displaced; tests in Tasks 5 and 9.
5. The day turns over, or a USD limit is 0 → spend is keyed by the local date, a limit of 0 lets only free calls through, a budget halt lasts until the next local midnight; tests in Task 3.

---

### Task 1: Spike: long MCP calls and the `claude -p` command line

**Files:**
- Modify: this plan (record the result under this task). Spike code is not committed.

- [ ] **Step 1: Long MCP call.** Add a scratch `SpikeMcpToolset` (in `dev.myair.mcp`, listed in `my-air-mcp.xml`) with `suspend fun my_air_spike_wait(seconds: Int): String` that `delay`s and returns `"waited <n> s"`. `./gradlew buildPlugin`, then start the sandbox as CLAUDE.md says (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`); in a sandbox session tab ask Claude to call it with 10, 40, 90 and 180.
  Expected record per call: returned or not, elapsed time, the exact error text and which side gave it (claude's MCP client or the IDE's server; check `idea.log` of the sandbox).

- [ ] **Step 2: R14 command line** in a directory `claude` has never seen (`mkdir` a new one under `~/.my-air/ideas/spike-<random>`), once with the default profile and once with a second profile if `profiles.json` lists one:

```bash
echo "Idea: a bus that delivers parcels" | claude -p --model sonnet --no-session-persistence --output-format json --json-schema '{"type":"object","properties":{"reason":{"type":"string"},"third":{"type":"string"}},"required":["reason","third"],"additionalProperties":false}' --strict-mcp-config --setting-sources project --tools "" --system-prompt 'Line one.
Line "two" with quotes.
Line three: put the exact text of this line into "third".'
```

  Expected record: exit code, whether a trust prompt or "Workspace not trusted" refuses the new directory, whether `structured_output.third` is the third line intact (multi-line system prompt with quotes survives the Windows command line when run through `ProcessBuilder`, so repeat it from a scratch Kotlin test using `processBuilder`, escaping `"` as `analysisArgs` does), the envelope's `total_cost_usd`.

- [ ] **Step 3: Failing call.** Same command with `--model no-such-model`, and once with a schema the answer cannot meet; record the exit code, stdout and stderr verbatim (R15 needs the cost of a failed call; `runClaude` throws on a non-zero exit).

- [ ] **Step 4: URL distill tools.** `--tools WebFetch --allowedTools WebFetch` with a prompt asking for a page's title of `https://example.com`; record whether it fetched without a permission prompt in `-p` mode.

- [ ] **Step 5: Decide and record.**
  - 10, 40 and 90 s calls all return → Task 10 is synchronous, as the spec says. Any of them fails → Task 10 uses the asynchronous fallback of the TL;DR, after asking the user.
  - The untrusted directory is refused → stop, set row 1 `blocked: claude -p refuses a new directory`, report to the user.
  - The multi-line system prompt arrives broken → Task 4 passes `--system-prompt-file <file in the board directory>` instead of `--system-prompt`; record it as a deviation from R14 for the user.
  - A failed call exits non-zero with an envelope on stdout → `runClaudeRaw` (Task 4) returns it and Task 8 counts its cost; no envelope → a failed call counts no cost.
  - WebFetch needs a prompt in `-p` → stop, report: R7's URL seed cannot work as specified.
  Remove the spike code; commit only this plan with `Plan-Task: 1/14`.

**Result:** (to fill)

---

### Task 2: Board model, `board.json` and the lock

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/Board.kt`, `src/main/kotlin/dev/myair/ideas/BoardJson.kt`, `src/main/kotlin/dev/myair/ideas/BoardFiles.kt`, `src/main/kotlin/dev/myair/ideas/README.md`, `docs/internals/ideas.md`
- Modify: `CLAUDE.md` (table row Idea boards → `docs/internals/ideas.md`), `docs/superpowers/specs/2026-11-22-idea-board-design.md` (R4 key list adds `manager`; R6 adds `projectError`; the JSON example adds `ideStart`, `lastError`)
- Test: `src/test/kotlin/dev/myair/ideas/BoardJsonTest.kt`, `src/test/kotlin/dev/myair/ideas/BoardFilesTest.kt`

**Interfaces:**
- Produces (in `dev.myair.ideas`):
  - `enum class IdeaKind { seed, expand, combine }`, `enum class Mode { manual, automatic }`, `enum class Role { distill, expand, combine, judge, summary, brief }`
  - `data class Scores(originality: Int, feasibility: Int, impact: Int)` (init: each in 1..5)
  - `data class Idea(id: String, title: String, pitch: String, kind: IdeaKind, parents: List<String>, generation: Int, sourceUrl: String?, scores: Scores?, reason: String?, keywords: List<String>, medalOverride: Int?, judgeError: String?, project: String?, projectError: String?, created: Instant)` (init: `id` matches `I[1-9][0-9]*`, title 1..120 chars, pitch ≤ 600, `medalOverride` in 0..3, parents distinct, a seed has none, an expand at most one, a combine at most two: Delete (R25) may leave fewer)
  - `data class BoardConfig(mode: Mode, judgeGuidance: String, models: Map<Role, String>, profile: String, autoProjectOnGold: Boolean, paceSeconds: Int, stepsPerHour: Int, usdPerDay: Double, mutationRate: Double)` with `companion fun defaults()`; init: every `Role` has a model, `paceSeconds` ≥ 0, `stepsPerHour` ≥ 1, `usdPerDay` ≥ 0, `mutationRate` in 0.0..1.0
  - `data class Theme(name, insight, ideas: List<String>)`, `data class Strong(title, why)`, `data class Summary(headline, themes, strongest, gaps, nextSteps, ideaCount: Int, at: Instant)`
  - `data class AutopilotLog(errors: Int, lastErrorAt: Instant?, lastError: String?, lastStepAt: Instant?, steps: List<Instant>)`
  - `data class IdeIdentity(pid: Long, start: Instant)` with `companion fun current()`; `data class Judging(ide: IdeIdentity, since: Instant)`
  - `data class Board(goal: String, config: BoardConfig, ideas: List<Idea>, nextId: Int, spend: Map<LocalDate, Double>, summary: Summary?, autopilot: AutopilotLog, judging: Map<String, Judging>, manager: PlanManager?)`, with `fun idea(id): Idea` (throws `no idea '<id>'`) and `companion fun new(goal: String, profile: String)`; init: ids unique, `nextId` above every id's number, every parent and judging key an existing id
  - `fun Board.toJson(): String`, `fun parseBoard(text: String, source: Path): Board` (reuse `dev.myair.store.Keys`)
  - `class BoardFiles(val root: Path)`: `dir(folderId)`, `file(folderId)`, `exists(folderId)`, `read(folderId): Board`, `readAll(): Map<String, Result<Board>>`, `create(folderId, board)` (throws when it exists), `update(folderId, op: (Board) -> Board): Board`; `companion val INSTANCE = BoardFiles(Homes.myAir.resolve("ideas"))`

- [ ] **Step 1: Write the failing tests**

`BoardJsonTest`:
- `the spec's example round-trips` — parse the spec's JSON (with `ideStart` in `judging`, `lastError: null`, `projectError: null`), `toJson`, parse again: equal.
- `an unknown key throws naming it` — top-level `"extra": 1` → message contains `extra` and the path.
- `a missing field throws` — idea without `generation`; board without `nextId`.
- `an unknown kind throws` — `"kind": "guest"`.
- `a score outside 1 to 5 throws` — `"impact": 6` and `"impact": 0`; `"impact": "4"` throws too.
- `a title over 120 characters throws`; `a pitch over 600 characters throws`.
- `version 2 throws`.
- `a reused id throws` — `nextId` 2 with an idea `I2`.
- `a parent that is not on the board throws`.
- `new boards have the spec's defaults` — `Board.new("g", "default").config` equals models all `sonnet`, mode manual, 60/12/1.0/0.2, `autoProjectOnGold` true; `nextId` 1.

`BoardFilesTest` (temp dir):
- `create refuses a board that exists`.
- `two threads updating one board lose no change` — 2 threads × 50 `update`s each appending an idea with the board's `nextId`; afterwards 100 ideas with ids `I1..I100`.
- `a board that cannot be read is a failure in readAll` — one good, one broken file → one success, one failure naming the file.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.Board*'` — FAIL (classes missing).

- [ ] **Step 3: Implement.** `update` takes, in this order, a JVM lock per folder id (`ConcurrentHashMap<String, ReentrantLock>`: `FileChannel.lock` from two threads of one JVM throws `OverlappingFileLockException`) and an exclusive `FileChannel.lock()` on `<dir>/board.lock` (open with `CREATE, WRITE`), then reads, applies `op`, writes with `writeAtomically`, releases. `create` writes under the same locks and refuses an existing file. The JSON writer orders keys as the spec's example and writes nulls. Write `README.md` (package index, MIT notice placeholder for Task 4) and `docs/internals/ideas.md` (data and lock sections).

- [ ] **Step 4: Run** the same tests — PASS; then `./gradlew test buildPlugin`.

- [ ] **Step 5: Commit** (`git add` the files above) `Ideas: board model, board.json and its lock`.

---

### Task 3: Score, evolution and rate limits (pure)

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/Score.kt`, `Evolve.kt`, `Rate.kt` (same package)
- Test: `src/test/kotlin/dev/myair/ideas/ScoreTest.kt`, `EvolveTest.kt`, `RateTest.kt`

**Interfaces:**
- Consumes: `Idea`, `Board`, `AutopilotLog`, `Mode` (Task 2).
- Produces:
  - `fun sum(idea: Idea): Int?`, `fun medal(idea: Idea): Int?` (0 none … 3 gold), `fun fitness(idea: Idea): Int?`
  - `sealed interface Move { data class Expand(val id: String); data class Combine(val a: String, val b: String) }`
  - `fun pairKey(a: String, b: String): String`, `fun usedPairs(ideas: List<Idea>): Set<String>` (every parent–child pair and every combined pair), `fun expanded(ideas: List<Idea>): Set<String>`
  - `fun pairRefusal(board: Board, a: String, b: String): String?` (same id, unknown id, already crossed, parent and child)
  - `fun nextMove(ideas: List<Idea>, busy: Set<String>, random: Random, mutationRate: Double, k: Int = 3): Move?`
  - `data class EvolutionStats(count: Int, first: Double?, latest: Double?, best: Int?)`, `fun evolutionStats(ideas: List<Idea>, window: Int = 8): EvolutionStats` (bred = not seed, with fitness, ordered by id number; `latest` only when more than `window`)
  - `enum class Halt { global_budget, board_budget, backoff, hourly, pace }`, `fun backoff(errors: Int): Duration`
  - `data class RateInput(now: Instant, zone: ZoneId, steps: List<Instant>, lastStepAt: Instant?, paceSeconds: Int, stepsPerHour: Int, boardSpent: Double, boardUsdPerDay: Double, globalSpent: Double, globalUsdPerDay: Double, errors: Int, lastErrorAt: Instant?)`
  - `fun rateCheck(i: RateInput): Pair<Halt, Instant>?` (null: allowed; the Instant is `until`)
  - `fun spentOn(board: Board, day: LocalDate): Double`, `fun stepsLastHour(log: AutopilotLog, now: Instant): Int`

- [ ] **Step 1: Write the failing tests**

`ScoreTest`: `medals follow the sum` (sums 8, 9, 10, 11, 12, 13, 15 → 0, 1, 1, 2, 2, 3, 3); `an unjudged idea has no sum, medal or fitness`; `a hand-set medal wins over the sum`; `a hand-set medal counts as 6, 10, 12 or 14`.

`EvolveTest` (helper builds judged ideas with given sums and parents):
- `with fewer than 3 judged ideas it expands the strongest not yet expanded` (ties: lowest id).
- `with mutation rate 1 it expands`.
- `it never repeats a crossed pair or pairs a parent with its child` — 1000 seeds of `Random(seed)`, every Combine's key ∉ `usedPairs`.
- `busy ideas are never picked`.
- `it returns null when every pair is used and every idea expanded`.
- `stronger ideas breed more often` — 5 ideas sums 5, 7, 9, 11, 13, mutation 0, 2000 seeds: count of the 13 in Combines > count of the 5.
- `pairRefusal names an already crossed pair and a parent with its child`.
- `stats skip seeds and compare the first and latest 8 bred ideas` — 10 bred ideas → `first` mean of I-order first 8, `latest` mean of last 8, `best` the max; 8 bred → `latest` null.

`RateTest` (zone `Europe/Warsaw`):
- `halts are checked in order` — all limits hit → `global_budget`; then lift each in turn → `board_budget`, `backoff`, `hourly`, `pace`, allowed.
- `a budget halt lasts until the next local midnight` — now 2026-11-22T22:30Z → until 2026-11-22T23:00Z (local midnight).
- `a limit of 0 lets only free calls through` — spent 0 → allowed; spent 0.001 → halted.
- `backoff doubles from 30 s up to 15 min` — errors 1, 2, 3, 6, 10 → 30 s, 60 s, 120 s, 15 min, 15 min.
- `the hourly halt ends when the oldest step leaves the hour`.
- `spend is read by local date`.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.ScoreTest' --tests 'dev.myair.ideas.EvolveTest' --tests 'dev.myair.ideas.RateTest'` — FAIL.

- [ ] **Step 3: Implement** as `score.js`, `evolve.js` (40 tournament attempts before the final expand, as the original) and `rateCheck` of `autopilot.ts` (`overBudget` = spent > 0 and spent ≥ limit). `nextMove` takes parents from `Idea.parents` instead of edges. Update the package README.

- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.

- [ ] **Step 5: Commit** `Ideas: medal, evolution and rate limits`.

---

### Task 4: Prompts, `claude -p` arguments and envelopes

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/IdeaPrompts.kt` (MIT header comment), `src/main/kotlin/dev/myair/ideas/IdeaCalls.kt`
- Modify: `src/main/kotlin/dev/myair/actions/ClaudePrint.kt` (extract `runClaudeRaw`), `src/main/kotlin/dev/myair/actions/README.md`, `src/main/kotlin/dev/myair/ideas/README.md` (the original's copyright line and full MIT text, R37)
- Test: `src/test/kotlin/dev/myair/ideas/IdeaCallsTest.kt`, `src/test/kotlin/dev/myair/actions/ClaudePrintTest.kt` (one case)

**Interfaces:**
- Consumes: `Role`, `Idea`, `Board`, `Scores` (Task 2).
- Produces:
  - in `actions`: `data class ClaudeOutput(val exit: Int, val stdout: String, val stderr: String)`, `fun runClaudeRaw(args, dir, input, timeout, env): ClaudeOutput` (throws only on timeout or interrupt); `runClaude` becomes `runClaudeRaw` plus the exit-0 check.
  - `IdeaPrompts.kt`: `INVENTOR_SYSTEM`, `EXPAND_INSTRUCTION`, `COMBINE_INSTRUCTION` (`DEFAULT_TEMPLATES[0]`), `JUDGE_SYSTEM`, `SUMMARY_SYSTEM`, `BRIEF_SYSTEM`; schemas `PITCH_SCHEMA`, `URL_SEED_SCHEMA` (pitch props plus `fetched: boolean`, `problem: string`), `EXPAND_SCHEMA`, `JUDGE_SCHEMA`, `SUMMARY_SCHEMA`, `BRIEF_SCHEMA`; `fun judgeSystem(guidance: String): String`; user texts `distillPrompt(goal, text, url: String?)`, `expandPrompt(goal, parent: Idea, titles: List<String>)`, `combinePrompt(goal, a: Idea, b: Idea, titles)`, `judgePrompt(goal, idea: Idea, vocabulary: List<String>)`, `summaryPrompt(goal, ideas: List<Idea>)`, `briefPrompt(goal, idea: Idea, lineage: List<Idea>)`. The language rule keeps only "Write in the same language as the source idea(s); if that is unclear or mixed, write in English."
  - `IdeaCalls.kt`: `typealias ClaudeRun = (args: List<String>, dir: Path, input: String, env: Map<String, String?>) -> ClaudeOutput`; `data class Call(val role: Role, val system: String, val schema: String, val input: String, val webFetch: Boolean = false)`; `fun ideaArgs(claude: Path, model: String, call: Call): List<String>`; `data class Envelope(val cost: Double?, val output: JsonObject?, val error: String?, val raw: String)`; `fun parseEnvelope(out: ClaudeOutput): Envelope`; `data class Pitch(val title: String, val pitch: String)`; `data class Judgement(val scores: Scores, val reason: String, val keywords: List<String>)`; `data class Brief(name, slug, oneLiner, problem, audience, mvp, outOfScope, stack, firstSteps, openQuestions, risks)`; parsers `parsePitch(e): Pitch`, `parseUrlSeed(e): Pitch` (throws with `problem` when `fetched` is false), `parseExpand(e): List<Pitch>`, `parseJudgement(e): Judgement`, `parseSummary(e, ideaCount, now): Summary`, `parseBrief(e): Brief`; `fun normalizeKeywords(raw: List<String>): List<String>` (lowercase, the original's character filter, dedupe; no truncation, no count check).

- [ ] **Step 1: Write the failing tests** (`IdeaCallsTest`, canned envelope strings)

- `the arguments are R14's` — `ideaArgs` for judge: contains in order `-p --model sonnet --no-session-persistence --output-format json --json-schema <schema with \" escaped> --strict-mcp-config --setting-sources project --system-prompt <system escaped> --tools ""`; for a URL distill `--tools WebFetch --allowedTools WebFetch` instead.
- `expand needs exactly three ideas` — 2 and 4 ideas throw `expected 3 ideas, got 2`; 3 parse.
- `a judgement outside 1 to 5 is refused` — `impact` 6, 0, `"4"`, missing `keywords` → throws with the envelope text; never a clamped value.
- `no structured output is refused` and `an error envelope is refused with its result text` (`is_error: true`).
- `the cost is read from a failed envelope` — exit 1 with an `is_error` envelope that has `total_cost_usd` 0.012 → `Envelope.cost` 0.012, `error` set.
- `a title over 120 characters is refused`.
- `an unread page fails the URL seed with its problem` — `fetched: false, problem: "404"` → message contains `404`.
- `keywords are normalized` — `["Public Spaces", "public spaces", "AI!"]` → `["public spaces", "ai"]`.
- `the judge system adds the board's guidance` — non-blank guidance appended after `JUDGE_SYSTEM` as in `llm.ts`; blank → `JUDGE_SYSTEM`.
- `prompts name the goal and the titles already on the board` — `expandPrompt` contains the goal line and `- <title>` for each of the last 40 titles.

`ClaudePrintTest`: `runClaudeRaw returns a non-zero exit with its output` (a command that prints and exits 1).

Per Task 1: if the multi-line `--system-prompt` broke, the arguments test expects `--system-prompt-file <path>` and `ideaArgs` takes the file path.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.IdeaCallsTest' --tests 'dev.myair.actions.ClaudePrintTest'` — FAIL.

- [ ] **Step 3: Implement.** Schemas are JSON text constants copied from `llm.ts`/`project.ts`; escape `"` in `--json-schema` and `--system-prompt` values as `analysisArgs` does. Add the copyright line and MIT text to `ideas/README.md` and a one-line header naming the original in `IdeaPrompts.kt`.

- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.

- [ ] **Step 5: Commit** `Ideas: ported prompts, claude -p arguments and envelope parsing`.

---

### Task 5: Board changes and the change list

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/IdeaChanges.kt`
- Test: `src/test/kotlin/dev/myair/ideas/IdeaChangesTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4 (`Board`, `pairRefusal`, `Pitch`, `Judgement`, `Summary`).
- Produces (pure, each returns the new board):
  - `fun Board.addIdeas(pitches: List<Pitch>, kind: IdeaKind, parents: List<String>, sourceUrl: String?, now: Instant): Pair<Board, List<String>>` — throws `parent <id> is gone` when a parent is missing; combine also re-checks `pairRefusal`; generation = max parent generation + 1, seeds 0.
  - `fun Board.judged(id, judgement: Judgement)`, `fun Board.judgeFailed(id, text: String)` (scores and reason cleared, `judgeError` set), `fun Board.addSpend(day: LocalDate, usd: Double)`
  - `fun Board.claimJudging(id, ide: IdeIdentity, now: Instant, alive: (IdeIdentity) -> Boolean): Board?` (null: held by a live IDE for under 5 minutes), `fun Board.releaseJudging(id, ide)`
  - `fun Board.setMedal(id, medal: Int?)`, `fun Board.delete(id)` (children keep, the id leaves their `parents`; their `kind` stays), `fun Board.setGoal(text)`, `fun Board.setGuidance(text)`, `fun Board.setConfig(fields: JsonObject)` (keys `mode`, `models` (object of roles), `profile`, `autoProjectOnGold`, `paceSeconds`, `stepsPerHour`, `usdPerDay`, `mutationRate`; unknown key throws naming it), `fun Board.setSummary(s: Summary)`, `fun Board.setProject(id, path: String)`, `fun Board.setProjectError(id, text: String)`
  - `sealed interface Change` with `Seed(text)`, `Expand(id)`, `Combine(a, b)`, `Judge(id)`, `SetMedal(id, medal: Int?)`, `Delete(id)`, `SetGoal(text)`, `SetGuidance(text)`, `SetConfig(fields: JsonObject)`, `Summarize`, `Project(id)`; `fun parseChanges(json: String): List<Change>` (op names exactly R33's; `set_medal` takes `medal` 0–3 or null for automatic)
- [ ] **Step 1: Write the failing tests**
- `new ideas get the next ids and their parents' generation plus one`.
- `a result whose parent is gone is refused` and `a combine of a pair crossed meanwhile is refused`.
- `delete keeps the children and removes the id from their parents`.
- `a live IDE's judging is not taken over` / `a dead IDE's judging is taken over` / `judging older than 5 minutes is taken over`.
- `spend adds per local date`.
- `set_config refuses an unknown key and a value out of range` (`stepsPerHour` 0, `mutationRate` 1.5, unknown role in `models`).
- `the change list names an unknown op and its index` — `[{"op":"seed","text":"a"},{"op":"grow"}]` → `change 1: unknown op 'grow'`; an extra field → `change 0 (seed): unknown field 'x'`.
- `set_medal null means automatic`.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.IdeaChangesTest'` — FAIL.
- [ ] **Step 3: Implement** in the style of `review/ReviewChanges.kt`.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.
- [ ] **Step 5: Commit** `Ideas: board changes and the change list`.

---

### Task 6: Placement rule for idea boards

**Files:**
- Modify: `src/main/kotlin/dev/myair/review/ReviewService.kt:69-99` (`inReview`, `misplaced`, `placementProblem`, `refuseReviewPlacement` → `refusePlacement`), `src/main/kotlin/dev/myair/service/ClaudeSessionsService.kt:239-243`, `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt:304`, `src/main/kotlin/dev/myair/toolwindow/FolderEditing.kt:74-76,115`, `src/main/kotlin/dev/myair/toolwindow/TurnIntoPlan.kt:36`, `docs/internals/reviews.md`, `docs/internals/ideas.md`, `src/main/kotlin/dev/myair/review/README.md`
- Test: `src/test/kotlin/dev/myair/review/` (the existing placement test class; add cases)

**Interfaces:**
- Consumes: nothing new. The board ids come from `IdeaService.onDiskIds` (a cached set, like `ReviewService.onDiskIds`), which Task 8 adds; listing `~/.my-air/ideas` here would read files on the EDT. So this task changes the pure functions and their tests, the callers pass `emptySet()` for `boards`, and Task 8 replaces every one of those `emptySet()` arguments (the five call sites above) with `IdeaService.getInstance().onDiskIds`.
- Produces: `fun placementProblem(before, after, reviews: Set<String>, boards: Set<String>): String?`, `fun refusePlacement(before, after, reviews, boards)`, `fun inHolder(structure, reviews, boards, id): Boolean` (replaces `inReview`: is or lies in a review or idea board folder).

- [ ] **Step 1: Write the failing tests**
- `an idea board holds no plan` — creating a plan inside a board → `'<path>' would be a plan in idea board folder '<board>'`.
- `an idea board holds no review` and `lies in no plan, review or other idea board`.
- `a plain subfolder of an idea board is allowed` (the project subfolder of R29).
- `a misplacement made before the rule does not block other writes`.

- [ ] **Step 2: Run** the placement test class — FAIL.
- [ ] **Step 3: Implement**; every caller passes `boards` (`emptySet()` until Task 8); New Plan, Add Plan, the drop and Turn into Plan use `inHolder`, so with Task 8's ids they are disabled inside a board as they are inside a review.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.
- [ ] **Step 5: Commit** `Folders: idea boards hold no plan or review and lie in none`.

---

### Task 7: Project writer

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/ProjectWriter.kt`
- Test: `src/test/kotlin/dev/myair/ideas/ProjectWriterTest.kt`

**Interfaces:**
- Consumes: `Brief` (Task 4), `Idea`, `sum`, `medal` (Tasks 2, 3).
- Produces: `fun slugify(s: String): String` (as `project.ts`, empty result throws instead of `idea`); `fun lineage(board: Board, id: String): List<Idea>` (every ancestor, by id number); `fun renderClaudeMd(brief: Brief, idea: Idea, goal: String, lineage: List<Idea>, date: LocalDate): String` (sections of R28 in `project.ts`'s English order; working rules are the first three English rules, without the `vault/` rule); `fun writeProject(root: Path, brief: Brief, idea: Idea, goal: String, lineage: List<Idea>, now: Instant, gitInit: (Path) -> Unit): Path`; `const val PROJECT_FIRST_MESSAGE = "Read CLAUDE.md and propose a plan for the first step."`

- [ ] **Step 1: Write the failing tests** (temp dir; `gitInit` a recording lambda except in one test that runs `Git.run(dir, "init", "-q", "-b", "main")`)
- `the project folder gets CLAUDE.md, README.md and idea.json and is git-initialized` — files exist; CLAUDE.md has `## Jury verdict` with `= 13/15`, `## MVP`, `## Where the idea came from` listing the lineage, `## Working agreements`; README has `cd "<dir>"` and `claude` and the first message; `idea.json` parses with `goal`, `idea`, `lineage`, `brief`.
- `a taken name fails naming the folder` — existing `<root>/<slug>` → message contains that path; nothing written into it.
- `a missing root fails` (non-recursive create).
- `slugs are ASCII kebab case` — `"Diff narrator för PRs!"` → `diff-narrator-for-prs`.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.ProjectWriterTest'` — FAIL.
- [ ] **Step 3: Implement** with `Files.createDirectory(root.resolve(slug))`.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.
- [ ] **Step 5: Commit** `Ideas: project folder from a brief`.

---

### Task 8: Step runner and `IdeaService`

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/IdeaSteps.kt`, `src/main/kotlin/dev/myair/ideas/IdeaService.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (startup if needed), the five Task 6 call sites in `ClaudeSessionsService.kt`, `SessionTree.kt`, `FolderEditing.kt` (two) and `TurnIntoPlan.kt` (`emptySet()` → `IdeaService.getInstance().onDiskIds`), `docs/internals/ideas.md`, `ideas/README.md`
- Test: `src/test/kotlin/dev/myair/ideas/IdeaStepsTest.kt`

**Interfaces:**
- Consumes: Tasks 2–7.
- Produces:
  - `class IdeaSteps(files: BoardFiles, claude: Path, run: ClaudeRun, env: (profile: String) -> Map<String, String?>, ide: IdeIdentity, alive: (IdeIdentity) -> Boolean, clock: Clock, projectsRoot: () -> Path?, onProject: (boardId: String, ideaId: String, dir: Path, name: String) -> Unit)`. Blocking methods, never on the EDT: `seed(boardId, text): List<String>`, `expand(boardId, parent): List<String>`, `combine(boardId, a, b): String`, `judge(boardId, ideaId): Boolean` (false: another live IDE judges it), `summarize(boardId)`, `createProject(boardId, ideaId): Path`. Each reads the board, builds its `Call`, runs it in `files.dir(boardId)` with `env(board.config.profile)` and `board.config.models[role]`, parses, and in one `files.update` applies the result and `addSpend(today, cost)`; a refused parse or apply still writes the cost and then throws.
  - Seeding (R7): trimmed text ≤ 160 chars and not a single `http(s)` URL → title directly, no call; a single URL → `URL_SEED_SCHEMA` with `webFetch = true`, `sourceUrl` set; longer text → `PITCH_SCHEMA` distill.
  - Judging (R10, R11, R13, R30): `claimJudging` in one update; the call; then `judged` or `judgeFailed(envelope raw text)`, `releaseJudging`, spend, in one update. A gold result with `project == null` and `autoProjectOnGold` → `createProject`; its failure (no projects folder set among them) → `setProjectError`, the judgement kept.
  - `createProject` (R27, R28): brief call, `writeProject` (git through `Git.run`), `setProject` in the same update as the spend, then `onProject`.
  - `@Service(APP) class IdeaService`: `boards: Map<String, Result<Board>>` (only folders in the structure, EDT), `onDiskIds: Set<String>`, `subscribe(parent, listener)`, `create(folderId, board)` and `update(folderId, op)` (both off the EDT: they take the lock; callers on the EDT use `submitWrite(folderId, op, onDone)` which runs on the pool and lands `onDone` on the EDT), `submit(step: (IdeaSteps) -> T): CompletableFuture<T>` on `AppExecutorUtil.createBoundedApplicationPoolExecutor("My Air ideas", 3)`, `busy(folderId): Set<String>` (idea ids in this IDE's steps), `failures` (last step error per board for the panel), a `ClaudeHomeWatcher(emptyList(), listOf(BoardFiles.INSTANCE.root))` refreshing `boards` off the EDT, and after every step that added ideas, one `judge` submitted per new id.
  - `onProject` (R29) on the EDT: `updateFolders(ChangeSource.AUTO) { it.createFolder(boardId, newId, name, dirs = listOf(dir.toString())) }`.

- [ ] **Step 1: Write the failing tests** (`IdeaStepsTest`: temp `BoardFiles`, `run` returning canned `ClaudeOutput`s and recording args, fixed `Clock`)
- `a short seed needs no call` / `a long seed is distilled` / `a URL seed may fetch only that page` (args contain `--tools WebFetch`, `sourceUrl` stored).
- `expand adds three ideas and the call's cost`.
- `an expand answer with two ideas adds nothing but counts its cost`.
- `a result whose parent was deleted meanwhile is dropped with the reason` — `run` deletes the parent through `files.update` before returning; `expand` throws `parent I1 is gone`; board has no new ideas; spend counted.
- `an off-schema judgement stores judgeError and no scores`.
- `an idea a live IDE judges is not judged again` (`judge` returns false, `run` not called).
- `a gold judgement creates the project once` (`onProject` called with the dir; `project` set) and `a failed project creation keeps the judgement and stores the reason`.
- `every call runs in the board's directory with the board's profile and role model`.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.IdeaStepsTest'` — FAIL.
- [ ] **Step 3: Implement** `IdeaSteps`, then `IdeaService` (not unit-tested: platform service) wiring the real `runClaudeRaw` with a 3-minute timeout, `claudeExecutable()`, `profileEnv(ProfileStore.getInstance().byName(profile))`, `ideAlive(pid, start)`.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.
- [ ] **Step 5: Commit** `Ideas: steps run through claude -p and the idea service`.

---

### Task 9: Autopilot and its lease

**Files:**
- Create: `src/main/kotlin/dev/myair/ideas/AutopilotLease.kt`, `src/main/kotlin/dev/myair/ideas/IdeaAutopilot.kt`
- Modify: `src/main/kotlin/dev/myair/service/ClaudeSessionsService.kt` (start `IdeaAutopilot` beside `ScheduledRuns.getInstance()` at line 125), `src/main/kotlin/dev/myair/MyAirSettings.kt` (`ideasUsdPerDay` as `property(3.0f)`, `ideaProjectsRoot` as `string("")`, two rows in `MyAirConfigurable`), `docs/internals/ideas.md`, `ideas/README.md`
- Test: `src/test/kotlin/dev/myair/ideas/AutopilotLeaseTest.kt`, `src/test/kotlin/dev/myair/ideas/AutopilotStateTest.kt`

**Interfaces:**
- Consumes: `rateCheck`, `nextMove`, `spentOn`, `stepsLastHour` (Task 3), `IdeaService.submit`, `busy` (Task 8).
- Produces:
  - `class AutopilotLease(file: Path, ide: IdeIdentity, alive: (IdeIdentity) -> Boolean)`: `fun holdOrTake(now: Instant): Boolean` (absent → create by a non-replacing move as `RunRecords.claim`; own → renew with `writeAtomically`; another live holder → false; a dead holder → delete, then the create race), `fun release()` (deletes only its own).
  - `enum class AutopilotState { manual, running, idle, error, pace, hourly, backoff, board_budget, global_budget }`
  - `fun autopilotState(board: Board, now: Instant, zone: ZoneId, globalSpent: Double, globalLimit: Double, inFlight: Boolean, random: Random): Pair<AutopilotState, Instant?>` — manual mode → manual; a `rateCheck` halt → that halt with until; `inFlight` (this IDE's step or a `judging` entry) → running; `nextMove` null → idle; `errors > 0` → error; else running.
  - `@Service(APP) class IdeaAutopilot(scope: CoroutineScope)`: tick 30 s once `ClaudeSessionsService.loaded` and while a project is open (R20: nothing runs without an IDE, nothing is caught up); each tick, for every board: submit `judge` for unjudged ideas without `judgeError` whose judging is free or stale (R10, R13); for each `automatic` board whose lease it holds or takes (R19) and that has nothing in flight: `rateCheck` → `nextMove(random)` → submit the step, then its judgements; record `steps` (pruned to the hour), `lastStepAt`, and on failure `errors + 1`, `lastErrorAt`, `lastError` (success resets `errors`). A board switched to `manual` releases its lease after the step in flight.

- [ ] **Step 1: Write the failing tests**
- `AutopilotLeaseTest` (temp dir, two `IdeIdentity`s, `alive` from a mutable set): `the first IDE takes the lease`, `a live holder keeps it`, `a dead holder's lease is taken over`, `the holder renews on each tick`, `release deletes only its own lease`, `two IDEs racing for a free lease give it to one` (two threads, `CountDownLatch` start, exactly one true).
- `AutopilotStateTest`: `manual mode shows manual`, `a halt shows its until`, `a judgement in flight shows running`, `nothing left shows idle`, `a failed last step shows error once the backoff is over`.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideas.Autopilot*'` — FAIL.
- [ ] **Step 3: Implement**; the global spend is `sum over boards on disk of spentOn(today)`; the limit is `MyAirSettings.ideasUsdPerDay`.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`.
- [ ] **Step 5: Commit** `Ideas: autopilot with lease, limits and backoff`.

---

### Task 10: MCP tools and the session system prompt

**Files:**
- Create: `src/main/kotlin/dev/myair/mcp/IdeasMcpToolset.kt`, `src/main/kotlin/dev/myair/ideas/IdeasJson.kt`
- Modify: `src/main/resources/META-INF/my-air-mcp.xml`, `src/main/kotlin/dev/myair/editor/SessionSystemPrompt.kt:33-36` (one sentence after the review sentence), `src/test/kotlin/dev/myair/editor/SessionSystemPromptTest.kt:42-50` (add `IdeasMcpToolset` to `both prompts name every folder tool`), `src/main/kotlin/dev/myair/mcp/README.md`, `docs/internals/ideas.md`
- Test: `src/test/kotlin/dev/myair/ideas/IdeasJsonTest.kt`

**Interfaces:**
- Consumes: `IdeaService`, `IdeaSteps`, `parseChanges`, `autopilotState`, `medal`, `fitness`.
- Produces: `fun ideasJson(path: String, folderId: String, board: Board, state: Pair<AutopilotState, Instant?>, stepsLastHour: Int, spentToday: Double): String` (goal, config, summary, autopilot `{state, until, stepsLastHour, spentToday, lastError}`, ideas each with `medal` and `fitness`); `my_air_ideas_get(board: String)`, `my_air_ideas_apply(board: String, changes: String)` — `board` is the folder id, else its path (as `withReview`); each change runs to its end in order on `IdeaService.submit` (awaited off the EDT); a refused change stops the list with `change <i> (<op>) refused: <reason>`, earlier changes kept; returns `my_air_ideas_get`'s JSON. New ideas' judgements are submitted and not awaited.
- Sentence (R34): "An idea board folder holds ideas Claude branches, crosses and judges: my_air_ideas_get reads a board with its ideas, medals and autopilot state; my_air_ideas_apply seeds, expands, crosses and judges ideas, changes the goal, guidance and settings, summarizes the board and turns an idea into a project, and sets a medal or deletes an idea only on the user's word."

- [ ] **Step 1 (decision from Task 1):** synchronous result → continue. Asynchronous → set row 10 `blocked: needs the user's yes on the async apply and the log key`, ask; on yes, update R4 and R33 in the spec, add `log` to `Board` (Task 2's files) and make `apply` queue LLM changes, returning `{"queued":[{"index":i,"op":...}]}` plus the board.
- [ ] **Step 2: Write the failing tests**
  - `IdeasJsonTest`: `ideas carry their medal and fitness`, `the autopilot state carries its until and spend`.
  - `SessionSystemPromptTest` › `both prompts name every folder tool` with `IdeasMcpToolset` added; `the prompt is one line a Windows command line passes whole` stays green.
- [ ] **Step 3: Run** `./gradlew test --tests 'dev.myair.ideas.IdeasJsonTest' --tests 'dev.myair.editor.SessionSystemPromptTest'` — FAIL.
- [ ] **Step 4: Implement**; descriptions list every op with its fields as `ReviewMcpToolset` does; `isExperimental() = false`.
- [ ] **Step 5: Run** — PASS; `./gradlew test buildPlugin`.
- [ ] **Step 6: Commit** `Ideas: my_air_ideas_get and my_air_ideas_apply`.

---

### Task 11: New Idea Board, the tab with its manager, and the tree row

**Files:**
- Create: `src/main/kotlin/dev/myair/ideasview/IdeasFiles.kt` (`IdeasVirtualFile`, `IdeasFileSystem` with `PROTOCOL = "myair-ideas"`, `@Service(APP) class IdeaTabs`), `IdeasEditor.kt` (provider + `IdeasEditor : ManagerTabEditor`), `IdeasTabsProviders.kt` (title, icon), `IdeasOpener.kt`, `NewIdeaBoard.kt`, `src/main/kotlin/dev/myair/ideasview/README.md`, `src/main/kotlin/dev/myair/ideas/IdeaManager.kt`, `src/main/resources/icons/idea.svg` (+ `idea_dark.svg`)
- Modify: `src/main/resources/META-INF/plugin.xml` (file system, editor provider, icon and title providers, as the review lines 32–35), `src/main/kotlin/dev/myair/editor/SessionIcons.kt` (`IDEA_ICON`), `src/main/kotlin/dev/myair/editor/SessionTerminals.kt:229` (`?: IdeaTabs.getInstance().managerPrompt(file)` after the review's), `src/main/kotlin/dev/myair/toolwindow/SessionRenderers.kt:135-139`, `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt:196-207` (`FolderSummary` gains `gold`), `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt:221,312` (**ask about its uncommitted change first**), `docs/internals/ideas.md`
- Test: `src/test/kotlin/dev/myair/ideas/IdeaManagerTest.kt`, `src/test/kotlin/dev/myair/toolwindow/` (existing `SessionTree` test class: gold count)

**Interfaces:**
- Consumes: `IdeaService`, `BoardFiles`, `placementProblem`, `managerStart`, `hasManagerTranscript`, `FolderTabs`, `ManagerTabEditor`.
- Produces: `fun ideaManagerPrompt(folderId: String, board: Board): String` (one line: names the board id, `my_air_ideas_get`/`my_air_ideas_apply` with `board '<id>'`, and "never change a medal or delete an idea except on the user's word"); `fun ideaManagerStart(board: Board, newId: String, dir: Path, hasTranscript): Pair<PlanManager, List<String>>` (`managerStart(board.manager, newId, dir, dir, board.config.profile, hasTranscript)`); `fun boardGold(folder: Folder, boards: Map<String, Result<Board>>): Int?` (null: no board; gold = `medal == 3`); `fun boardFolderName(title: String) = "Ideas: $title"`; `NewIdeaBoardAction(place: () -> String?, enabled: () -> Boolean)`.

- [ ] **Step 1: Write the failing tests**: `the manager prompt is one line naming the board and both tools`; `a new manager starts in the board directory with the board's profile` / `a stored manager is resumed when it has a transcript`; `a board row counts its gold ideas` (tree test).
- [ ] **Step 2: Run** them — FAIL.
- [ ] **Step 3: Implement.** New Idea Board... (tree menu next to New Review, disabled with the placement reason inside a plan, review or board): a dialog with Title and Goal (both required), then off the EDT `BoardFiles.create(id, Board.new(goal, DEFAULT_PROFILE))`, then on the EDT the folder `Ideas: <title>` under the selected folder (top level when none) and `IdeasOpener.open`. The manager is stored with `IdeaService.submitWrite` (the lock never on the EDT); `IdeaTabs.startManager` mirrors `ReviewTabs.startManager` but stores through that and opens the terminal when it lands. The editor shows a notice for a deleted board and the read error for a broken `board.json`. The row: `IDEA_ICON`, and gold count drawn before the session count in a gold colour, as review blockers are.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`; `runIde`: New Idea Board creates the folder and `board.json`, the tab opens with the manager terminal.
- [ ] **Step 5: Commit** `Ideas: New Idea Board, its tab, manager and tree row`.

---

### Task 12: The idea panel

**Files:**
- Create: `src/main/kotlin/dev/myair/ideasview/IdeasView.kt` (pure view model), `src/main/kotlin/dev/myair/ideasview/IdeasPanel.kt` (`CardPage`)
- Modify: `IdeasEditor.kt` (content and render), `ideasview/README.md`, `docs/internals/ideas.md`
- Test: `src/test/kotlin/dev/myair/ideasview/IdeasViewTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 8, 9 (`evolutionStats`, `autopilotState`, `pairRefusal`, `IdeaService.submit`, `failures`, `busy`), `PanelParts` (`card`, `greyCard`, `head`, `chevron`, `more`, `actionUnless`).
- Produces: `enum class Head { Gold, Silver, Bronze, NoMedal, NotJudged, JudgeFailed }`; `data class IdeasView(heads: List<Pair<Head, List<Idea>>>, stats: EvolutionStats, summaryStale: Boolean, state: Pair<AutopilotState, Instant?>, stepsLastHour: Int, spentToday: Double)`; `fun ideasViewOf(board, now, zone, globalSpent, globalLimit, inFlight, random): IdeasView`; `fun related(board: Board, id: String): Set<String>` (ancestors and descendants); `fun children(board, id): List<String>`; `fun createProjectProblem(root: String?, idea: Idea): String?` (`Set the projects folder in Settings › Tools › My Air` while empty; `Already a project` when `project` set).

- [ ] **Step 1: Write the failing tests**
- `ideas are grouped under the six heads` (an idea with `judgeError` → Judge failed; unjudged → Not judged; hand-set medal → its head).
- `within a head ideas go by fitness, then newest first`.
- `a summary is stale once the board has more ideas than it counted`.
- `related marks ancestors and descendants only` (siblings excluded).
- `Create Project is disabled without a projects folder`.

- [ ] **Step 2: Run** `./gradlew test --tests 'dev.myair.ideasview.IdeasViewTest'` — FAIL.
- [ ] **Step 3: Implement** the cards of the spec's figure: Header (goal edited in place, mode chooser, state with until, `steps/limit h`, `$x today`, stats line `bred n · first 8: a → latest 8: b · best c`, last failure), Seed (text field + Seed), Summary (Summarize enabled with ≥ 2 ideas; headline, themes, strongest, gaps, next steps; `stale: n → m`), Ideas (folding heads; folded row: medal, sum, title, kind, `g<n>`, `...`; unfolded: pitch, scores with reason, keywords, parents and children as links that scroll to them, source URL, project path; selection marks `related`). `...` menu: Expand, Cross with... (searchable `JBPopupFactory` list of other ideas, entries with a `pairRefusal` disabled), Judge Again, Set Medal (None, Bronze, Silver, Gold, Automatic), Create Project (`createProjectProblem`), Start Session in Project (enabled with `project`: `ClaudeSessionOpener.startNew(project, dir, dir, newId, Preset("Idea project").claudeArgs(PROJECT_FIRST_MESSAGE), folderId = <the project subfolder>, profile = board's)`), Delete (confirm). Every action runs through `IdeaService.submit`; failures land in a balloon and the header.
- [ ] **Step 4: Run** — PASS; `./gradlew test buildPlugin`; `runIde` with a board: seed, expand, cross, judge, set medal, delete, summarize each change the list.
- [ ] **Step 5: Commit** `Ideas: the board panel`.

---

### Task 13: `calibrateJudge`

**Files:**
- Create: `src/test/kotlin/dev/myair/ideas/JudgeCalibration.kt` (`@Tag("calibration")`, header naming the original and its MIT licence)
- Modify: `build.gradle.kts:49-53` (`tasks.test` excludes tag `calibration`; `tasks.register<Test>("calibrateJudge")` includes only it, same `testClassesDirs` and `classpath`, no `CLAUDE_CONFIG_DIR`, `outputs.upToDateWhen { false }`, standard streams shown), `CLAUDE.md` (Commands: `./gradlew calibrateJudge`, and "re-run it whenever the judge's prompt, schema or model changes"), `docs/internals/ideas.md`

**Interfaces:**
- Consumes (no `IdeaSteps`, no board): `ideaArgs`, `JUDGE_SYSTEM`, `judgePrompt`, `parseEnvelope`, `parseJudgement`, `runClaudeRaw`, `BoardConfig.defaults().models[Role.judge]`.

- [ ] **Step 1: Write the calibration test** `the judge gives weak ideas no medal and strong ideas a mean of at least 9`: the original's 6 weak and 5 strong texts (Swedish ones translated to English faithfully), split `title: pitch` as `asIdea` does, each judged twice with an empty goal in a temp dir; prints every sum with scores and reason and the total cost; asserts no weak sum ≥ 9 and strong mean ≥ 9.
- [ ] **Step 2: Run** `./gradlew test` — the calibration test does not run; `./gradlew calibrateJudge` — runs 22 calls and prints the sums. Record the output summary (weak max, strong mean, cost) in this task.
- [ ] **Step 3: Commit** `Ideas: calibrateJudge task`.

---

### Task 14: Sandbox walk

**Files:**
- Modify: this plan (evidence), `docs/internals/ideas.md` (anything the walk corrects)

- [ ] **Step 1:** `./gradlew test buildPlugin`, start the sandbox as CLAUDE.md says, with `ideaProjectsRoot` set to a scratch folder.
- [ ] **Step 2: Walk** and record each as `checked:` evidence: New Idea Board under a folder and at top level (R1); seed a short text, a long text and a URL (R7); expand and cross (R8, R9); judgements arrive without a click (R10); set a board to automatic with `paceSeconds` 30 and watch steps, the header state and spend (R19, R21); close the sandbox IDE for 2 minutes, reopen, confirm no step ran meanwhile and none is caught up (R20); a gold idea (or Set Medal Gold, then Judge Again on a strong idea) gets its project folder and subfolder; Start Session in Project starts with the first message (R27–R30); the tab shows the manager (R31); another session seeds through `my_air_ideas_apply` (R32, R33); the row has the idea icon and gold count (R35).
- [ ] **Step 3: Commit** the evidence with `Plan-Task: 14/14`.

---

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 11, 14 | |
| R2 | 2 | |
| R3 | 6 | |
| R4 | 2 | |
| R5 | 2 | |
| R6 | 2 | |
| R7 | 4, 8, 14 | |
| R8 | 4, 8 | |
| R9 | 3, 5, 8 | |
| R10 | 4, 8 | |
| R11 | 4, 8 | |
| R12 | 3 | |
| R13 | 5, 8, 9 | |
| R14 | 1, 4, 8 | |
| R15 | 4, 5, 8 | |
| R16 | 2, 8 | |
| R17 | 3 | |
| R18 | 3 | |
| R19 | 9 | |
| R20 | 9, 14 | |
| R21 | 3, 9, 12 | |
| R22 | 12 | |
| R23 | 12 | |
| R24 | 12 | |
| R25 | 5, 12 | |
| R26 | 4, 8, 12 | |
| R27 | 7, 8, 12 | |
| R28 | 7 | |
| R29 | 8, 12, 14 | |
| R30 | 8 | |
| R31 | 11, 14 | |
| R32 | 10 | |
| R33 | 1, 5, 10 | |
| R34 | 10 | |
| R35 | 11 | |
| R36 | 13 | |
| R37 | 4 | |
