# Plan as an ideal state artifact Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `55c675c`..`1296572` |
| 2 | done | `b04711a` |
| 3 | done | `871cf90` |
| 4 | done | `f7dedc1` |
| 5 | done | `3cdcf8e` |
| 6 | | |
| 7 | | |

## TL;DR

- **Goal:** a My Air plan gains ideal state (the existing description), constraints, decisions, numbered requirements with eval kind and evidence, and a code review with blockers; agents change them through `my_air_plan_apply`, the plan view shows and edits them, sessions get them in `planPrompt`.
- **Approach:** 7 tasks: model and file format → ops → MCP JSON → prompt → view model and inline edits → plan panel cards and sandbox walk → docs (repo and global `CLAUDE.md`).
- **Needs your input:** task 7 shows you the new text of "Spec requirements and plan evidence" in `~/.claude/CLAUDE.md` before saving it.
- **Risks:** `PlanPanel.kt` (546 lines) grows; the cards go into a new file to keep it in hand. Once a plan uses a new section, an installed My Air older than this build cannot read `structure.json`.
- **Out of scope:** reading test reports, review staleness against HEAD, requirements in the tree or Session Context, gating Mark Done, the branch `worktree-spec-requirements-exec`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a My Air plan into an ideal state artifact: ideal state, constraints, decisions, requirements with evidence, code review.

**Architecture:** New typed fields on `Plan` in `structure.json` (version 5 only while used), lenient by-id edits in `SessionFolders` as for milestones and tasks, strict validation in `PlanChanges`, read by `McpJson.planMap`, `planPrompt`, `planViewOf` and a new `PlanSections.kt` in `planview/`.

