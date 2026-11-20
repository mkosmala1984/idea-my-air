# Review base and range Implementation Plan

## TL;DR

- **Goal:** a reviewer and the review's manager get one exact base and a non-empty range; a base that leaves nothing to review is refused.
- **Approach:** 6 tasks, pure code first with JUnit 5 (real `git` in temp dirs where git is the subject), then the panel and MCP glue, docs, a build for IDEA.
- **Decided with you:** base written to the folder and to `myAirBase` (D14, R43); rules R36/R37 for reviews only (D15); manager prompt without counts (D16).
- **Risks:** `ReviewGit` grows three fields, every constructor call changes (4 in tests); the panel's Run now builds the prompt off the EDT.
- **Out of scope:** Session Context and Branch Diff behaviour, PR target from Bitbucket, rebasing stacked branches. No commits until you ask.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

| Task | Status | Commit |
|---|---|---|
| 1 | done | |
| 2 | done | |
| 3 | done | |
| 4 | done | |
| 5 | done | |
| 6 | blocked: R42 needs the build installed in IDEA (ends this session) | |

**Goal:** exact base and non-empty range for review runs (T265 of plan *Węzeł Review brancha-PR – propozycja*).

**Architecture:** the review-only rules live in `review/` (`readGit`, `storeBase`, `runProblem`, prompts) and in a flag of the pure `nearestBases`; `Git.state` is untouched. One helper `storeBase` checks a base and writes `myAirBase`; the panel (Change Base..., Use it) and `my_air_review_apply` `set_base` use it before updating `review.json`.

**Tech Stack:** Kotlin, JUnit 5, real `git` (2.41+).

**Spec:** `docs/superpowers/specs/2026-11-20-review-base-and-range.md`

## Global Constraints

- No fallbacks: a missing fact is a problem shown, never a guessed value.
- Review panel rules: edits from menus, `ActionButton`s, partial rebuilds.
- Prompts are one line without `"`, `<`, `>` (Windows command line).
- Tests plain JUnit 5 on pure code; test names are backtick sentences.
- English in code; evidence through `my_air_plan_apply add_evidence`.

## Review Focus

- A stored base that is a short sha of HEAD or of a descendant: `readGit` compares full shas (`merge-base` and `rev-parse HEAD` both print 40 hex), so it is caught (Task 2 test uses a short sha).
- A reviewed commit reported abbreviated: `rangeFor` treats a prefix of HEAD as HEAD (Task 5 test).
- A base equal to HEAD's commit under another name (`0 0`): left out of the review list, refused as a base (Tasks 1, 2).
- `myAirBase` changed from Session Context while the review tab is open: `GitStamp` takes the time of the common dir's `config`, so the header line appears without a restart (Task 3 test).
- A path with a newline or non-ASCII letters in the range: `rangeSize` counts with `-z` (Task 3 test with a spaced, non-ASCII name).

---

