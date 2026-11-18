# Plan as an ideal state artifact: constraints, decisions, requirements, code review

## TL;DR

- **Goal:** a My Air plan becomes one document that runs from prose down to checkable criteria (after "Anatomy of an Ideal State Artifact"): ideal state, constraints, decisions, numbered requirements each with its kind of evaluation and its evidence, a code review with blockers; milestones and tasks stay below.
- **Approach:** typed fields of `Plan` in `structure.json` (version 5), changed through new ops of `my_air_plan_apply` and from the plan view, shown as cards above the milestones, given to sessions in `planPrompt`.
- **Decided with you:** the plan is the only source of requirements, a spec names the plan instead of listing them; decisions are a record of their own kept by the manager; code review holds its status, whether it was done and its blockers; nothing blocks closing a plan, open requirements and blockers are only shown; the unmerged branch `worktree-spec-requirements-exec` (`my_air_spec_check`, Markdown requirements) is dropped, evidence is text and test reports are not read.
- **Needs your input:** the last task rewrites the section "Spec requirements and plan evidence" of `~/.claude/CLAUDE.md` (with a My Air plan folder the spec names the plan and evidence goes through `my_air_plan_apply`; without one the Markdown convention stays; `my_air_spec_check` removed); you check that text before it is saved.
- **Risks:** an installed My Air older than this one cannot read `structure.json` once version 5 is written (as with version 4); agents leaving evidence empty shows as ○ everywhere, not as an error.
- **Out of scope:** reading test reports, telling a review stale against HEAD, Session Context and tree showing requirements, gating Mark Done.

## Requirements