**Tech Stack:** Kotlin, IntelliJ Platform 263 (Kotlin UI DSL, Swing), Gson, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-18-plan-ideal-state-design.md`

## Global Constraints

- English in code, comments, strings, test names.
- Anything malformed throws naming the change; no silent skip, trim or default (spec anti-goal).
- `structure.json` written as version 4 unless a plan holds a field new in 5; versions 1–5 read; unknown keys throw.
- Ids `R<n>`, `D<n>`, `B<n>` numbered per plan and prefix from 1, never reused, also across `restore`.
- Evidence item regex, exactly: `^(test \S+ › .+|commit [0-9a-f]{7,40}|checked: .+|not done: .+)$`, matched with `matches` (whole string, no newline), and the item must equal its `trim()`.
- Eval kinds `assert`, `code`, `questions`, `judge`; review statuses `not_done`, `in_progress`, `done`.
- Dates are ISO local dates (`2026-11-18`) set by the plugin from an injected `today: LocalDate`, never by the agent.
- Nothing gates closing a plan, a milestone or a task.
- Tests: `./gradlew test --tests <class>`; full run `./gradlew test buildPlugin` before the last task closes.

## Review Focus

1. A `structure.json` without any new field round-trips as version 4 byte-for-byte, so the installed My Air keeps reading it → test in Task 1.
2. Undo (`restore`) to a snapshot taken before `R3` was added, then adding a requirement, gives `R4`, not `R3` → test in Task 1.
3. Deleting a task (or a closed milestone with its tasks) drops its id from every requirement's `tasks`, not leaving a dangling id → test in Task 1.
4. An evidence item with a trailing space or a newline, or `commit ABC1234` in upper case, is refused, not trimmed → test in Task 2.
5. A plan with no requirements, constraints or decisions gives a prompt with no empty headings → test in Task 4.

---

### Task 1: Model, file format, by-id edits

**Files:**
- Modify: `src/main/kotlin/dev/myair/folders/Plan.kt`
- Modify: `src/main/kotlin/dev/myair/folders/SessionFolders.kt` (plan edits ~246–300, `restore`, `toJson`, `parse`/`plan`)
- Test: `src/test/kotlin/dev/myair/folders/PlanTest.kt`, `src/test/kotlin/dev/myair/folders/SessionFoldersTest.kt`

**Interfaces:**
- Produces (`Plan.kt`):
  - `enum class EvalKind { ASSERT, CODE, QUESTIONS, JUDGE }` with `@SerializedName` lowercase, `val label`, `companion fun of(label: String)` throwing like `PlanStatus.of`.
  - `enum class ReviewStatus { NOT_DONE, IN_PROGRESS, DONE }` serialized `not_done`/`in_progress`/`done`, same `label`/`of`.
  - `enum class RequirementStatus { UNPROVEN, NOT_DONE, PROVEN }`.
  - `data class Requirement(val id: String, val text: String, val eval: EvalKind, val tasks: List<String> = emptyList(), val evidence: List<String> = emptyList())` with `val status: RequirementStatus` (R8).
  - `data class Decision(val id: String, val date: String, val text: String, val session: String? = null)`.
  - `data class Blocker(val id: String, val text: String, val resolved: Boolean = false)`.
  - `data class Review(val status: ReviewStatus, val commit: String?, val date: String, val blockers: List<Blocker> = emptyList())`.
  - `Plan` gains `constraints: String? = null, decisions: List<Decision> = emptyList(), requirements: List<Requirement> = emptyList(), review: Review? = null, next: Map<String, Int> = emptyMap()`, and `val usesVersion5: Boolean` (any of them set or non-empty).
  - `fun requireEvidence(item: String)` throwing `IllegalArgumentException` naming the four formats.
- Produces (`SessionFolders`, each returning `this` when the folder or item is gone, as `editTask` does):
  - `setConstraints(folderId: String, constraints: String?)`
  - `addDecision(folderId: String, text: String, today: LocalDate, session: String? = null)`
  - `addRequirement(folderId: String, text: String, eval: EvalKind, tasks: List<String> = emptyList(), index: Int? = null)`; `require` every task id is a task of this plan.
  - `editRequirement(folderId: String, id: String, change: (Requirement) -> Requirement)`; same `require` on the result's tasks.
  - `editDecision(folderId: String, id: String, change: (Decision) -> Decision)`
  - `setReview(folderId: String, status: ReviewStatus, commit: String?, today: LocalDate)` (keeps blockers)
  - `addBlocker(folderId: String, text: String, today: LocalDate)` (creates `Review(NOT_DONE, null, today)` when none)
  - `editBlocker(folderId: String, id: String, change: (Blocker) -> Blocker)`
  - `deleteEntry(folderId: String, id: String)` for an `R`/`D`/`B` id.
  - private `Plan.allocate(prefix: Char): Pair<String, Plan>`: id `"$prefix${next[prefix] ?: 1}"`, `next` bumped.

- [x] **Step 1: Write the failing tests**

In `PlanTest`:
- `` `a requirement is unproven without evidence, not done with any not done item, else proven` ``: `Requirement("R1","x",EvalKind.CODE).status == UNPROVEN`; with `listOf("commit abc1234")` → `PROVEN`; with `listOf("commit abc1234", "not done: sandbox walk not run")` → `NOT_DONE`.
- `` `evidence must be one of the four formats exactly` ``: `requireEvidence` accepts `"test PlanFilesTest › a plan without a manager"`, `"commit 4c91271d91"`, `"checked: opened the plan"`, `"not done: no sandbox"`; throws for `"commit ABC1234"`, `"commit abc"`, `"checked:x"`, `"checked: x "`, `"test PlanFilesTest"`, `"commit abc1234\n"`, `"commit abc1234 "`.

In `SessionFoldersTest`:
- `` `requirements, decisions and blockers are numbered per plan and prefix, never reused after a delete` ``: in plan p add R1, R2, delete R2, add → `R3`; `addDecision` → `D1`; plan q's first requirement is `R1`.
- `` `restore keeps the higher next of each plan and prefix` ``: snapshot s before R1 exists; add R1, R2; `restore(s)` then `addRequirement` → `R3`.
- `` `deleting a task drops it from requirements` ``: R1 with tasks `[T2]`; `deletePlanItem("T2")` → R1.tasks empty; same for a closed milestone holding T2.
- `` `a requirement may name only tasks of its plan` ``: `addRequirement("p", "x", CODE, listOf("T4"))` (task of q) throws; `editRequirement` to a missing task throws.
- `` `a blocker on a plan with no review starts one not done` ``: `addBlocker("p","b",LocalDate.of(2026,11,18))` → `Review(NOT_DONE, null, "2026-11-18", [Blocker("B1","b")])`; `setReview(.., DONE, "abc1234", ..)` keeps the blocker.
- `` `a structure without new plan fields is written as version 4 and reads back equal` ``: `parse(s.toJson())` equals `s` and `toJson()` contains `"version": 4` and none of `constraints`, `decisions`, `requirements`, `review`, `next`.
- `` `a plan with new fields is written as version 5 and reads back equal` ``: one of each field set; round-trips; `"version": 5`.
- `` `version 5 rejects unknown plan keys` ``: a v5 file with `"plan":{"milestones":[],"extra":1}` throws naming `extra`.

- [x] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests dev.myair.folders.PlanTest --tests dev.myair.folders.SessionFoldersTest`
Expected: compilation fails on the missing types.

