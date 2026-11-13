# Dashboard: what is left to do in each session

## TL;DR

- **Goal:** the user no longer remembers what was left to do in sessions the dashboard lists; an Analyze action has Haiku read each one and write under its row what remains, with a verdict.
- **Approach (user's pick, 1):** one `claude -p --model haiku` call per session, a few in parallel, off the EDT, on a digest of its transcript; results land in the rows one by one and are stored per session in `~/.my-air/analysis/`.
- **Decided by the user:** the analysis only describes (variant A): a verdict chip and a "Left:" line; sections Now / Unfinished / Finished stay decided by the hard signals of the dashboard spec.
- **My calls to check:** analysed are the sessions of Unfinished and of Finished except merged ones and those whose git facts are still loading; the transcript is read at analysis time from a path the scanner now records (all prompts, last 5 agent texts), not kept in the scan cache; Haiku runs without the user's hooks, plugins and MCP servers; a session analysed is skipped until it gets a newer last message, then its old result is hidden and it is analysed again; Haiku runs in `~/.my-air/analysis/` with no tools, so no project CLAUDE.md or tool call costs anything; the summary is written in the language of the session's own prompts; 3 calls at a time.
- **Date limit (user's request):** a run takes only sessions whose last message is within the chosen 7 / 14 / 30 days or all, default 14 days (my call: the default and the steps).
- **Risks:** a first run with "all" over ~250 sessions costs ~250 Haiku calls; a digest that leaves out the decisive message gives a wrong "Left:".
- **Out of scope:** moving sessions between sections by the verdict, analysing sessions in Now or settled, Jira/Bitbucket.

## Requirements

- **R1** The dashboard header has an Analyze button; a click starts one analysis run over the sessions listed in Unfinished and Finished at that moment, leaving out merged sessions (`Chip.Merged`), sessions whose git facts are not read yet (`Chip.Pending`; a later run takes them) and those whose stored analysis is current (R3).
- **R11** Beside Analyze a chooser limits the run to sessions whose `lastMessage` is within the last 7, 14 or 30 days, or takes all; the default is 14 days and the choice is kept across restarts (application property `dev.myair.dashboard.analysisDays`); the sessions R1 offers are counted against it at the moment of the click.
- **R2** While a run goes on the button reads Cancel and the header shows `Analyzing <done>/<total>`; Cancel stops starting new calls and kills those running; closing the dashboard tab cancels the run the same way.
- **R3** An analysis is current while the session's `lastMessage` equals the `lastMessage` stored with it; a session whose analysis is not current shows no analysis and is analysed by the next run.
- **R4** Each analysis is one `claude -p --model haiku --no-session-persistence` process with no tools (`--tools ""`), no MCP servers (`--strict-mcp-config` without `--mcp-config`) and only project settings (`--setting-sources project`, so the user's hooks and plugins do not run), started in `~/.my-air/analysis/`, given a digest of the session (R6), answering in JSON validated by `--json-schema`: `verdict` (`done`, `open` or `unclear`) and `left` (one or two sentences; empty only with `done`).
- **R5** At most 3 analysis processes run at a time; each is killed after 2 minutes and counts as failed.
- **R6** The digest holds the session's title, the brief's goal and tasks with statuses, the plan task its brief names with status, its open signals as the dashboard's chips name them, the files it wrote, every human prompt and the text of the last 5 assistant messages outside sidechains, each prompt and message cut to 2000 characters, the whole cut to 40000 characters by dropping the oldest prompts first.
- **R7** A finished analysis is written to `~/.my-air/analysis/<sessionId>.json` (`lastMessage`, `verdict`, `left`, `analyzedAt`) atomically and the dashboard shows it at once, without waiting for the run to end; every IDE's dashboard reads the same files.
- **R8** A row with a current analysis shows the verdict as a chip (`done`, `open`, `unclear`) after its other chips and, unless the verdict is `done`, a second line `Left: <left>` in grey under the title.
- **R9** A failed analysis (non-zero exit, timeout, output not matching the schema) is not stored; its row shows an `analysis failed` chip with the error in its tooltip until the next run, which tries it again.
- **R10** No analysis process starts or is waited for on the EDT.

## Why

The dashboard (spec `2026-11-13-session-dashboard-design.md`) tells that a session is unfinished (`brief 2/5`, `uncommitted`, `asks`), not what was left in it. The user's words: "nie pamiętam, co w nich zostało do zrobienia". Opening each session and scrolling its transcript is what this saves.

## Goals, non-goals, anti-goals

- Goal: for every session listed in Unfinished, one glance at its row says what remains.
- Goal: repeated runs cost only the sessions that changed since their analysis.
- Non-goal: deciding sections or hiding sessions by the verdict (the user picked "describe only").
- Non-goal: a combined report across sessions.
- Anti-goal: an analysis shown for a session that moved on since it was made.
- Anti-goal: analysis leaving transcripts that show as sessions in My Air, or running tools in the user's repositories.
- Anti-goal: the IDE stalling during a run.

## Approaches

| | Shape | Verdict |
|---|---|---|
| **1** | `claude -p --model haiku` per session in the background, results stored per session | **picked** by the user: per-session quality, results land one by one, a cancel or crash loses only calls under way, pattern of `suggestBranchName` |
| 2 | One interactive Claude session given the list, writing results through a new MCP tool | slow and costly (main model), needs a new tool, completeness depends on the agent |
| 3 | One `claude -p` call with every digest | cheapest, but overflows the context at tens of sessions and one failure loses the run |

Variant B of the earlier question (a `done` verdict moves the session to Finished) was declined: a wrong verdict would hide unfinished work in the folded section that Settle All clears.

## Behaviour

```
Sessions   17 in the last 2 days · 244 older   [14 days ▾] [Analyze]
...
Unfinished                                                   12
┌ rtm-cloud / security
│ ● TR-11887 Websocket authz    3 d   uncommitted · brief 2/5 · open
│   Left: integration test for the handshake, PR not created
│ ● Logging cleanup             5 d   asks · done
└
```

During a run the button reads `Cancel` and `Analyzing 7/31` stands beside it. Strings, colours and placement within the header are the builder's.

## Data and code

- **Store** (`dashboard/Analyses.kt` or `store/`): read all of `~/.my-air/analysis/*.json` on the refresh thread or a pooled thread, never the EDT; write with `writeAtomically`. A file that does not parse throws (no silent skip).
- **Digest** (pure function over parsed lines, tested): built from `ClaudeSession` (title, brief, changedFiles) plus the dashboard's chips for the session, and, read from the transcript file at analysis time on the runner's thread, every human prompt (as `promptText` reads one) and the last 5 assistant text messages outside sidechains and `isMeta`. `ClaudeSession.prompts` keeps only the first 3 (`PROMPTS`), and keeping all in the scan cache would grow it for every session on every refresh, so the scanner instead records which file counted for the session (`ClaudeSession.transcript: Path`, the copy chosen by the latest-message / longest rule); this changes the transcript-reading classes, so the scan cache is reread once, as designed. (My call, after the builder check.)
- **Isolation** (my call): the flags of R4 keep the user's hooks, plugins and MCP servers out of ~250 calls, for time and side effects. The builder checks with the installed `claude` that `--setting-sources project` still signs in with the user's OAuth login (`--bare` is not used for that reason) and notes whether `~/.claude/CLAUDE.md` is still loaded; either way the prompt names the answer's language.
- **Runner**: a bounded executor of 3 (`AppExecutorUtil.createBoundedApplicationPoolExecutor`), the process started as `suggestBranchName` does (stdin closed, stdout/stderr read on futures, `waitFor` with a timeout), flags `--output-format json --json-schema <schema> --tools ""` and the digest after `--`; the structured result is read from the JSON envelope's structured output field (the builder checks its name against the installed `claude`).
- **Model** (`Dashboard.kt`): `Row` gains the analysis state (`none`, `current(verdict, left)`, `failed(message)`), `chipText` the verdict and failure chips; `dashboardOf` takes the analyses map and drops one whose `lastMessage` differs (R3).
- **Editor** (`DashboardEditor.kt`): the button, progress and cancel; each landed result rebuilds as git facts do today.

## Testing

- `DashboardTest`: R11 (a session older than the chosen days not offered, "all" offers it), R3 (stale analysis dropped), R8 chip and line, R9 failure chip, merged rows not offered for analysis (R1).
- Digest test: field order, the cuts of R6, oldest prompts dropped first.
- Store test: write, read back, a malformed file throws.
- Runner: a test with a fake executable standing for `claude` (a script echoing a fixed JSON, one exiting non-zero, one sleeping past a short timeout) checks parsing, failure and timeout.
- Manual in `runIde`: R1, R2 (cancel, closing the tab), R7 (second IDE sees the result), one real run over a few sessions with the verdicts read by the user.

## Left to the builder

Visual details, where the store class lives, the exact Haiku prompt (it must ask for what remains to be done, judged from the last messages over the first ones, in the language of the human prompts), how the digest gets the last assistant texts (above).
