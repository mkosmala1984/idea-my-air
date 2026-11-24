# dev.myair.reviewview

## Overview

The review tab and New Review. A review folder opens as an editor tab, the review on the left (`ReviewPanel`) and the terminal of its manager session on the right, on the plan tab's shared `FolderTabs` and `ManagerTabEditor` (`dev.myair.planview`). The tab's file is a `ReviewVirtualFile` under the `myair-review://<folderId>` file system. The model and `review.json` are in `dev.myair.store`; the service, operations and prompts are in `dev.myair.review`.

## Interface

- `ReviewFiles.kt`: `class ReviewVirtualFile(folderId)`, `class ReviewFileSystem` (`PROTOCOL = "myair-review"`), `reviewManagerStart(review, newId, mainCheckout, hasTranscript)` and `@Service(APP) class ReviewTabs` with `tabs`, `startManager(folderId)`, `profileToCheck(folderId)` (the stored manager's profile, `default` for none; null while its process runs or for `default`), `reviewOf(file)` and `managerPrompt(file)`.
- `ReviewEditor.kt`: `class ReviewEditorProvider` and `class ReviewEditor`.
- `ReviewOpener.kt`: `object ReviewOpener` with `open(project, folderId)`.
- `ReviewPanel.kt`: `class ReviewPanel(project, folderId, parent, pane, managerRuns)` with `show(result)`.
- `ReviewView.kt`: `enum FindingFilter`, `Finding.standing` (open or accepted), `data class Header`, `ChapterFile`, `ChapterView`, `RunView`, `FindingView`, `ReviewView`, `bitbucketPrUrl(originUrl, pr)`, `reviewViewOf(...)` and `findingMessage(finding)`.
- `ReviewEdits.kt`: `sealed interface ReviewEdit`, `ReviewEdit.current(review)` and `ReviewEdit.apply(text)`.
- `ReviewTabsProviders.kt`: `class ReviewTabTitleProvider` and `class ReviewFileIconProvider`.
- `NewReview.kt`: `data class ReviewPlace`, `reviewParent(structure, reviews, place, canvases)`, `reviewFolderName(structure, parent, branch)`, `reviewDir(...)`, `reviewWorkTree(dirHint, branch, worktreesSetting)`, `prNumber(input)`, `startReview(project, branch, dirHint, place, pr, profile)`, `data class ReviewStart`, `class NewReviewAction`, `chooseProfile(project, title, message)` (also used by New Canvas...).

## Invariants

- `plugin.xml` names `ReviewFileSystem` (key `myair-review`), `ReviewEditorProvider`, `ReviewFileIconProvider` (`order="first"`) and `ReviewTabTitleProvider`; a rename or move must update it.
- `ReviewTabs.startManager` asserts the EDT. A new manager runs in the review's work tree with the main checkout as its project; a stored one keeps its directories. The manager is stored in `review.json` and filed in the review folder.
- `startReview` writes `review.json` before the folder, so a failed structure write leaves an ignored file rather than an empty folder, and checks that the place did not change meanwhile.
- A review is never started inside a review or a canvas (`reviewParent` goes beside the outermost of them); `ClaudeSessionsService.updateFolders` refuses any structure write that puts a review folder in a review or plan folder.
- An open `ReviewEditor` watches its review's git (`ReviewService.watchGit`): only watched reviews have git facts.
- In the panel a base changes only through `ReviewPanel`'s private `changeBase` (Change Base..., whose list leaves out refs holding HEAD, and Use it on the header's `myAirBase` line): `storeBase` off the EDT, then the review, then `ActiveSession.baseStored`; the MCP op `set_base` takes the same steps in `ReviewMcpToolset`. Run and its items are disabled with `runProblem`'s reason, also shown in the Reviewers card; the reviewer prompt's range size is read off the EDT before the job starts.
- Run checks each reviewer's Claude profile (its `profile`, else `default`) with `managerToolsRefusal` off the EDT before its job starts; a refusal fails that reviewer's start as `Reviewer <name>: <reason>`, its run removed and the message shown on its row.
- The manager's profile is stored with it at creation (`startReview`'s `profile`, asked by `NewReviewAction` through `chooseProfile` only when profiles besides `default` are listed, `default` preselected); before a manager with no process starts, `ReviewEditor` checks a profile other than `default` (`ReviewTabs.profileToCheck`, `FolderTabs.profileToCheck`).
- A run's status shows as `RunStatus.text` (needs input, stopped without report); Fix with Claude accepts a finding only when open (`acceptIfOpen`); the reviewer's prompt names the folder by id.