- [x] **Step 3: Implement the model in `Plan.kt` and the edits in `SessionFolders.kt`**

`VERSION` becomes 5; `parse` accepts `version in 1..5` and for 5 the root and folder key sets of 4; `plan()` reads the new keys only when `version == 5` (pass the version down) and uses `only` with them. `toJson` writes `"version"` as `if (all().any { it.plan?.usesVersion5 == true }) 5 else 4` and serializes `Plan` with a `JsonSerializer<Plan>` registered on the `GsonBuilder` that leaves out `decisions`, `requirements`, `next` when empty (Gson already leaves out nulls), so a version-4 file is unchanged. `restore` additionally merges, for every plan folder present in the snapshot, `next` with the current plan's by `maxOf` per key. `deletePlanItem` maps the plan's requirements' `tasks` minus the deleted task ids. Update the version comment.

- [x] **Step 4: Run them to verify they pass**

Run: `./gradlew test --tests 'dev.myair.folders.*'`
Expected: PASS, the existing folder tests included.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/myair/folders/Plan.kt src/main/kotlin/dev/myair/folders/SessionFolders.kt src/test/kotlin/dev/myair/folders/PlanTest.kt src/test/kotlin/dev/myair/folders/SessionFoldersTest.kt
git commit -m "Plan model: constraints, decisions, requirements with evidence, review with blockers; structure.json version 5 only while used"
```

Trailers: `Plan-Task: 1/7`, `Plan: docs/superpowers/plans/2026-11-18-plan-ideal-state.md`.

### Task 2: Ops of `my_air_plan_apply`

**Files:**
- Modify: `src/main/kotlin/dev/myair/folders/PlanChanges.kt`
- Modify: `src/main/kotlin/dev/myair/mcp/FoldersMcpToolset.kt:196-219` (call and description)
- Test: `src/test/kotlin/dev/myair/folders/PlanChangesTest.kt`

**Interfaces:**
- Consumes: Task 1's `SessionFolders` methods, `EvalKind.of`, `ReviewStatus.of`, `requireEvidence`.
- Produces: `PlanChanges.apply(structure: SessionFolders, folderId: String, changes: String, today: LocalDate): SessionFolders`.

New `FIELDS`: `set_constraints {constraints}`, `add_decision {text, session}`, `add_requirement {text, eval, tasks, index}`, `add_evidence {id, evidence}`, `set_review {status, commit}`, `add_blocker {text}`; `update` gains `text, eval, tasks, evidence, resolved`; `update`/`delete` dispatch on the id's first letter `M`/`T`/`R`/`D`/`B` and refuse fields not of that kind (R: `text, eval, tasks, evidence`; D: `text`; B: `text, resolved`; M/T as today). `set_review` without `commit` keeps the stored one, `"commit": null` clears it; a given commit must match `[0-9a-f]{7,40}`. An id of another plan or unknown throws `no requirement 'R9' in this plan` and likewise.

- [x] **Step 1: Write the failing tests** (`structure` and `apply` helper get `today = LocalDate.of(2026, 11, 18)`):
- `` `the new sections are set in order, ids allocated per plan` ``: one list with `set_constraints`, `add_requirement` (`"eval":"code","tasks":["T2"]`), `add_evidence` (`"id":"R1","evidence":"commit 4c91271d91"`), `add_decision` (`"session":"s1"`), `set_review` (`"status":"in_progress","commit":"abc1234"`), `add_blocker`, `update` `B1` `resolved:true` → the plan equals the expected `Plan` with `Decision("D1","2026-11-18",...)`, review date `2026-11-18`, `R1.status == PROVEN`.
- `` `update and delete take only the fields of the id's kind` ``: `{"op":"update","id":"D1","eval":"code"}` throws `unknown fields`; `{"op":"update","id":"B1","next":"x"}` throws.
- `` `malformed evidence, eval and review status are refused naming the change` ``: `"evidence":"commit ABC1234"`, `"evidence":"commit abc1234 "`, `"eval":"unit"`, `"status":"finished"` each throw with message starting `change 1 `.
- `` `ids of another plan are refused` ``: `update R1` of plan q from p throws `no requirement 'R1' in this plan`; `"tasks":["T4"]` throws.
- `` `set_review keeps the commit unless given, null clearing it` ``.
- Existing tests: pass `today`.

