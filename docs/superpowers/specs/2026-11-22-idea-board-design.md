# Idea board: a folder where Claude branches, crosses and judges ideas, and gold ones become projects

## TL;DR

- **Goal:** the core of `E:\dev\projects\personal\idea-generator-2` (MIT) inside My Air: a board with a goal, ideas branched (expand) and crossed (combine) by Claude, a calibrated jury whose medal is computed in code, an evolution autopilot, and a gold idea turned into a Claude Code project folder with a session ready to start.
- **Approach:** a third kind of folder beside plan and review. The folder is an ordinary folder of `structure.json`; its data lives in `~/.my-air/ideas/<folderId>/board.json`. Every LLM step is one `claude -p --json-schema` call, as `dashboard/Analyzer.kt` does. The tab shows a list (no canvas) on the left and the board's manager session on the right. Two MCP tools let any session read and change a board.
- **Decided with you:** a list first, with the canvas later; the autopilot runs only while an IDE runs.
- **Decided here, check it:** limits are steps per hour plus USD per day per board and globally, as in the original. `total_cost_usd` is notional on a subscription but still a usage proxy. Defaults: 12 steps/h, $1 per board, $3 global. A gold idea gets its project folder written by itself (if the board allows it), but its session starts only on a click. The original's `-2` suffix and silent clamping of scores are replaced by refusals (no fallbacks).
- **Needs your input:** the default limits above, and whether auto-project on gold should also start the session.
- **Risk:** `my_air_ideas_apply` with expand, combine or judge waits for a `claude -p` call (10–40 s); the IDE's MCP call timeout for that is untested. Task 1 is a spike.
- **Out of scope:** the canvas, scouts (X, research), announce or guest ideas across boards, spin-off boards, keyword trends and the cross-board report, exhibition mode, providers other than `claude`, per-board prompt flows, the Swedish UI, the `vault/` folder.

## Requirements

