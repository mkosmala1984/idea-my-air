# dev.myair.reviewview

## Overview

The review tab and New Review. A review folder opens as an editor tab, the review on the left (`ReviewPanel`) and the terminal of its manager session on the right, on the plan tab's shared `FolderTabs` and `ManagerTabEditor` (`dev.myair.planview`). The tab's file is a `ReviewVirtualFile` under the `myair-review://<folderId>` file system. The model and `review.json` are in `dev.myair.store`; the service, operations and prompts are in `dev.myair.review`.

## Interface

- `ReviewFiles.kt`: `class ReviewVirtualFile(folderId)`, `class ReviewFileSystem` (`PROTOCOL = "myair-review"`), `reviewManagerStart(review, newId, mainCheckout, hasTranscript)` and `@Service(APP) class ReviewTabs` with `tabs`, `startManager(folderId)`, `reviewOf(file)` and `managerPrompt(file)`.
- `ReviewEditor.kt`: `class ReviewEditorProvider` and `class ReviewEditor`.
- `ReviewOpener.kt`: `object ReviewOpener` with `open(project, folderId)`.
- `ReviewPanel.kt`: `class ReviewPanel(project, folderId, parent, pane, managerRuns)` with `show(result)`.
- `ReviewView.kt`: `enum FindingFilter`, `data class Header`, `ChapterFile`, `ChapterView`, `RunView`, `FindingView`, `ReviewView`, `bitbucketPrUrl(originUrl, pr)`, `reviewViewOf(...)` and `findingMessage(finding)`.
- `ReviewEdits.kt`: `sealed interface ReviewEdit`, `ReviewEdit.current(review)` and `ReviewEdit.apply(text)`.
- `ReviewTabsProviders.kt`: `class ReviewTabTitleProvider` and `class ReviewFileIconProvider`.
- `NewReview.kt`: `data class ReviewPlace`, `reviewParent(structure, reviews, place)`, `reviewFolderName(structure, parent, branch)`, `reviewDir(...)`, `reviewWorkTree(dirHint, branch, worktreesSetting)`, `prNumber(input)`, `startReview(project, branch, dirHint, place, pr)`, `data class ReviewStart`, `class NewReviewAction`.

## Invariants

- `plugin.xml` names `ReviewFileSystem` (key `myair-review`), `ReviewEditorProvider`, `ReviewFileIconProvider` (`order="first"`) and `ReviewTabTitleProvider`; a rename or move must update it.
- `ReviewTabs.startManager` asserts the EDT. A new manager runs in the review's work tree with the main checkout as its project; a stored one keeps its directories. The manager is stored in `review.json` and filed in the review folder.
- `startReview` writes `review.json` before the folder, so a failed structure write leaves an ignored file rather than an empty folder, and checks that the place did not change meanwhile.
- A review is never started inside a review (`reviewParent`); `ClaudeSessionsService.updateFolders` refuses any structure write that puts a review folder in a review or plan folder.
- An open `ReviewEditor` watches its review's git (`ReviewService.watchGit`): only watched reviews have git facts.
- A run's status shows as `RunStatus.text` (needs input, stopped without report); Fix with Claude accepts a finding only when open (`acceptIfOpen`); the reviewer's prompt names the folder by id.
