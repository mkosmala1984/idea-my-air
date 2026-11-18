# Review folder: one place for the review of a branch or pull request

## TL;DR

- **Goal:** a new kind of folder, Review, that opens in an editor tab like a plan: the branch's description and an explanation of its changes, the reviewers (skills and agents) run on it, their findings merged and triaged, questions for the author; its manager session beside it.
- **Approach:** the folder is an ordinary folder of `structure.json`; everything else lives in `~/.my-air/reviews/<folderId>/review.json`, changed by the plugin's view and by three new MCP tools. Reviewers are real `claude` sessions run as background jobs (`claude --bg`), filed in the review folder, each reporting through `my_air_review_report`.
- **Decided with you:** a review linked to a plan mirrors its blockers into the plan's `review` (spec `2026-11-18-plan-ideal-state-design.md`); reviewers are real sessions, not `claude -p`; Bitbucket only, through the manager's Atlassian MCP, never from the plugin.
- **Decided here, check it:** review data is not in `structure.json`, so the file's version does not move and does not collide with version 5 of the plan spec; a review folder and its data are joined by the folder id.
- **Needs your input:** which extras stay in scope: reviewer profiles (R18–R20), review of new commits only (R27–R29) and reviewer precision (R30) are in; editor gutter marks, PR comment replies, dashboard and taskbar are out. Say if you want any moved.
- **Risks:** R31 waits for the plan ideal-state spec to be built; whether `claude --bg` takes `--session-id`, `--append-system-prompt` and a first prompt must be checked first (task 1), else reviewers fall back to tabs, which the design does not cover.
- **Out of scope:** the plugin calling Bitbucket, reading PR comments back, gutter annotations, tokens per reviewer, a settings editor for profiles.

## Requirements