- **R1** New Idea Board... in the session tree menu asks for a title and a goal, then creates the folder `Ideas: <title>` under the selected folder (top level when none), writes `board.json` and opens the tab.
- **R2** `structure.json` keeps its version and format: an idea board is an ordinary folder whose id names `~/.my-air/ideas/<folderId>/board.json`.
- **R3** An idea board folder holds no plan and no review, and lies in no plan, review or other idea board folder. Every structure write refuses a new placement that breaks this, as `refuseReviewPlacement` does for reviews.
- **R4** `board.json` holds `version` 1, `goal`, `config`, `ideas`, `nextId`, `spend`, `summary`, `autopilot` and `judging`. An unknown key, a missing field or an unknown enum value throws, and the panel shows the error.
- **R5** Every change to a board is a function applied to the board read from disk, inside an exclusive `FileChannel` lock on `~/.my-air/ideas/<folderId>/board.lock`, and written with `writeAtomically`. A change from another IDE between two writes is never lost.
- **R6** An idea has `id` (`I<n>`, never reused), `title` (≤ 120 chars), `pitch` (≤ 600), `kind` (`seed`, `expand`, `combine`), `parents` (ids), `generation`, `sourceUrl`, `scores` (`originality`, `feasibility`, `impact`, each 1–5, or null), `reason`, `keywords`, `medalOverride` (0–3 or null), `judgeError`, `project` (path or null) and `created`.
- **R7** Seeding text of up to 160 chars stores it as the title. Longer text, or a single `http(s)` URL, is first distilled by `claude -p` into one title and pitch. For a URL that call may use only `WebFetch`, and a page it cannot read fails the seed with the reason.
- **R8** Expand asks `claude -p` for exactly 3 ideas from one parent, given the goal and the titles already on the board. Any other count fails the step, and nothing is added.
- **R9** Combine asks `claude -p` for 1 idea from two different ideas of the same board. The same pair, or a parent with its own child, is refused.
- **R10** Every new idea is judged once without a click. The judge call uses the original's `JUDGE_SYSTEM` (reason first, then three scores and 2–4 keywords) plus the board's `judgeGuidance` and the keyword vocabulary in use.
- **R11** A judge answer with a score outside 1–5, a missing field, or no `structured_output` stores `judgeError` with the envelope text and no scores. Nothing is clamped or guessed.
- **R12** `medal` is computed in Kotlin: `medalOverride` when set, else bronze from a sum of 9, silver from 11, gold from 13, none below 9, null while unjudged. `fitness` counts a hand-set medal as 6, 10, 12 or 14.
- **R13** At most 3 `claude -p` calls run at once per IDE. An idea is judged by one IDE at a time: `judging` holds its id with the IDE's pid, and an entry whose IDE is not alive (`ideAlive`) or that is older than 5 minutes is taken over.
- **R14** Every `claude -p` call of a board runs with the board's Claude profile (`profileEnv`), in `~/.my-air/ideas/<folderId>`, with `--no-session-persistence --output-format json --json-schema <schema> --strict-mcp-config --setting-sources project --system-prompt <role system>` and the role's model. `--tools ""` is passed except for the URL distill (R7). The user text goes through stdin.
- **R15** A call's `total_cost_usd` is added to `spend[<local date>]` of its board in the same write that stores its result. A call that fails still adds its cost when the envelope reports one.
- **R16** `config.models` names a model per role (`distill`, `expand`, `combine`, `judge`, `summary`, `brief`). The default is `sonnet` for each.
- **R17** `nextMove` (pure) picks the autopilot's step as `evolve.js` does: tournament selection (k = 3) on `fitness` over judged ideas not busy. With probability 0.2, or with fewer than 3 judged ideas, it expands the strongest idea not yet expanded. It never repeats a combined pair or pairs a parent with its child, and it returns null when nothing is left.
- **R18** `rateCheck` (pure) allows a step unless one of these holds, checked in order: global USD today ≥ limit; board USD today ≥ limit; error backoff (30 s, doubling, at most 15 min); steps in the last hour ≥ limit; pace (seconds since the last step) not reached. A USD limit of 0 means free calls only.
- **R19** A board's autopilot runs in one IDE at a time. That IDE holds `autopilot.lease` (`CREATE_NEW`, with its pid) and renews it on each 30 s tick. Another IDE takes over only when the holder's IDE is not alive, by deleting the lease and winning a new `CREATE_NEW`.
- **R20** The autopilot runs only while an IDE runs. With no IDE running nothing happens, and no step is caught up later.
- **R21** The header shows the autopilot's state (`manual`, `running`, `idle`, `error`, `pace`, `hourly`, `backoff`, `board_budget`, `global_budget`) with its `until` time, the steps in the last hour, the spend today, and the evolution stats (mean fitness of the first 8 and the latest 8 bred ideas, and the best).
- **R22** The list groups ideas under folding heads Gold, Silver, Bronze, No medal, Not judged and Judge failed. Within a head, ideas are ordered by fitness, then newest first.
- **R23** A folded idea row shows its medal, sum, title, kind and generation. Unfolded, it shows the pitch, the three scores with the reason, the keywords, its parents and children as links that scroll to them, the source URL and the project path.
- **R24** Selecting an idea marks its ancestors and descendants in the list.
- **R25** An idea's `...` menu offers Expand, Cross with... (a searchable popup of the board's other ideas, with the used pairs disabled), Judge Again, Set Medal (none, bronze, silver, gold, automatic), Create Project, Start Session in Project, and Delete. Delete keeps the children and removes the id from their `parents`.
- **R26** Summarize (two or more ideas) asks `claude -p` for a headline, 2–5 themes, the 3 strongest ideas with why, gaps and next steps, and stores it in `summary` with the idea count and the time. The Summary card shows it, marked stale once the board has more ideas than it counted.
- **R27** Create Project needs `MyAirSettings.ideaProjectsRoot` and is disabled with that reason while it is empty. It asks `claude -p` for the original's brief schema, then creates `<root>/<slug>` with a non-recursive create. A taken name fails, naming the folder.
- **R28** The project folder gets `CLAUDE.md` (idea, goal, the jury verdict, problem, audience, MVP, out of scope, stack, first steps, open questions, risks, lineage, working rules), `README.md`, `idea.json` and `git init`. The idea's `project` is set in the same write.
- **R29** Then a subfolder of the board folder, named after the project, is bound to the project directory. Start Session in Project starts a session there whose first message is `Read CLAUDE.md and propose a plan for the first step.`
- **R30** With `config.autoProjectOnGold` (default true), a judgement that yields gold with no `project` runs Create Project by itself. A failure there stores the reason on the idea and is shown, and it does not undo the judgement.
- **R31** The tab `myair-ideas://<folderId>` shows the panel on the left and the board's manager session on the right, on `FolderTabs` and `ManagerTabEditor` as the review tab does. The manager is stored in `board.json` and started in the board's directory with a prompt that names the board and the idea tools.
- **R32** `my_air_ideas_get(board)` returns the board (goal, config, summary, autopilot state) and its ideas with medal and fitness. `board` is the folder's id or path.
- **R33** `my_air_ideas_apply(board, changes)` applies `seed`, `expand`, `combine`, `judge`, `set_medal`, `delete`, `set_goal`, `set_guidance`, `set_config`, `summarize` and `project`. Each change runs to its end, in order, and a refused one stops the list naming it and its index. An unknown op or field fails naming it.
- **R34** `SessionSystemPrompt` gains one sentence naming both idea tools. `IdeasMcpToolset` is listed in `my-air-mcp.xml`.
- **R35** An idea board's row and tab carry an idea icon, and the row shows the gold count before the session count.
- **R36** Gradle task `calibrateJudge` (not part of `test`) judges the original's 6 weak and 5 strong reference ideas twice each with the real judge. It fails when any weak idea gets a medal or when the strong mean is below 9, and it prints every sum. CLAUDE.md says to re-run it whenever the judge's prompt, schema or model changes.
- **R37** The ported prompts and reference ideas carry the original's MIT notice (copyright line and licence text) in `ideas/README.md`.

