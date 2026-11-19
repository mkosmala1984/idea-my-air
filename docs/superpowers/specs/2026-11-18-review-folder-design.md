# Review folder: one place for the review of a branch or pull request

## TL;DR

- **Goal:** a new kind of folder, Review, that opens in an editor tab like a plan: the branch's description and an explanation of its changes, the reviewers (skills and agents) run on it, their findings merged and triaged, questions for the author; its manager session beside it.
- **Approach:** the folder is an ordinary folder of `structure.json`; everything else lives in `~/.my-air/reviews/<folderId>/review.json`, changed by the plugin's view and by three new MCP tools. Reviewers are real `claude` sessions run as background jobs (`claude --bg`), filed in the review folder, each reporting through `my_air_review_report`.
- **Decided with you:** a review linked to a plan mirrors its blockers into the plan's `review` (spec `2026-11-18-plan-ideal-state-design.md`); reviewers are real sessions, not `claude -p`; Bitbucket only, through the manager's Atlassian MCP, never from the plugin.
- **Decided here, check it:** review data is not in `structure.json`, so the file's version does not move and does not collide with version 5 of the plan spec; a review folder and its data are joined by the folder id.
- **Needs your input:** which extras stay in scope: reviewer profiles (R18–R20), review of new commits only (R27–R29) and reviewer precision (R30) are in; editor gutter marks, PR comment replies, dashboard and taskbar are out. Say if you want any moved.
- **Risks:** `claude --bg` ignores `--session-id` (checked in task 1), so a reviewer's session is known only once the registry lists its job; a report names its run, not its session.
- **Out of scope:** the plugin calling Bitbucket, reading PR comments back, gutter annotations, tokens per reviewer, a settings editor for profiles.

**Plan:** my-air/plans/Węzeł Review brancha-PR – propozycja

Requirements R1–R35 live only in that plan (`my_air_plan_get`), each with its eval kind, delivering tasks and evidence.

## Intent and why

The user reviews many branches: `rtm-cloud/pr-reviews` holds over 40 sessions, one per review skill per branch, their results buried in transcripts. Several skills (`rtm-pr-review`, `rtm-adversarial-review`, `code-review`, `security-review`, `simplify`) find overlapping things; nothing shows which findings several of them agree on, what was decided about each, or whether a review is still current after new commits. A plan answers "what are we building"; a review should answer "what does this branch do, what is wrong with it, what did we decide, is that still true at HEAD".

## Goals, non-goals, anti-goals

- Goal: one tab per branch with the description, the explanation, every reviewer's findings merged, and their triage.
- Goal: agreement between reviewers is visible (`3/4`), and over time which reviewer is worth running (R30).
- Non-goal: the plugin talking to Bitbucket; the manager does it with the Atlassian MCP.
- Non-goal: posting all findings automatically; the user decides per finding.
- Anti-goal: parsing reviewer output text; findings come only through `my_air_review_report`.
- Anti-goal: a finding state that changes by itself; `maybe_fixed` is derived and shown, never written.

## Constraints

- `structure.json` keeps its format: a review folder is an ordinary folder (R1), so an installed older My Air still reads the file.
- Plan view rules apply to the review panel: edits only from menus, by id against the data as it is then, fold rebuilds only what changed, `ActionButton`s rather than toolbars, the composer's look (`prompt/Composer.kt`).
- Tests stay plain JUnit 5 on pure code: the review JSON, `ReviewChanges`, `mergeFindings`, `maybeFixed`, `precision`, the run status, the plan mirror, the prompts; `GitTest` for the range diff.

## Approaches

| | Where the review lives | Verdict |
|---|---|---|
| **A** | folder in `structure.json`, data in `~/.my-air/reviews/<id>/review.json` | **picked**: no version bump, machine output out of the folder history, joined by id |
| B | a `review` field of `Folder` in `structure.json` | every reviewer report a history snapshot; version 6 on top of the plan spec's 5 |
| C | review as a kind of plan (milestones = reviewers) | findings and triage do not fit tasks; the plan's own `review` would point at itself |

| | How a reviewer runs | Verdict |
|---|---|---|
| **A** | `claude --bg` job, attachable | **picked**: no tab per reviewer, resumable, live status already read (`LiveSession.job`) |
| B | a session tab each | five tabs per review |
| C | `claude -p` | cannot be asked a follow-up, no transcript to open |

## Design

### Files

`~/.my-air/reviews/<folderId>/review.json` (`store/Reviews.kt`, written with `writeAtomically`):

