# Review: an exact base and range for reviewers

## TL;DR

- **Goal:** a reviewer and the review's manager always get one exact base and one non-empty range (`<merge base>..<head>`) for the review branch, the ones the panel shows; a base that leaves nothing to review is refused, not run.
- **Cause (seen 2026-11-20, review of `TR-11848/Requira-Requirement-Type-Consume`, rtm-cloud):** *Change Base...* lists `origin/TR-11852/Requira-RTM-Links` first, a branch stacked **on top of** the review branch (`ahead-behind:HEAD` = `2 0`); its merge base with HEAD is HEAD, so five reviewers ran on `2cda93c..2cda93c` and only asked questions. The real base, `TR-11851` (`0 1`), was third.
- **Approach:** the review's *Change Base...* leaves out refs that already contain HEAD; the review's git read (`readGit`, so `baseProblem`) refuses a base whose merge base is HEAD; Run refuses an empty range; `my_air_review_get` and the reviewer prompt name the base, merge base, head, range and its size; the manager prompt names the base and points to `my_air_review_get` for the range, and says the folder's base wins over `branch.NAME.myAirBase`. Session Context and Branch Diff are not changed (D15).
- **Decided (D14):** *Change Base...*, *Use it* and `set_base` write the accepted base to the folder **and** to `branch.NAME.myAirBase` (R43); a `myAirBase` changed elsewhere still shows as a mismatch with *Use it* (R41). The manager prompt carries no counts, they would go stale (D16).
- **Risk:** low; base candidates and a new refusal. A review whose stored base already contains HEAD shows the problem instead of an empty Changes list.
- **Out of scope:** reading the PR's target branch from Bitbucket/GitHub; rebasing a stacked branch; ranges other than `merge base..HEAD` and `last reviewed..HEAD`.
- **Note:** the plugin installed in IDEA predates `d59a010` (its `my_air_review_apply` has no `set_base`), so an agent cannot fix a base today at all.

**Plan:** my-air/plans/Węzeł Review brancha-PR – propozycja

## What happens today

```
     ●  TR-11851  52efa7e586   ahead-behind:HEAD = 0 1   (a real base)
     │
     ●  TR-11848  2cda93c933   HEAD of the review branch
     │
     ├──●  TR-11850             1 0   (stacked on the branch)
     └──●  TR-11852             2 0   (stacked; listed first)
```

- `Git.nearestBases` (`actions/Worktrees.kt`) sorts by the commits of HEAD a ref lacks (`behind`), then by its own (`ahead`). A ref stacked on the branch lacks none (`behind = 0`), so it sorts before every real base.
- `Git.state` takes `merge-base base HEAD`; for a stacked ref that is HEAD, so Changes is empty and no problem is reported.
- `ReviewPanel.run` builds `rangeFor(review, mergeBase, head, newOnly)` and starts the reviewers without checking that the range has a commit.
- `reviewerPrompt` names the branch, the base and the range only; nothing tells the reviewer how many commits and files to expect, so an empty range is found only by each reviewer separately.
- `startReview` takes the base once, from `Git.baseOf` (stored `myAirBase`, else the default branch). Setting `myAirBase` later does not change the folder; the manager's system prompt (`SessionSystemPrompt`) tells it to use `myAirBase`, the folder uses its own base: two answers to "what is the base".

## Behaviour after the change

| Case | Today | After |
|---|---|---|
| *Change Base...* list for a branch with stacked branches above it | stacked refs first | stacked refs left out; first is the ref with the fewest HEAD commits missing (`TR-11851`) |
| `set_base` / *Change Base...* / *Use it* to a ref containing HEAD | stored, Changes empty | refused: `Base <ref> already contains <branch>: nothing to review` |
| `set_base` / *Change Base...* / *Use it* to an accepted base | folder only | folder and `branch.NAME.myAirBase`; Session Context and Branch Diff read it again |
| Stored base already containing HEAD (old review.json) | empty Changes, runs on an empty range | `state.problem` with the message above; Run disabled with it |
| Run when `last reviewed == HEAD` (*New commits only*) | starts on an empty range | that action disabled: `No commits since <sha>` |
| `my_air_review_get` | `base` only | also `merge_base`, `range` (of a *Whole branch* run), `commits`, `files`, `stored_base` (`myAirBase` of the branch or null) |
| Reviewer prompt | `branch X against Y`, range | also merge base, head, `N commits, F files`, and: `if git diff <range> is empty, report one question and stop` |
| Manager prompt | `branch X against Y` | also: the range is `<merge base>..HEAD`, its current values in `my_air_review_get`; the folder's base wins over `myAirBase` |
| Folder base differs from `myAirBase` | not shown | header line: `myAirBase of <branch> is <ref>` with *Use it* (sets the folder's base through `setBase`) |

Not changed: how a run's range is stored, the report check (commit = end of range), the default base of a new review.

## Where

- `actions/Worktrees.kt` `nearestBases`: for a review only, drop lines whose `behind` is 0 (the ref has every commit of HEAD), besides the branch itself.
- `review/ReviewService.kt` `readGit`: `mergeBase == HEAD` → the problem `Base $base already contains $branch: nothing to review`. `baseProblem` returns it, so `set_base`, *Change Base...* and *Use it* refuse it (all three through one helper that checks, then writes `myAirBase`). `Git.state` is not changed.
- `review/ReviewPrompts.kt`: `rangeFor` asserts the range is non-empty (`require(start != head)`); `reviewerPrompt` and `reviewManagerPrompt` take the counts.
- `reviewview/ReviewPanel.kt`: Run actions disabled with the reason; header line for the `myAirBase` mismatch.
- `review/ReviewJson.kt`: the new fields.
- `review/ReviewPrompts.kt` `reviewManagerPrompt`: the folder's base wins over `myAirBase` (`SessionSystemPrompt` stays the same for every session).
- Release a build with `d59a010` (`set_base`) to the IDE.

## Requirements

In the plan: R36–R43, delivered by task T265 *12. Exact base and range for reviewers*.