## Why

idea-generator-2 is a separate Node server with its own SQLite, browser UI and provider keys. Its best parts are the calibrated jury, the evolution loop and the hand-off from a gold idea to a Claude Code project. In My Air those parts gain things the original does not have: the project's session shows up live in the tree, the manager is a real Claude session beside the board, and any session can seed a board through MCP ("put this as an idea on board X"). The rest of the original (scouts, the cross-board report, exhibition mode) is display around that core and can come later.

Anti-goal: a board that silently produces junk. Off-schema answers fail visibly (R8, R11), and the medal is never the model's word (R12).

## Approaches

| | How an LLM step runs | Verdict |
|---|---|---|
| **A** | one `claude -p --json-schema` per step, no session (R14) | **picked**: a fresh context per judgement keeps the scale fixed, works with no one at a terminal, and its cost is reported; the pattern exists in `Analyzer.kt` |
| B | the manager session does every step through MCP | rejected: its judgements drift as its context fills, and the autopilot would need a session kept busy |
| C | a background `claude --bg` per step | rejected: heavy for a 10 s call, and no structured output |

| | Where the board lives | Verdict |
|---|---|---|
| **A** | folder in `structure.json`, data in `~/.my-air/ideas/<id>/board.json` | **picked**: same as reviews, no version bump |
| B | a standalone tab like Scheduled Tasks | rejected: a board has its manager and project sessions, which belong in the tree |

## Design

### Package and files

New packages `dev.myair.ideas` (model, JSON, pure `Score.kt`, `Evolve.kt`, `Rate.kt`, `IdeaPrompts.kt`, `IdeaCalls.kt`, `ProjectWriter.kt`, `IdeaService`, `Autopilot`) and `dev.myair.ideasview` (tab, panel, New Idea Board). Each gets a `README.md`. `docs/internals/ideas.md` is added and linked from the CLAUDE.md table.

### Data

`board.json` (R4, R6):

