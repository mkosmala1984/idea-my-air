# dev.myair.review

## Overview

Review folders: the application service that reads and writes `~/.my-air/reviews/<folderId>/review.json`, the review operations agents and the panel apply, reviewer prompts and starts, and the mirror of a review into its linked plan. The model and the files (`BranchReview`, `ReviewFiles`, `ReviewProfileStore`) are in `dev.myair.store`; the tab and New Review are in `dev.myair.reviewview`. `ReviewServiceStartup` (`plugin.xml`, `postStartupActivity`) creates the service, and `my-air-mcp.xml` names `dev.myair.mcp.ReviewMcpToolset`.

## Interface

- `ReviewService.kt`:
  - `@Service(APP) class ReviewService`, with `reviews`, `readable`, `liveJobs`, `facts`, `review(folderId)`, `file(folderId)`, `subscribe(parent, listener)`, `update(folderId, op)`, `moveManagers(switched)`, `create(folderId, review)` and `refresh()`.
  - `data class ReviewGit`, `class ReviewServiceStartup`.
  - Pure: `jobSessions(live)`, `managedReviewFolder(reviews, sessionId)`, `learnSessions(review, jobs)`, `onDisk(all)`, `inStructure(all, structure)`, `inReview(structure, reviews, id)`, `refusePlansInReviews(before, after, reviews)`, `linkedPlan(review, structure)`, `mirrorDue(review, structure, liveJobs)`, `readGit(review)`.
- `ReviewFacts.kt`: `enum RunStatus` (`running`, `reported`, `failed`), `runStatus(run, liveJobs)`, `maybeFixed(finding, changed)`, `blockerCount(review)`, `toDecide(review)`, `reviewedCommit(review)`, `data class Precision`, `precision(reviews)`.
- `ReviewChanges.kt`: the `BranchReview` edits `setState`, `setSummary`, `setChapters`, `setPr`, `addQuestion`, `resolveQuestion`, `addRun`, `setJob`, `setSession`, `removeRun`, and `object ReviewChanges` with `apply(review, changes)` (the JSON change list) and `report(review, run, commit, findings, questions, now)`.
- `Findings.kt`: `data class IncomingFinding`, `mergeFindings(review, run, commit, incoming)`.
- `ReviewJson.kt`: `reviewJson(path, review, git, liveJobs)`, the text `my_air_review_get` returns.
- `ReviewMirror.kt`: `data class Mirror(changes, blockers)`, `mirrorToPlan(review, plan, liveJobs)`.
- `ReviewPrompts.kt`: `reviewerPrompt`, `reviewManagerPrompt`, `reviewerArgs`, `parseJobId`, `rangeFor`.
- `Reviewers.kt`: `object Reviewers` with `start(dir, args)`, which runs `claude --bg` and returns the job id.

## Invariants

- `ReviewService` state (`reviews`, `facts`, the listeners) is touched on the EDT only; reads run on one background thread and only the latest lands. It is the only writer of `review.json`.
- `reviews` holds only folders of the structure; a `review.json` that cannot be read is a failed `Result`, never skipped.
- `claude --bg` ignores `--session-id`, so a run's session is learned from the registry by its job id (`learnSessions`) and filed in the review folder.
- The review is written before the plan mirror, so the plan never holds a blocker the review does not map; a mirror that does not converge throws.
- A review folder holds no plan (`refusePlansInReviews`).
- `parseJobId` throws on any first line other than `backgrounded · <8 hex>`.