- [x] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests dev.myair.folders.PlanChangesTest` — Expected: FAIL (signature and unknown ops).

- [x] **Step 3: Implement the ops; `my_air_plan_apply` passes `LocalDate.now()` and its description names every new op with its fields, the eval kinds, review statuses and the evidence formats (R20)**

- [x] **Step 4: Run** `./gradlew test --tests dev.myair.folders.PlanChangesTest --tests dev.myair.editor.SessionSystemPromptTest` — Expected: PASS.

- [x] **Step 5: Commit** `Plan changes: ops for constraints, decisions, requirements, evidence, review and blockers` with trailers `Plan-Task: 2/7`.

### Task 3: `my_air_plan_get` returns the sections

**Files:**
- Modify: `src/main/kotlin/dev/myair/mcp/McpJson.kt:106-133`, `src/main/kotlin/dev/myair/mcp/FoldersMcpToolset.kt` (description of `my_air_plan_get`)
- Test: `src/test/kotlin/dev/myair/mcp/FolderQueriesTest.kt`

**Interfaces:** Consumes Task 1's model. `planMap` adds after `description`: `"constraints"`, `"decisions"` (`id, date, text, session`, in order added), `"requirements"` (`id, text, eval` label, `tasks`, `evidence`, `status` as `unproven`/`not_done`/`proven`), `"review"` (`status` label, `commit`, `date`, `blockers` with `id, text, resolved`) or null.

- [x] **Step 1: Write the failing test** `` `a plan's sections come out with derived requirement statuses` ``: build a plan with one of each, assert the JSON fields and `requirements[0].status == "proven"`; and `` `a plan without sections has null constraints and review and empty lists` ``.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.mcp.FolderQueriesTest` — Expected: FAIL.
- [x] **Step 3: Implement** in `planMap`; describe the fields in `my_air_plan_get`'s description (R13, R20).
- [x] **Step 4: Run** the same — Expected: PASS.
- [x] **Step 5: Commit** `my_air_plan_get returns constraints, decisions, requirements and review` with `Plan-Task: 3/7`.

### Task 4: Sessions are told the sections

**Files:**
- Modify: `src/main/kotlin/dev/myair/editor/SessionSystemPrompt.kt:63-86`
- Test: `src/test/kotlin/dev/myair/editor/SessionSystemPromptTest.kt`

**Interfaces:** Consumes Task 1's model; `planPrompt` signature unchanged.

Layout after the intro sentence: `## Ideal state` + description (when set), `## Constraints` + text (when set), `## Requirements` with one line per requirement `- **R1** (code) text` (task session: those whose `tasks` hold its task; manager: all; heading only when any), then task and milestone as today, then the manager section with these rules added: record a decision with `add_decision` whenever the ideal state, constraints or requirements change; add evidence with `add_evidence` as tasks close, in the four formats; after a code review set it with `set_review` and its blockers with `add_blocker`; delete requirements, decisions or blockers only after the user agreed.

