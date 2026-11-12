# Requirements of a spec, checked at approval and at the end

## TL;DR

- **Goal:** a spec has one numbered list of checkable requirements; My Air shows it beside the spec when it is approved, and at the end shows for each requirement whether the plans that implement the spec prove it.
- **Approach:** conventions in the Markdown (`## Requirements` in the spec, `## Requirements evidence` in the plan, the plan's `**Spec:**` line naming the spec file), written into `~/.claude/CLAUDE.md`; a "Requirements" editor tab on spec files reads them, the git repository and JUnit XML reports.
- **Decided with you:** used at approval and at the end, not during execution; evidence is a table in the plan filled by the executing agent, a test's result read from the report; the Markdown is the only source, no copy in `structure.json`.
- **My calls to check:** reports of Gradle and Maven both read (rtm-cloud is Maven); approval is a line in the spec holding a hash of the list; the panel also flags a plan whose status table and checkboxes disagree.
- **Risks:** agents ignore the convention (then the panel shows errors, not silence); a report older than the code still counts as a pass, its age shown beside it.
- **Out of scope:** making the plan itself easier to read, plan folder statuses taken from the plan file, navigation from a test name to its source, B–E of the proposals (session stage, subagents, decision inbox, review comments, change evidence).

## Why

The user named three costs of the current superpowers process: plans are hard to read, spec, plan and statuses disagree, and there is no clear list of what must work. In `docs/superpowers` today, 1 of 10 specs has something like a list (the behaviour table of `2026-11-08-plan-view-design.md`); plans point at spec *sections*, never at requirements; `2026-11-08-plan-view.md` says 8/8 done in its status table while 0 of its 33 checkboxes are ticked, and "sandbox walk not run" sits in a table cell where nobody looks. The list is needed at two moments: when the user approves a spec (read the list, not the prose) and at the end (is everything there, with proof). 20 repositories under `E:/dev/projects` have `docs/superpowers`, so this is not specific to my-air.

## Goals, non-goals, anti-goals

- Goal: the user approves a spec by reading its requirements alone.
- Goal: at the end, every requirement shows proven, failed, unproven, not done or uncovered, and the reason is one click away.
- Non-goal: a view for the time between approval and end (task progress lives in the plan view and the status table).
- Non-goal: changing the superpowers skills; they live in the plugin cache and an update overwrites them.
- Anti-goal: a format error that silently drops a requirement or an evidence row. Every line the parser cannot read is shown as an error.
- Anti-goal: a second copy of requirements or statuses outside the repository.

## Approaches

| | Where requirements live | Where they are seen | Verdict |
|---|---|---|---|
| **A** | Markdown of the spec and plan | an editor tab on the spec file | **picked**: works before a plan folder exists, one source |
| B | Markdown | a section of the plan folder's plan view | needs a plan folder linked to files; can come later reading the same parser |
| C | `structure.json` through an MCP tool | plan view | a third copy beside spec and plan: the inconsistency the user complains about |

## Conventions (written into `~/.claude/CLAUDE.md`)

A new section beside "Plans and specs: human summary", so every agent in every repository follows it:

Spec, after the TL;DR:

```markdown
## Requirements

- **R1** Opening Plan on a plan with no manager starts one with `--session-id` and records it in `plan-managers.json`.
- **R2** `/clear` in the manager moves its entry to the new session id.
```

- One requirement per bullet, `**R<n>**` then one sentence a test or a manual check can decide. Markdown inline is allowed.
- Ids are unique in the file; a removed requirement's id is never reused. Order is free.
- A behaviour table may stay as prose; the list is what counts.

Plan, the existing `**Spec:**` line names the spec file in backticks (a repository-relative path) whenever there is one, and a section:

```markdown
## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 3 | test `PlanFilesTest` › `a plan without a manager starts one` |
| R2 | 4 | test `PlanManagerStoreTest` › `clear moves the entry`; commit `5175332` |
| R3 | 8 | checked: opened the plan in runIde, ran /clear, entry moved |
| R4 | 8 | not done: sandbox walk not run |
| R5 | 6 | |
```

- Written by the planner with Evidence empty; the executing agent fills Evidence when the task closes.
- Evidence items are separated by `;`, each one of: `test <Class> › <name>` (simple class name, the test's display name), `commit <sha>`, `checked: <what was done>`, `not done: <why>`. Empty means not yet.
- Every requirement of the spec appears in at least one plan; a spec may have several plans.

Approval, written by the panel's Approve button (or by hand) anywhere in the spec:

```markdown
**Approved:** 2026-11-12, requirements `3fa1c07e`
```

The hash is the first 8 hex digits of SHA-256 over the requirements sorted by id, each `id + " " + text` with whitespace collapsed, joined by `\n`.

## The Requirements tab

`SpecRequirementsEditorProvider` accepts a `.md` file whose path contains `/docs/superpowers/specs/` (a path test only, no content read in `accept`), with `FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR`: the spec keeps its Markdown editor and preview, and a "Requirements" tab sits beside them at the bottom of the editor.

```
┌ 2026-11-08-plan-view-design.md ──────────────────────────────────────┐
│ Requirements of plan-view                                            │
│ Approved 2026-11-12 ✓          [Approve]  [Refresh]                  │
│ ████████████░░░░  9 proven · 1 failed · 1 not done · 1 uncovered     │
│                                                                      │
│ ✓ R1  Opening Plan on a plan with no manager starts one...           │
│       test PlanFilesTest › a plan without a manager...  passed, 2 h  │
│ ✗ R2  /clear in the manager moves its entry...                       │
│       test PlanManagerStoreTest › clear moves...       failed, 2 h   │
│ ○ R4  Plan tab restored after restart...                             │
│       not done: sandbox walk not run          (plan-view.md, task 8) │
│ ! R9  Manager's directory gone shows a notice                        │
│       no evidence row in any plan                                    │
│                                                                      │
│ Plans: 2026-11-08-plan-view.md                                       │
│   ! Task 6: status table "done", checkboxes 0/7                      │
│ Errors                                                               │
│   ! plan-view.md:57 evidence names R14, which the spec does not have │
└──────────────────────────────────────────────────────────────────────┘
```

- **Approval line.** None: "Not approved". Its hash equals the current list's: "Approved <date>". Different: "Changed after approval", and, when the commit that added the approval line is found (`git log -S<hash> -- <spec>`), the requirements added, removed or changed since that commit's version of the file; when it is not committed, the line says the approved version is unavailable.
- **Approve** inserts or replaces the approval line through the `Document` (so it undoes), with today's date and the current hash. Disabled while the list is empty or has errors.
- **Requirement states**, the worst evidence item across all plans wins, in the order failed > not done > unproven > proven (my call: a red test is the one thing that says something is wrong, unfinished work says only that it is not there yet); uncovered applies only when no plan has a row:
  - *proven*: every item is a passed test, an existing commit or `checked:`;
  - *failed*: a test failed or errored in its latest report, or a commit does not exist in the repository;
  - *unproven*: a row exists but Evidence is empty, or a test is in no report;
  - *not done*: an item `not done:`;
  - *uncovered*: no plan has a row for it.
  Before any plan names the spec, the tab shows the list with no states (the approval moment).
- **Tests.** Reports are `TEST-*.xml` under every `build/test-results/` and `target/surefire-reports/` directory of the spec's git work tree, skipping `.git`, `node_modules` and the configured worktrees directory. A test matches a `testcase` whose `classname` is the class or ends with `.<Class>` and whose `name`, with a trailing `()` removed, equals the name. The newest `testsuite` `timestamp` among matches counts; its age is shown.
- **Commits** are checked with `git cat-file -e <sha>^{commit}` in the spec's repository (`Git`).
- **Plans** are the `.md` files of `../plans/` beside the spec whose `**Spec:**` line names this spec's repository-relative path.
- **Plan consistency**, per plan that has a `## Requirements evidence` section (so plans written before this convention are not flooded): a task whose status table row says `done` while its checkboxes are not all ticked, and an evidence row whose Task is not in the status table. The grammar is the one "Plan Execution Status" in `~/.claude/CLAUDE.md` already prescribes, read strictly (my call): a row is `| <n> | <status> | <commits> |` with `<n>` an integer and `<status>` one of empty, `in progress`, `done`, `blocked: <why>`; anything else in such a plan (`done (sandbox walk not run)`, `5 (final review fixes)`) is an error naming the line, since an unfinished check belongs in the evidence table as `not done:`. A task's checkboxes are the `- [ ]`/`- [x]` items, one or more per line, between its heading `### Task <n>` (or `## Task <n>`) and the next heading of the same or a higher level.
- **Errors**, with file and line, each clickable: a requirement bullet or evidence row that does not parse, a duplicate id, an evidence row naming an id the spec lacks, an evidence item of unknown kind, a plan without a `## Requirements evidence` section, a spec without `## Requirements`.
- A requirement's row opens the spec at its line in the text editor; an evidence row opens the plan at its line.
- Reading runs off the EDT: on the tab's first show, when the spec's or a plan's document changes (debounced), and on Refresh, which also refreshes the report directories in the VFS (Gradle and Maven write them outside the IDE). Only the latest read lands.
- Look: the composer's cards and chips (`prompt/Composer.kt`), `PaneFont`, Markdown inline through `markdownHtml`, the plan view's segmented progress bar.

## Code layout

- `specs/` package: `Requirements.kt` (pure: parse a spec's list and approval line, parse a plan's evidence table, status table and checkboxes, the hash, the diff of two lists), `TestReports.kt` (pure over XML text: index of results), `SpecCheck.kt` (pure: combine spec, plans, reports and existing commits into states and errors), `SpecRequirementsEditor.kt` (provider, editor, panel, IO). Tests for the three pure files.
- `plugin.xml`: the `fileEditorProvider`.

## Requirements

- **R1** A `.md` file under a `docs/superpowers/specs/` directory gets a "Requirements" editor tab beside its default Markdown editor; other files do not.
- **R2** The tab lists every `**R<n>**` bullet of the spec's `## Requirements` section with its text rendered as Markdown inline.
- **R3** A requirement bullet or evidence row that does not parse, a duplicate id, or an evidence row naming an unknown id appears under Errors with its file and line; it is never dropped silently.
- **R4** Approve writes `**Approved:** <today>, requirements <hash>` into the spec as an undoable edit, replacing an existing approval line; it is disabled while the list is empty or has errors.
- **R5** With an approval line whose hash differs from the current list, the tab says "Changed after approval" and lists the requirements added, removed or changed since the committed approved version, or says that version is not committed.
- **R6** Plans are the files in the sibling `plans/` directory whose `**Spec:**` line names the spec; with none, the tab shows the list without states.
- **R7** A requirement with a test item whose latest matching JUnit report entry passed, and no worse item, is shown proven with the report's age.
- **R8** A test item matching a failed or errored testcase, or a commit item naming no commit of the repository, makes the requirement failed.
- **R9** A test item found in no report, or an evidence row with empty Evidence, makes the requirement unproven.
- **R10** A `not done:` item makes the requirement not done and shows its reason and plan task.
- **R11** A requirement no plan's evidence table names is shown uncovered.
- **R12** Reports are read from both `build/test-results` and `target/surefire-reports` directories of the spec's work tree.
- **R13** A plan task whose status table says done while some of its checkboxes are unticked is listed under its plan.
- **R14** Clicking a requirement opens the spec at its line; clicking an evidence row opens the plan at its line.
- **R15** The tab re-reads after a change to the spec or a plan document and on Refresh, which also picks up reports written outside the IDE; no file is read on the EDT.
- **R16** `~/.claude/CLAUDE.md` describes both sections, the evidence kinds and the `**Spec:**` line, so agents in every repository write them, and its "Plan Execution Status" says a status cell holds only the four statuses, unfinished checks going to the evidence table as `not done:`.
- **R17** When evidence items of one requirement disagree, the state shown is the worst in the order failed > not done > unproven > proven.
- **R18** A plan without a `## Requirements evidence` section gets no status-table or checkbox checks; in a plan with one, a status-table row outside the grammar is an error with its line.

## Left to the builder

- The exact debounce and how a document change of a plan file is noticed (document listener on open plans vs VFS events).
- Whether the summary bar reuses the plan view's segment painter or a copy of its idea.
- The texts of states and errors, as long as each names the file and line.