### Task 1: Review base list without refs holding HEAD (R36)

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/Worktrees.kt` (`nearestBases`), `src/main/kotlin/dev/myair/actions/Git.kt` (`Git.nearestBases`)
- Modify callers: `reviewview/ReviewPanel.kt:235` (`keepContaining = false`), `toolwindow/SessionContextPanel.kt:602` (`true`)
- Test: `src/test/kotlin/dev/myair/actions/WorktreesTest.kt`, `GitTest.kt:420`

**Interfaces:**
- Produces: `fun nearestBases(refs: String, branch: String, keepContaining: Boolean): List<String>`; `Git.nearestBases(dir: Path, branch: String, keepContaining: Boolean)`.

- [x] Step 1: test `nearest bases for a review leave out refs that hold every commit of HEAD`: refs `refs/heads/TR-11852 2 0`, `refs/heads/TR-11850 1 0`, `refs/heads/TR-11851 0 1`, `refs/heads/same 0 0`, `refs/remotes/origin/main 0 5` → `keepContaining = false` gives `["TR-11851", "origin/main"]`, `true` keeps all five in today's order. Existing tests pass `true`.
- [x] Step 2: run `./gradlew test --tests dev.myair.actions.WorktreesTest`, expect compile failure.
- [x] Step 3: filter `behind > 0` (the field `fields[2]`) when `!keepContaining`, before sorting.
- [x] Step 4: run `WorktreesTest` and `GitTest`, PASS.

### Task 2: A base holding HEAD is a problem of the review (R37)

**Files:**
- Modify: `src/main/kotlin/dev/myair/review/ReviewService.kt` (`readGit`)
- Test: `src/test/kotlin/dev/myair/review/ReviewGitTest.kt`

**Interfaces:**
- Produces: `readGit(review).state.problem == "Base <base> already contains <branch>: nothing to review"`, `mergeBase` and `changes` null then; `baseProblem` returns it.

- [x] Step 1: test `a base holding every commit of HEAD leaves nothing to review`: in `repo()`, `git branch stacked` on feature plus a commit on it; `baseProblem(review(repo, base = "stacked"))` and `baseProblem(review(repo, base = "feature"))` and with a short sha of feature's HEAD all equal `Base <b> already contains feature: nothing to review`; `readGit(...).state!!.mergeBase` null. `Git.state(repo)` (no `against`) on `main` checked out with a fresh branch still has no problem (unchanged).
- [x] Step 2: run, FAIL.
- [x] Step 3: in `readGit`, after `head`, replace `state` by `state.copy(mergeBase = null, changes = null, problem = ...)` when `state.mergeBase == head`.
- [x] Step 4: run `dev.myair.review.*`, PASS.

### Task 3: Range, its size and the stored base in the git facts and `my_air_review_get` (R39)

**Files:**
- Modify: `actions/Git.kt` (`RangeSize`, `Git.rangeSize`), `review/ReviewService.kt` (`ReviewGit`, `readGit`, `gitStamp`), `review/ReviewJson.kt`
- Test: `GitTest.kt`, `ReviewGitTest.kt`, `ReviewJsonTest.kt`, `reviewview/ReviewViewTest.kt` (constructor)

**Interfaces:**
- Produces: `data class RangeSize(val commits: Int, val files: Int)`; `Git.rangeSize(root: Path, range: String): RangeSize` (`rev-list --count <range>`, `diff --name-only -z <range>`).
- `ReviewGit(state, head, sinceReviewed, changed, origin, storedBase: String?, range: String?, size: RangeSize?, error)`: `range` = `<mergeBase>..<head>` and `size` its size, both null while `state.problem` is set; `storedBase` = `Git.storedBase(root, review.branch)`, read also then.
- `GitStamp` gains `config: Long?` (time of `<common dir>/config`).
- JSON keys: `merge_base`, `range`, `commits`, `files`, `stored_base`.

- [x] Step 1: tests: `GitTest` › `rangeSize counts commits and files of a range` (2 commits touching `a b.txt` and `zażółć.txt` → `RangeSize(2, 2)`; `x..x` → `RangeSize(0, 0)`); `ReviewGitTest` › `the git facts carry the whole branch range, its size and the stored base` (`range == "<main sha>..<head>"`, `size == RangeSize(1, 1)`, `storedBase` null, then `Git.setBase(repo, "feature", "other")` → `"other"`; with base `stacked` range and size null, storedBase still read); `ReviewGitTest` › `the stamp changes when the repository config changes`; `ReviewJsonTest` › `review json carries merge base, range, its size and the stored base` (and null without facts).
- [x] Step 2: skipped: tests and code were written together; the tests did not compile before the code.
- [x] Step 3: implement as in Interfaces; update the four test constructor calls.
- [x] Step 4: run `dev.myair.actions.*`, `dev.myair.review.*`, `dev.myair.reviewview.*`, PASS.

### Task 4: One way to set a base: check, `myAirBase`, folder; mismatch line (R37, R41, R43)

**Files:**
- Modify: `review/ReviewService.kt` (`storeBase`), `mcp/ReviewMcpToolset.kt` (`set_base` path), `reviewview/ReviewPanel.kt` (`pickBase`, header Use it), `reviewview/ReviewView.kt` (`Header.storedBase`, `baseMismatch`)
- Test: `ReviewGitTest.kt`, `ReviewViewTest.kt`

**Interfaces:**
- Produces: `fun storeBase(review: BranchReview, base: String): String?` (off the EDT): `baseProblem(review.copy(base = base))`, else `Git.setBase(review.dir, review.branch, base)` and null.
- `Header` gains `storedBase: String?`; `fun baseMismatch(h: Header): String?` = `"myAirBase of <branch> is <ref>"` when `storedBase` is set and differs from `base`.
- Panel: `private fun changeBase(base: String)` runs `storeBase` in `background`, then on the EDT `reviews.update { it.setBase(base) }` and `ActiveSession.getInstance(p).baseChanged()` for every open project; a refusal shows `Messages.showErrorDialog` with the reason. Change Base... and Use it call it. MCP: `storeBase(after.copy(base))` replaces the `baseProblem` call, then the same `baseChanged` on the EDT.

- [x] Step 1: tests: `ReviewGitTest` › `storing an accepted base writes myAirBase, a refused one writes nothing` (`storeBase(review(repo), "other")` null and `Git.storedBase(repo, "feature") == "other"`; `storeBase(review(repo), "stacked")` returns the R37 text, stored base still `other`); `ReviewViewTest` › `the header tells a myAirBase other than the review's base` (`storedBase = "x"`, base `main` → text; equal or null → null).
- [x] Step 2: run, FAIL.
- [x] Step 3: implement; header row shows the mismatch comment and a `Use it` link calling `changeBase(storedBase)`.
- [x] Step 4: run `dev.myair.review.*`, `dev.myair.reviewview.*`, PASS; `get_file_problems` on `ReviewPanel.kt`, `ReviewMcpToolset.kt`.