- **R1** `structure.json` is written as version 5 while a plan holds a field new in it, else as version 4; versions 1–5 read, a plan of them reading with no constraints, decisions, requirements or review.
- **R2** A plan's `description` is shown as "Ideal state" in the plan view and under that heading in `planPrompt`; its JSON key stays `description`.
- **R3** `my_air_plan_apply` op `set_constraints` sets the plan's constraints (Markdown) or clears them with null.
- **R4** Op `add_decision` appends a decision `D<n>` with `text` (Markdown, required), optional `session`, and the date of the day it was applied, set by the plugin.
- **R5** Op `add_requirement` adds `R<n>` with `text`, `eval` (one of `assert`, `code`, `questions`, `judge`), optional `tasks` (task ids of this plan) and `index`.
- **R6** Ids `R<n>`, `D<n>` and `B<n>` are numbered per plan and per prefix, starting at 1, never reused after a delete.
- **R7** Op `add_evidence` appends one evidence item to a requirement; an item not matching `test <Class> › <name>`, `commit <7–40 hex>`, `checked: <text>` or `not done: <text>` is refused naming the formats.
- **R8** A requirement's status is derived: no evidence → unproven (○), any `not done:` item → not done (✗), else proven (✓).
- **R9** Op `update` on an `R` id changes `text`, `eval`, `tasks` or replaces `evidence` as a whole; on a `D` id its `text`; on a `B` id its `text` or `resolved`.
- **R10** Op `delete` removes an `R`, `D` or `B` item; a task id unknown in this plan in `tasks` is refused; deleting a task removes its id from every requirement's `tasks`.
- **R11** Op `set_review` sets the review's `status` (`not_done`, `in_progress`, `done`) and optional `commit`; the date of the change is set by the plugin.
- **R12** Op `add_blocker` adds `B<n>` with `text`, unresolved.
- **R13** `my_air_plan_get` returns `constraints`, `decisions` (id, date, text, session), `requirements` (id, text, eval, tasks, evidence, derived status) and `review` (status, commit, date, blockers with id, text, resolved); absent sections come out as null or an empty list.
- **R14** The plan view shows, between the plan's header and its milestones, cards Ideal state, Constraints, Decisions (newest first), Requirements and Code review, each folding by a click on its head.
- **R15** By default Requirements is open; Code review is open while it has an unresolved blocker; Ideal state, Constraints and Decisions are folded when they hold anything and open when empty, so their add row shows.
- **R16** The plan view's header shows proven over all requirements (`R 4/6`) and the review (`review: not done`, `review: done`, `review: 2 blockers` while any is unresolved) beside task progress.
- **R17** From the plan view one can: edit the ideal state and constraints (Edit Description's area), add a decision, add, edit text of and delete a requirement and pick its eval kind, set the review status, add, resolve or reopen, edit and delete a blocker; evidence is read-only in the view.
- **R18** `planPrompt` of a task session lists, after the ideal state, the constraints and the requirements whose `tasks` hold its task, each with id, eval kind and text.
- **R19** `planPrompt` of a manager lists all requirements and tells it: record a decision with `add_decision` whenever the ideal state, constraints or requirements change; add evidence as tasks close; set the review and its blockers after a code review; delete requirements, decisions or blockers only after the user agreed.
- **R20** `my_air_plan_apply`'s description names every new op and its fields; `SessionSystemPromptTest`'s check that every tool is named still passes.
- **R21** `~/.claude/CLAUDE.md`'s section "Spec requirements and plan evidence" says: when the spec has a My Air plan folder, its requirements live only in that plan, the spec names the plan's path and evidence goes through `my_air_plan_apply`; without one, the Markdown `## Requirements` list and "Requirements evidence" table stay as today; the `my_air_spec_check` paragraph is gone; `CLAUDE.md` of this repo describes the new plan fields.

## Intent and why

The user wants a plan that says what the finished thing is and how to tell it is finished, not only a task list: the diagram's ideal state at the top as the most important part, what will not be built, how the ideal state changed while building, and atomic criteria that are both what is built from and the test harness. A code review is the last gate the user cares about: was it done, and is anything blocking.

Today `Plan` (`folders/Plan.kt`) has a Markdown `description` and milestones with tasks. Requirements live in the Markdown of specs (`## Requirements`) and evidence in a table of the Markdown plan, a convention of `~/.claude/CLAUDE.md`; a branch that checked them (`my_air_spec_check`) was never merged and is dropped by this design.

## Goals, non-goals, anti-goals

- Goal: one place, the plan folder, answers "what are we building, what not, what changed, what must hold and is it proven, was it reviewed".
- Goal: sessions of a task are told the criteria that their task serves.
- Non-goal: reading JUnit reports or running checks; evidence is what agents write.
- Non-goal: blocking Mark Done or `update` to done on open requirements or blockers.
- Anti-goal: a second list of requirements in the spec.
- Anti-goal: a status set by hand that can disagree with its evidence.
- Anti-goal: a malformed op or evidence item silently accepted; every one throws naming the change, as `PlanChanges` does now.

## Constraints

- `PlanChanges` stays all-or-none and strict about unknown fields; new ops follow its shape.
- Plan view rules stay: edits only from menus, `PlanEdit.apply` by id against the structure as it is then, fold rebuilds only the cards it changes, `ActionButton`s rather than toolbars.
- Tests stay plain JUnit 5 on pure code (`SessionFolders`, `PlanChanges`, `planViewOf`, `planPrompt`, `planJson`).

## Approaches

| | Where | Verdict |
|---|---|---|
| **A** | typed fields of `Plan` in `structure.json` | **picked**: derived status and header counts from types, undo through the existing history, one write path |
| B | Markdown with fixed headings in the description, parsed | a renamed heading drops data silently |
| C | a separate file per plan | a second write path and no history/undo |

## Design

### Model (`folders/Plan.kt`)

```kotlin
enum class EvalKind { assert, code, questions, judge }            // serialized lowercase
data class Requirement(val id: String, val text: String, val eval: EvalKind, val tasks: List<String> = emptyList(), val evidence: List<String> = emptyList())
data class Decision(val id: String, val date: String /* ISO local date */, val text: String, val session: String? = null)
enum class ReviewStatus { not_done, in_progress, done }
data class Blocker(val id: String, val text: String, val resolved: Boolean = false)
data class Review(val status: ReviewStatus, val commit: String?, val date: String, val blockers: List<Blocker> = emptyList())
data class Plan(
    val description: String? = null, val milestones: List<Milestone> = emptyList(),
    val constraints: String? = null, val decisions: List<Decision> = emptyList(),
    val requirements: List<Requirement> = emptyList(), val review: Review? = null,
    val next: Map<String, Int> = emptyMap(),                       // per prefix R/D/B, next number
)
```

`requirementStatus(r)` is a pure function (R8). An `add_blocker` with no review yet creates one with `not_done`. `next` is written only when not empty; the plan reader of version 5 accepts the new keys, 1–4 keep their key sets (R1). Today's date for R4/R11 comes from a `Clock`/`LocalDate` passed into `PlanChanges.apply` and the view's edits, so tests fix it.

### Ops (`PlanChanges`)

New entries of `FIELDS`: `set_constraints {constraints}`, `add_decision {text, session}`, `add_requirement {text, eval, tasks, index}`, `add_evidence {id, evidence}`, `set_review {status, commit}`, `add_blocker {text}`. `update` and `delete` dispatch on the id's prefix (`M`, `T`, `R`, `D`, `B`), refusing fields that do not apply to that kind. Evidence regex: `^(test \S+ › .+|commit [0-9a-f]{7,40}|checked: .+|not done: .+)$`.

### MCP (`McpJson.planMap`, `FoldersMcpToolset`)

`planMap` adds the sections of R13; `my_air_plan_get`'s and `my_air_plan_apply`'s descriptions name them and the ops (R20).

### Prompt (`SessionSystemPrompt.planPrompt`)

Heading "Ideal state" over the description; "Constraints"; "Requirements" filtered by task (R18) or all for the manager (R19); the manager paragraph gains the rules of R19.

### View (`planview/`)

`PlanView` gains the sections; `PlanPanel` adds the five cards between header and milestones, built like milestone cards (head with chevron and `...` menu, rebuilt only when changed). `Folding` gets the section ids `ideal`, `constraints`, `decisions`, `requirements`, `review` with R15's defaults. New `PlanEdit`s: `Constraints`, `NewDecision`, `NewRequirement`, `RequirementText(id)`, `DecisionText(id)`, `NewBlocker`, `BlockerText(id)`; eval kind, review status and blocker resolution are menu toggles. A requirement row: status glyph (○ ✗ ✓ in the theme's grey, error and success colours), id, text, eval chip, task ids; under it its evidence, dim. A blocker row: ✗/✓, id, text.

## Calls made after the builder check

- `SessionFolders.restore` keeps, per plan and prefix, the higher `next` of the snapshot and the current structure, as it does for `nextId`, so an undo never hands out an `R`/`D`/`B` id twice (part of R6).
- The file is written as version 5 only while some plan holds a field new in 5 (`constraints`, `decisions`, `requirements`, `review`, `next`), else as version 4, the way `workdir` is written only when set; the installed My Air keeps reading the file until a plan uses the new sections (narrows R1).
- The branch `worktree-spec-requirements-exec` and its worktree are left as they are: not merged, not deleted; existing specs with `## Requirements` and plans with evidence tables stay as history, not moved.

## Left to the builder

Exact visuals within the composer's look; where `today` is injected; splitting the work into tasks.
