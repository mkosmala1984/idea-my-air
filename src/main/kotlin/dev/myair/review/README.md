# dev.myair.review

## Overview

Review folders: the application service that reads and writes `~/.my-air/reviews/<folderId>/review.json`, the review operations agents and the panel apply, reviewer prompts and starts, and the mirror of a review into its linked plan. The model and the files (`BranchReview`, `ReviewFiles`, `ReviewProfileStore`) are in `dev.myair.store`; the tab and New Review are in `dev.myair.reviewview`. `ReviewServiceStartup` (`plugin.xml`, `postStartupActivity`) creates the service, and `my-air-mcp.xml` names `dev.myair.mcp.ReviewMcpToolset`.

## Interface

- `ReviewService.kt`:
  - `@Service(APP) class ReviewService`, with `reviews`, `readable`, `onDiskIds`, `liveJobs`, `facts`, `review(folderId)`, `file(folderId)`, `subscribe(parent, listener)`, `watchGit(folderId, parent)`, `update(folderId, op)`, `moveManagers(switched)`, `create(folderId, review)` and `refresh()`.
  - `data class ReviewGit`, `class ReviewServiceStartup`.
  - Pure: `jobSessions(live)`, `managedReviewFolder(reviews, sessionId)`, `learnSessions(review, jobs)`, `onDisk(all)`, `inStructure(all, structure)`, `inReview(structure, reviews, id)`, `placementProblem(before, after, reviews)`, `refuseReviewPlacement(before, after, reviews)`, `linkedPlan(review, structure)`, `mirrorDue(review, structure, liveJobs)`, `readGit(review)`, `data class GitStamp`, `gitStamp(review, reviewFile)`, `readGits(reviews, reviewFile, lastStamps, lastFacts)`.
- `ReviewFacts.kt`: `enum RunStatus` (`running`, `waiting`, `stopped`, `reported`, `failed`, each with its panel `text`), `runStatus(run, liveJobs)`, `maybeFixed(finding, changed)`, `blockerCount(review)`, `toDecide(review)`, `reviewedCommit(review)`, `data class Precision`, `precision(reviews)`.
- `ReviewChanges.kt`: the `BranchReview` edits `setState`, `setSummary`, `setChapters`, `setPr`, `acceptIfOpen` (Fix with Claude), `addQuestion`, `resolveQuestion`, `addRun`, `setJob`, `setSession`, `removeRun`, and `object ReviewChanges` with `apply(review, changes)` (the JSON change list) and `report(review, run, commit, findings, questions, now)`.
- `Findings.kt`: `data class IncomingFinding`, `mergeFindings(review, run, commit, incoming)`.
- `ReviewJson.kt`: `reviewJson(structure, folderId, review, git, liveJobs)`, the text `my_air_review_get` returns: the folder and the linked plan as paths.
- `ReviewMirror.kt`: `data class Mirror(changes, blockers)`, `mirrorToPlan(review, plan, liveJobs)`.
- `ReviewPrompts.kt`: `reviewerPrompt`, `reviewManagerPrompt`, `reviewerArgs`, `parseJobId`, `rangeFor`.
- `Reviewers.kt`: `object Reviewers` with `start(dir, args)`, which runs `claude --bg` and returns the job id.

## Invariants

- `ReviewService` state (`reviews`, `facts`, the listeners) is touched on the EDT only; reads run on one background thread and only the latest lands. It is the only writer of `review.json`.
- `reviews` holds only folders of the structure; a `review.json` that cannot be read is a failed `Result`, never skipped.
- Git is read only for the reviews an open review tab watches (`watchGit`), again only when their `GitStamp` (HEAD, the ref it names, `packed-refs`, the index, `review.json`, the files the findings name) changed, and by `my_air_review_get`/`my_air_review_apply` on each call, off the EDT; `facts` and the stamps are kept in step. The tree and the plan mirror read no git. `readGit` counts changes against the review's `base` and is an error while the work tree has another branch than the review's checked out.
- A report's commit must be the end of its run's range (`ReviewChanges.report`), which the reviewer's prompt names.
- `claude --bg` ignores `--session-id`, so a run's session is learned from the registry by its job id (`learnSessions`) and filed in the review folder.
- The plan mirror is computed inside the folders write, against the structure read from disk there, and the review is written there before the structure, so the plan never holds a blocker the review does not map; a mirror that does not converge throws. `moveManagers` writes without the mirror, after the folders `carryOver` of a `/clear`.
- A review folder holds no plan and lies in no review or plan folder: `ClaudeSessionsService.updateFolders` runs `refuseReviewPlacement` on every structure write (MCP, drag and drop, Turn into Plan, New Review), over the folder ids of every `review.json` on disk; only placements new in the write count. The drop target and Turn into Plan are disabled where it would refuse.
- `parseJobId` throws on any first line other than `backgrounded · <8 hex>`.