### Task 5: Run refuses an empty range; prompts name base, merge base, head, range, size (R38, R40)

**Files:**
- Modify: `review/ReviewPrompts.kt` (`rangeFor`, `runProblem`, `reviewerPrompt`, `reviewManagerPrompt`), `reviewview/ReviewPanel.kt` (`reviewersTrail`, `run`), `planview/PanelParts.kt` (an `action` taking a reason)
- Test: `ReviewPromptsTest.kt`

**Interfaces:**
- `rangeFor(...)` throws `IllegalArgumentException("empty range: <start>..<head>")` when `head.startsWith(start)`.
- `fun runProblem(git: ReviewGit?, reviewed: String?, newOnly: Boolean): String?`: `"Git not read yet"` without facts, else `git.error`, else `git.state?.problem`, else for `newOnly` with `sinceReviewed == 0` `"No commits since <reviewed sha7>"`, else null.
- `internal fun action(text: String, problem: () -> String?, run: () -> Unit): AnAction`: disabled with the problem as description while it is not null.
- `reviewerPrompt(folderId, run, review, mergeBase: String, head: String, range: String, size: RangeSize)`: names `against <base> (merge base <mb>, head <head>)`, `<range> (<n> commits, <f> files)` and `If git diff <range> is empty, call my_air_review_report with no findings and one question saying so, and stop.`
- `reviewManagerPrompt`: adds `The review's base is the folder's base, <base>; it wins over branch.<branch>.myAirBase. Its range is <merge base>..HEAD; my_air_review_get gives the current merge_base, range, commits and files.`
- Panel `run`: records runs on the EDT as today; per reviewer, off the EDT, `Git.rangeSize(review.dir, range)`, then the prompt and `Reviewers.start`.

- [x] Step 1: tests in `ReviewPromptsTest`: `a range that starts at HEAD is refused` (`rangeFor(review(), "h1", "h1", false)` and reviewed `abc1234` with head `abc1234ff..` and `newOnly`); `run problem names why a run cannot start` (null facts, error, state problem, newOnly with 0 since reviewed → `No commits since abc1234`, ready → null); update `the reviewer prompt names ...` to the new signature asserting `merge base mb`, `head def`, `3 commits, 2 files`, `git diff abc..def is empty`; manager prompt asserts `wins over branch.feat.myAirBase` and `my_air_review_get gives the current merge_base`.
- [x] Step 2: run, FAIL.
- [x] Step 3: implement; Run actions use `action(text, { runProblem(...) })`.
- [x] Step 4: run `dev.myair.review.*`, PASS; `get_file_problems` on touched files.

### Task 6: Docs, full build, install (R42)

- [x] Step 1: update `actions/README.md` (`nearestBases` flag), `review/README.md` (`storeBase`, `runProblem`, `RangeSize` facts, JSON keys), `CLAUDE.md` (review folder paragraph: Change Base list, `myAirBase` write, Use it, Run refusal, JSON keys, prompts), the `my_air_review_apply` description of `set_base` (writes `myAirBase`).
- [x] Step 2: `./gradlew test buildPlugin` → BUILD SUCCESSFUL, all tests pass.
- [ ] Step 3: set T265 `waiting`: the user installs with Tools › Install My Air Build and Restart (it ends this session); evidence R42: `my_air_review_apply`'s description lists `set_base`; R41: checked in the installed IDE.
- [x] Step 4 (partial: R41, R42, R43 and R38 wait for the IDE walk): evidence for R36–R40, R43 with `add_evidence` (`test <Class> › <name>`).

## Requirements evidence

Recorded in the plan folder (R36–R43) through `add_evidence`.