```json
{
  "version": 1,
  "goal": "Tools that cut review time for agent-written code",
  "config": {
    "mode": "manual",
    "judgeGuidance": "",
    "models": {"distill": "sonnet", "expand": "sonnet", "combine": "sonnet", "judge": "sonnet", "summary": "sonnet", "brief": "sonnet"},
    "profile": "default",
    "autoProjectOnGold": true,
    "paceSeconds": 60, "stepsPerHour": 12, "usdPerDay": 1.0, "mutationRate": 0.2
  },
  "ideas": [{"id": "I1", "title": "...", "pitch": "...", "kind": "seed", "parents": [], "generation": 0,
             "sourceUrl": null, "scores": {"originality": 2, "feasibility": 4, "impact": 3}, "reason": "...",
             "keywords": ["review"], "medalOverride": null, "judgeError": null, "project": null,
             "created": "2026-11-22T10:00:00Z"}],
  "nextId": 2,
  "spend": {"2026-11-22": 0.031},
  "summary": null,
  "autopilot": {"errors": 0, "lastErrorAt": null, "lastStepAt": null, "steps": []},
  "judging": {"I1": {"ide": 12345, "since": "2026-11-22T10:00:01Z"}},
  "manager": null
}
```

The global limit `ideasUsdPerDay` (default 3) and `ideaProjectsRoot` live in `MyAirSettings`. The global spend is the sum of every board's `spend[today]`.

### A step

```figure
 IDE thread pool (max 3)            board.lock held only here
 ───────────────────────            ─────────────────────────
 read board ─► build prompt ─► claude -p ─► parse ─► {accent:lock} ─► re-read ─► apply ─► write
                             {note:10–40 s, no lock}       {warn:parent gone: drop, show}
```

The call runs outside the lock (R5). The result is applied to the board as it is on disk then. A parent deleted meanwhile drops the result and shows the reason. Ids are given at apply time.

### Autopilot

`IdeaAutopilot` is an application service with a 30 s tick, like `ScheduledRuns`. For each board in `automatic` mode whose lease it holds or takes (R19), it runs `rateCheck`, then `nextMove`, then the step, and it waits for that step's judgements before the next step of the same board. An error raises `errors` and sets `lastErrorAt`; a success resets `errors`. Switching a board to `manual` stops it after the step in flight.

### Panel

Built from the plan and review panels' parts (cards, folding heads, `ActionButton`s, edits from menus by id):

```figure
┌ Header ───────────────────────────────────────────────────────┐
│ Goal: Tools that cut review time ...                  [edit]  │
│ Autopilot [automatic v]  {ok:running} · 7/12 h · $0.42 today       │
│ {dim:bred 31 · first 8: 7.9 → latest 8: 10.4 · best 13}             │
├ Seed ─────────────────────────────────────────────────────────┤
│ [ text or URL                                      ] [Seed]   │
├ Summary ─────────────────────────────── {dim:stale: 31 → 38} ───┤
├ Ideas ────────────────────────────────────────────────────────┤
│ ▼ Gold (2)                                                    │
│   {ok:●} 13  Diff narrator for PRs      combine · g3      ...  │
│ ▼ Silver (5)                                                  │
│ ▶ Not judged (1)  ▶ Judge failed (1)                          │
└───────────────────────────────────────────────────────────────┘
```

### Manager

It starts as the review manager does (`reviewManagerStart` pattern): a new session in `~/.my-air/ideas/<folderId>`, else the stored one resumed. Its prompt says: the board is named by id; read it with `my_air_ideas_get`; seed, expand, cross and judge with `my_air_ideas_apply`; never change a medal or delete an idea except on the user's word.

## Testing

Plain JUnit 5 on pure code:

- `board.json` round trip and refusals (R4, R6);
- `Score` (R12);
- `nextMove` with a seeded `Random` (R17);
- `rateCheck` (R18);
- the parsing of each schema's envelope, including off-schema answers (R8, R11);
- the change list (R33);
- placement (R3);
- `ProjectWriter` against a temp dir (R27, R28);
- the lease (R19) and the lock (R5), with two threads and real files.

`claude -p` is behind a function type, so tests pass canned envelopes. The real call is exercised only by `calibrateJudge` (R36) and by a sandbox walk-through.

## Out of scope

The canvas (next step, on this data model), scouts, announce and guests, spin-off boards, keyword trends and the report, exhibition mode, other providers, custom prompt flows per board, the `vault/` folder, and running with no IDE open.