```kotlin
enum class Severity { blocker, major, minor, nit }
enum class FindingState { open, accepted, rejected, fixed, posted }
data class Report(val run: String, val reviewer: String, val commit: String, val line: Int, val severity: Severity, val summary: String, val failureScenario: String)
data class Finding(val id: String, val file: String, val category: String, val reports: List<Report>, val state: FindingState = FindingState.open,
                   val reason: String? = null, val fixCommit: String? = null, val comment: String? = null)
data class Run(val id: String, val reviewer: String, val session: String?, val job: String?, val range: String, val started: String, val reported: String? = null)
data class Chapter(val title: String, val text: String, val files: List<String>)
data class Question(val id: String, val text: String, val reviewer: String?, val resolved: Boolean = false)
data class BranchReview(val branch: String, val base: String, val dir: Path, val pr: Int?, val plan: String?, val manager: PlanManager?,
                  val summary: String?, val chapters: List<Chapter>, val runs: List<Run>, val findings: List<Finding>,
                  val questions: List<Question>, val next: Map<String, Int>)   // per prefix F/Q/W (run), next number
```

A finding's line and severity are its newest report's line and the highest report severity (R23); a report keeps the reviewer's own text. `mergeFindings(review, report)` is pure: each incoming finding joins an existing one of the same `file` and `category` whose newest line is at most 3 away (the nearest when several), else becomes `F<next>`. Reports of a run replaced by a second report of the same run are removed first (R17).

`~/.my-air/review-profiles.json` (`store/ReviewProfiles.kt`): `{"profiles": [{"name": "RTM PR", "reviewers": [{"name": "rtm-pr-review", "prompt": "/rtm-pr-review", "model": "opus", "effort": "high"}]}]}`. Read on every panel update, cached by modification time, as `PresetsGroup` reads `presets.json`.

### Service

`ReviewStore` (application service) reads every `review.json` on refresh and on file events of `~/.my-air/reviews/`, holds them by folder id, and notifies subscribers; writes go through it so the panel and the MCP tools see one state. Run status (R21) is derived from `ClaudeSessionsService`'s live entries by job id. Git facts of a review's `dir` (`Git.state`, HEAD, the reviewed commit's distance, range hunks for `maybeFixed`) are read off the EDT on refresh and when HEAD changes, as Session Context reads them.

### MCP (`FoldersMcpToolset`)

`my_air_review_get`, `my_air_review_apply`, `my_air_review_report` (R14–R17), names and descriptions in `appendedSystemPrompt` (R34). `my_air_review_report` takes the run id the reviewer's prompt named and refuses one the review does not have.

### Reviewer start

`startReviewer(review, reviewer, range)`: the run `W<n>` is recorded first, then `claude --bg [--model] [--effort] --append-system-prompt <reviewerPrompt> -- <prompt>` in `dir`; the job id is the 8 hex characters of stdout's first line `backgrounded · <job>`, and the run's session the `sessionId` of the registry entry whose `jobId` it is, set on the next refresh, when the session is also filed in the review folder (`claude --bg` ignores `--session-id`, task 1). `reviewerPrompt` (pure): folder path, run id, branch, base, range, "review only this range", "call `my_air_review_report` once at the end with this run id and every finding and question; report nothing elsewhere".

### Plan mirror (R31)

`mirrorToPlan(review, plan): PlanChanges` (pure) yields `set_review` and `add_blocker`/`update` ops of the plan ideal-state spec; the mapping finding id → blocker id is kept in `review.json` as `blockers: {"F3": "B2"}`, written only when not empty. Applied through `updateFolders` after every review change whose blocker set or run status changed. Those ops are on `main` since `8a872ef` (`PlanChanges`: `set_review`, `add_blocker`, `update` on a `B` id). The review's model is named `BranchReview`, since `folders.Review` is the plan's.

### View (`reviewview/`)

`ReviewVirtualFile`, `ReviewFileSystem` (`myair-review://`), `ReviewFiles`, `ReviewEditor` (splitter of `ReviewPanel` and `SessionPane`), `ReviewOpener`, following `planview/` one for one; where `PlanFiles` can take a folder kind instead of being copied, the builder generalizes it. `ReviewPanel` cards, each folding: header (R10), Summary, Explanation, Reviewers, Findings, Questions. A review tab counts as its manager's tab wherever a plan tab does (`sessionFileOf`, `shownSessionFiles`, `ActiveSession`).

### Tree

A review folder row (`ReviewRow` or the folder row with a review icon, R33) keeps the folder's sessions (manager and reviewers) as its children; its menu has Open Review, Delete (the folder; data kept, R3) and Rename.

## Calls made while writing

- The review's data stays out of `structure.json` (Approach A), against the first sketch in chat, so this spec and the plan ideal-state spec do not both claim version 5.
- Reviewers start only from the panel's Run, not from the manager, so every run is recorded before its session reports and R17's check holds.
- A review created from a place inside a plan sits beside the plan folder, not in it, because a plan folder's sessions go to its tasks or `no task`.

## Left to the builder

Visuals within the composer's look; how much of `PlanFiles`, `PlanOpener`, `PlanTabs` is shared rather than mirrored; the PR number prompt's form; splitting the work into tasks.