- [x] **Step 1: Write the failing tests**:
- `` `a task session gets the ideal state, constraints and only the requirements of its task` ``: R1 tasks `[T2]`, R2 tasks `[T9]`; prompt for T2 contains `## Ideal state`, `## Constraints`, `- **R1** (code) ` and not `R2`.
- `` `a manager gets every requirement and the rules for decisions, evidence and review` ``: contains both R lines, `add_decision`, `add_evidence`, `set_review`, `add_blocker`.
- `` `a plan without sections has no empty headings` ``: plan with only a description: no `## Constraints`, no `## Requirements`.
- Update `` `the plan prompt carries names, the path and descriptions as written, but no status` `` for the `## Ideal state` heading.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.editor.SessionSystemPromptTest` — Expected: FAIL.
- [x] **Step 3: Implement** in `planPrompt`.
- [x] **Step 4: Run** the same — Expected: PASS.
- [x] **Step 5: Commit** `Plan prompt: ideal state, constraints, the task's requirements; manager keeps decisions, evidence and review` with `Plan-Task: 4/7`.

### Task 5: View model, folding of sections, inline edits

**Files:**
- Modify: `src/main/kotlin/dev/myair/planview/PlanView.kt`, `src/main/kotlin/dev/myair/planview/PlanEdit.kt`
- Test: `src/test/kotlin/dev/myair/planview/PlanViewTest.kt`, `src/test/kotlin/dev/myair/planview/PlanEditTest.kt`