- **R1** A folder is a review folder exactly when `~/.my-air/reviews/<its id>/review.json` exists; a review folder holds no plan, and Add Plan is not offered on it.
- **R2** `review.json` has `format` 1 and the keys `branch`, `base`, `dir`, `pr` (number or null), `plan` (folder id or null), `manager` (`PlanManager` shape or null), `summary`, `chapters`, `runs`, `findings`, `questions`, `next`; any other key or format throws naming the file.
- **R3** A review's data is never deleted by the plugin; a `review.json` whose folder id is not in the structure is ignored, so a history restore of a deleted review folder brings the review back.
- **R4** New Review... is offered on a session row (branch checked out in its `workDir`), a worktree row (its branch) and a project row or a folder with a start directory (branch picked in a searchable popup of the repository's local branches); it asks for an optional PR number.
- **R5** New Review creates the folder `Review: <branch>` where the start point shows (`placeOf` for a session, the project's bound folder for a worktree row, the folder itself for a folder row; for a place inside a plan folder, the plan folder's parent) and writes `review.json` with `base` from `Git.baseOf` and `plan` set to that plan folder's id, else null.
- **R6** The review's `dir` is the existing linked worktree holding the branch, else the main checkout when it holds it, else a new worktree `<worktrees dir>/review-<branch>` checking the branch out (`Git.addWorktree`); a branch missing locally fails with git's message.
- **R7** A review folder opens in an editor tab (`myair-review://<folderId>`, restored with the open tabs, never in Recent Files) from Open Review in its menu and by opening its manager session from anywhere; one tab per review per IDE, focused rather than opened twice.
- **R8** The tab shows the review panel on the left and the manager's terminal on the right, the manager started, resumed and moved on `/clear` as a plan's manager is (`PlanFiles` lifecycle), stored in `review.json`'s `manager`, run in `dir`, filed in the review folder.
- **R9** The manager gets `--append-system-prompt-file` naming the review's branch, base, dir, PR and the rules: fill the summary and chapters with `my_air_review_apply`, start no reviewer itself, publish to the PR only when the user asks, change finding states only on the user's word.
- **R10** The header shows `branch → base`, the PR number when set (a link to `https://bitbucket.org/<workspace>/<repo>/pull-requests/<n>` built from `origin`'s URL; not a link for a non-Bitbucket origin), the reviewed commit (the newest commit of a completed run), HEAD of `dir` and how many commits HEAD is past it.
- **R11** Section Summary shows `summary` as Markdown; Edit Summary edits it in place; Publish sends the manager (`SessionPane.send`) a message asking it to set the PR's description to the summary, disabled without `pr`.
- **R12** Section Explanation lists `chapters` (title, Markdown text, files), each file with `+added −removed` from `Git.state` of `dir`; a click on a file opens its base diff (`showBaseDiff`); a chapter file not among the changed files shows grey with "not changed".
- **R13** Changed files that no chapter names are listed under a last chapter "Not explained".
- **R14** `my_air_review_apply(folder, changes)` takes a JSON array of ops, all-or-none: `set_summary {text}`, `set_chapters {chapters}`, `set_pr {pr}`, `set_state {id, state, reason, commit, comment}`, `add_question {text, reviewer}`, `resolve_question {id}`; an unknown op or field throws naming the change.
- **R15** `my_air_review_get(folder)` returns the review as JSON with each finding's derived `maybe_fixed` and each run's derived status.
- **R16** `my_air_review_report(folder, reviewer, commit, findings, questions)` records a run's report; each finding needs `file`, `line`, `category`, `severity` (`blocker`, `major`, `minor`, `nit`), `summary`, `failure_scenario`; a missing field or unknown severity is refused with nothing recorded.
- **R17** A second report of the same run replaces its first; a report for a session not started as a run of that review is refused.
- **R18** Reviewer profiles live in `~/.my-air/review-profiles.json`: profiles of a `name` and `reviewers`, each reviewer a `name`, a `prompt` (such as `/rtm-pr-review`), optional `model` and `effort`; unknown keys throw; a missing file means no profiles.
- **R19** Section Reviewers has a profile chooser and Run; with no profiles it says so and names the file; a broken file names its error there.
- **R20** Run starts one background job per reviewer of the profile (`claude --bg` in `dir` with the reviewer's model and effort, its prompt as the first message and an appended prompt naming folder, branch, base, range and the duty to call `my_air_review_report` once at the end) and records a run (`id`, reviewer, session, job, range, started).
- **R21** A run's status is derived: running while its job's entry is in the live registry and no report came, reported once a report came, failed when the job is gone without a report; the row shows status, finding count and duration, and a click opens the session (`ClaudeSessionOpener.open`, which attaches to the job).
- **R22** Reviewer sessions are filed in the review folder and show in the tree under it; the review panel lists them as runs, newest first, grouped by Run.
- **R23** Findings of all reports are merged: one finding per (file, category) with lines at most 3 apart, its reports listed with reviewer and commit; the merged finding's severity is the highest of its reports; ids `F<n>` are per review and never reused.
- **R24** A finding row shows severity, `file:line`, summary, how many distinct reviewers of the latest run of each reviewer reported it over how many reviewers ran (`3/4`), and its state; its tooltip the failure scenario and each report.
- **R25** Finding states: `open`, `accepted`, `rejected` (needs a reason), `fixed` (needs a commit), `posted` (needs a comment id); the row's menu sets accepted, rejected (asking the reason) and fixed (asking the commit); posted is set only through `set_state`.
- **R26** A finding's menu offers Fix with Claude (a new session in `dir`, filed in the review folder, its first message the finding, the finding set accepted), Post to PR (a message to the manager asking it to post the finding as a comment at file and line and set it posted with the comment id), and Open Diff (its file's base diff).
- **R27** When a reviewed commit exists and HEAD moved past it, Run offers New commits only (range `reviewed..HEAD`, the default) and Whole branch (`merge-base..HEAD`); the range goes into each reviewer's prompt.
- **R28** A finding is `maybe_fixed` when the diff from its newest report's commit to HEAD changes a line of its file within 3 lines of it; it shows dim with "maybe fixed", its state unchanged.
- **R29** Section Findings filters by All / Blockers / Open (open or accepted), and shows `<n> blockers · <m> to decide` in its head, blockers being blocker-severity findings open or accepted.
- **R30** Reviewer rows show the reviewer's precision over every review on disk: accepted, fixed and posted findings over those plus rejected ones, with the count of reviews (`7/10 · 5 reviews`); none before any finding of it was triaged.
- **R31** When `plan` names a plan folder that still has a plan, every change of the review sets that plan's `review` (status `in_progress` while a run runs, else `done` once a run reported, else `not_done`; `commit` the reviewed commit) and keeps one blocker per blocker-severity finding, unresolved while open or accepted, resolved otherwise, its text `F<n> file:line summary`; plan blockers not made by the review are left alone.
- **R32** Section Questions lists open questions (text, reviewer); Resolve marks one resolved; resolved ones fold away under a count.
- **R33** The tree's review folder row has a review icon and, on its right, its blocker count when any, in the needs-input colour.
- **R34** `appendedSystemPrompt` names the three review tools and what each is for; `SessionSystemPromptTest`'s check that every tool of `FoldersMcpToolset` is named passes.
- **R35** `CLAUDE.md` of this repo describes the review folder, its files, tools and tab.

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
data class Run(val id: String, val reviewer: String, val session: String, val job: String, val range: String, val started: String, val reported: String? = null)
data class Chapter(val title: String, val text: String, val files: List<String>)
data class Question(val id: String, val text: String, val reviewer: String?, val resolved: Boolean = false)
data class Review(val branch: String, val base: String, val dir: Path, val pr: Int?, val plan: String?, val manager: PlanManager?,
                  val summary: String?, val chapters: List<Chapter>, val runs: List<Run>, val findings: List<Finding>,
                  val questions: List<Question>, val next: Map<String, Int>)   // per prefix F/Q/W (run), next number
```

A finding's line and severity are its newest report's line and the highest report severity (R23); a report keeps the reviewer's own text. `mergeFindings(review, report)` is pure: each incoming finding joins an existing one of the same `file` and `category` whose newest line is at most 3 away (the nearest when several), else becomes `F<next>`. Reports of a run replaced by a second report of the same run are removed first (R17).

`~/.my-air/review-profiles.json` (`store/ReviewProfiles.kt`): `{"profiles": [{"name": "RTM PR", "reviewers": [{"name": "rtm-pr-review", "prompt": "/rtm-pr-review", "model": "opus", "effort": "high"}]}]}`. Read on every panel update, cached by modification time, as `PresetsGroup` reads `presets.json`.

### Service

`ReviewStore` (application service) reads every `review.json` on refresh and on file events of `~/.my-air/reviews/`, holds them by folder id, and notifies subscribers; writes go through it so the panel and the MCP tools see one state. Run status (R21) is derived from `ClaudeSessionsService`'s live entries by job id. Git facts of a review's `dir` (`Git.state`, HEAD, the reviewed commit's distance, range hunks for `maybeFixed`) are read off the EDT on refresh and when HEAD changes, as Session Context reads them.

### MCP (`FoldersMcpToolset`)

`my_air_review_get`, `my_air_review_apply`, `my_air_review_report` (R14–R17), names and descriptions in `appendedSystemPrompt` (R34). `my_air_review_report` checks the calling session against the review's runs.

### Reviewer start

`startReviewer(review, reviewer, range)`: `claude --bg --session-id <uuid> [--model] [--effort] --append-system-prompt <reviewerPrompt> -- <prompt>` in `dir`, the job id read from the registry entry of that session id. `reviewerPrompt` (pure): folder path, branch, base, range, "review only this range", "call `my_air_review_report` once at the end with every finding and question; report nothing elsewhere". The first build task checks these flags against `claude --bg`; if any is refused, the spec comes back before building on it.

### Plan mirror (R31)

`mirrorToPlan(review, plan): PlanChanges` (pure) yields `set_review` and `add_blocker`/`update` ops of the plan ideal-state spec; the mapping finding id → blocker id is kept in `review.json` as `blockers: {"F3": "B2"}`, written only when not empty. Applied through `updateFolders` after every review change whose blocker set or run status changed. Built only after that spec's ops exist.

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