**Interfaces:**
- Consumes: Task 1's model and `SessionFolders` methods.
- Produces:
  - `enum class Section(val id: String) { IDEAL("ideal"), CONSTRAINTS("constraints"), DECISIONS("decisions"), REQUIREMENTS("requirements"), REVIEW("review") }`.
  - `PlanView` gains `val plan: Plan` (the folder's, for the sections).
  - `Folding.open(section: Section, plan: Plan): Boolean` with R15's defaults, flipped by `toggle(section.id)`; `Folding.all` takes the plan and covers sections too.
  - `fun requirementsSummary(requirements: List<Requirement>): String?` → `"R 4/6"` (proven/all), null without any.
  - `fun reviewSummary(review: Review?): String` → `"review: not done"` for null or `NOT_DONE`, `"review: in progress"`, `"review: done"`, and `"review: 2 blockers"` whenever any blocker is unresolved (count of unresolved).
  - `PlanEdit` gains `data object Constraints`, `data object NewDecision`, `data object NewRequirement`, `data object NewBlocker`; `Title(id)` accepts `R`/`D`/`B` ids (their `text`); `kind` accepts `M,T,R,D,B`.
  - `fun PlanEdit.apply(folderId: String, text: String, today: LocalDate): ((SessionFolders) -> SessionFolders)?`: a new requirement gets `EvalKind.CODE`; blank text refused for titles and new items, empty clears `Constraints`.
  - `PlanEdit.current(plan)` covers the new edits.

- [x] **Step 1: Write the failing tests**:
- `PlanViewTest` › `` `requirements start open, review open while a blocker is unresolved, other sections folded when they hold anything` ``: plan with constraints, one decision, review with one unresolved blocker: `open(REQUIREMENTS)` true, `open(REVIEW)` true, `open(CONSTRAINTS)` false, `open(DECISIONS)` false, `open(IDEAL)` false with a description and true without; after resolving: `open(REVIEW)` false; `toggle("constraints")` opens it.
- `PlanViewTest` › `` `the header counts proven requirements and names the review` ``: `requirementsSummary` of 6 with 4 proven → `"R 4/6"`, empty → null; `reviewSummary(null) == "review: not done"`, two unresolved → `"review: 2 blockers"`, done with all resolved → `"review: done"`.
- `PlanEditTest` › `` `new decision, requirement and blocker edits add items, blank refused` ``: `NewDecision.apply("p","Why",today)` gives `D1` dated today; `NewRequirement` gives `R1` with `CODE`; `NewBlocker` gives `B1`; blank → null.
- `PlanEditTest` › `` `a title edit of a requirement, decision or blocker changes its text` `` and `` `an empty constraints edit clears them` ``.
- [x] **Step 2: Run** `./gradlew test --tests 'dev.myair.planview.*'` — Expected: FAIL.
- [x] **Step 3: Implement** in `PlanView.kt` and `PlanEdit.kt`; `planViewOf` fills `plan`.
- [x] **Step 4: Run** the same — Expected: PASS (`PlanPanel` compiles against the new `apply` signature by passing `LocalDate.now()`).
- [x] **Step 5: Commit** `Plan view model: sections, their folding, header summaries, edits of the new items` with `Plan-Task: 5/7`.

### Task 6: Plan panel cards and sandbox walk

**Files:**
- Create: `src/main/kotlin/dev/myair/planview/PlanSections.kt` (the five cards)
- Modify: `src/main/kotlin/dev/myair/planview/PlanPanel.kt` (header, render order, `rebuild`, menus)

**Interfaces:** Consumes Task 5's `Section`, `Folding.open(section, plan)`, summaries, edits; reuses `PlanPanel`'s `head`, `more`, `action`, `markdown`, `addRow`, `inline`, `SelectableTitle`, `CARD`.

Cards, between the header (with description moved into Ideal state) and the milestones, in the order Ideal state, Constraints, Decisions, Requirements, Code review (R14). Each a grey card with a chevron head and a `...` menu, folded per `Folding.open(section, plan)`, rebuilt only when its section changed, as milestone cards are:
- Ideal state: the Markdown description; menu Edit Ideal State (`PlanEdit.Description(null)`).
- Constraints: Markdown; menu Edit Constraints (`PlanEdit.Constraints`, the description area).
- Decisions: newest first: date (grey), id, text as Markdown; row menu Edit, Delete; `+ Add decision`.
- Requirements: row = status glyph `○` grey / `✗` error colour / `✓` success colour, id (grey), text (`SelectableTitle`), eval chip, task ids (grey); evidence lines under it, dim, read-only; row menu Edit, Eval (the four kinds as toggles), Delete; `+ Add requirement`.
- Code review: status (menu: the three statuses as toggles, `setReview(..., LocalDate.now())` keeping the commit), commit and date in grey; blocker rows `✗`/`✓`, id, text; row menu Resolve/Reopen, Edit, Delete; `+ Add blocker`.
Header beside the task progress: `requirementsSummary` (when not null) and `reviewSummary` (R16). Deletes from the view ask nothing (as task delete in the view today).

- [ ] **Step 1: Build** `./gradlew buildPlugin` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: Sandbox walk** (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`) on a test plan folder: add one item of every kind from the view; edit each; flip eval and review status; resolve a blocker; see the header change; fold and unfold each card; from the manager run `my_air_plan_apply` with `add_evidence` and see `R1` turn `✓`; Edit → Undo restores and the next requirement keeps a new id; then delete the new sections' items and check `~/.my-air/structure.json`'s `version` (5 while `next` is set).
- [ ] **Step 3: Commit** `Plan view: cards for ideal state, constraints, decisions, requirements and code review` with `Plan-Task: 6/7`.

### Task 7: Docs

**Files:**
- Modify: `CLAUDE.md` (plan folders paragraph and plan view paragraph), `src/main/kotlin/dev/myair/folders/README.md`, `src/main/kotlin/dev/myair/planview/README.md`
- Modify (outside the repo, after the user approves the text): `~/.claude/CLAUDE.md` section "Spec requirements and plan evidence"

- [ ] **Step 1: Update the repo docs**: the new `Plan` fields, version 5 written only while used, ids per plan, ops, derived status, sections in the prompt and the view.
- [ ] **Step 2: Draft the global section**: with a My Air plan folder, a spec names the plan's path (`**Plan:** <folder path>`) in place of `## Requirements`, requirements live only in the plan and evidence goes through `my_air_plan_apply` `add_evidence` in the same four formats; without one, the Markdown list and table stay as today; drop the `my_air_spec_check` paragraph. Show the diff to the user; save only on approval.
- [ ] **Step 3: Full run** `./gradlew test buildPlugin` — Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 4: Commit** the repo docs: `Document the plan's ideal state sections` with `Plan-Task: 7/7`.

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 1 | |
| R2 | 4, 6 | |
| R3 | 2 | |
| R4 | 2 | |
| R5 | 2 | |
| R6 | 1 | |
| R7 | 1, 2 | |
| R8 | 1 | |
| R9 | 2 | |
| R10 | 1, 2 | |
| R11 | 2 | |
| R12 | 2 | |
| R13 | 3 | |
| R14 | 6 | |
| R15 | 5 | |
| R16 | 5, 6 | |
| R17 | 5, 6 | |
| R18 | 4 | |
| R19 | 4 | |
| R20 | 2, 3 | |
| R21 | 7 | |
