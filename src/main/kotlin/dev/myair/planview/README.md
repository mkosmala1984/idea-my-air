# dev.myair.planview

## Overview

The plan tab. A plan folder opens as an editor tab, with the plan on the left (`PlanPanel`) and the terminal of its manager session on the right (a `dev.myair.editor.SessionPane`). The tab's file is a `PlanVirtualFile` under the `myair-plan://<folderId>` file system, so the platform saves it with the open tabs and restores it on startup. The package also tells the rest of the plugin which session a plan tab shows (`sessionFileOf`, `shownSessionFiles`, `tabFileOf`).

## Interface

- `FolderTabs.kt`, shared by plan and review tabs: `abstract class FolderTabFile(folderId, name)`, `abstract class FolderTabFileSystem(protocol, fileOf)`, `fun parseFolderTabPath(path)` and `class FolderTabs<F>(create)`, with `file`, `manager`, `running`, `setManager`, `managerFile`, `forgetManager`, `tabOf`, `openHere<E>(project, folderId)` and `editors<E>(folderId)`.
- `ManagerTabEditor.kt`: `abstract class ManagerTabEditor : FileEditor, SessionPaneHost`, a folder's content beside its manager's terminal, which exposes `pane`, `gone` and `showManager()`.
- `PlanEditor.kt`: `class PlanEditorProvider` (type id `myair-plan`, `HIDE_DEFAULT_EDITOR`) and `class PlanEditor : ManagerTabEditor`.
- `PlanFiles.kt`:
  - `class PlanVirtualFile(folderId)`, whose path is the folder id.
  - `class PlanFileSystem` (`PROTOCOL = "myair-plan"`).
  - `fun managerStart(existing, newId, cwd, project, hasTranscript)`.
  - `@Service(APP) class PlanFiles`, with `store`, `file(folderId)`, `manager(folderId)`, `startManager(folder, structure, ideProject)`, `tabs`, `planOf(file)` and `forgetManager(folderId)`.
- `PlanOpener.kt`: `object PlanOpener`, with `open(project, folderId)`.
- `PlanPanel.kt`: `class PlanPanel(project, folderId, parent) : JPanel, Scrollable`, with `show(view)`.
- `PlanView.kt`: `data class TaskView`, `MilestoneView`, `PlanView`, `enum Section` (`IDEAL`, `CONSTRAINTS`, `DECISIONS`, `REQUIREMENTS`, `REVIEW`), `Folding`, `fun planViewOf(structure, folderId, sessions, managerId)`, `fun statusCounts(tasks)`, `fun openWork(counts)`, `fun requirementsSummary(requirements)`, `fun reviewSummary(review)` and `fun newestFirst(decisions)`.
- `PlanSections.kt`: `PlanPanel.sectionCard(section, plan)`, the grey card of each section with its rows and `...` menu.
- `MarkdownHtml.kt`: `data class MarkdownColors`, `fun markdownHtml(markdown, colors)`.
- `PlanEdit.kt`:
  - `sealed interface PlanEdit`: `Title`, `Next`, `Description`, `Constraints`, `NewDecision`, `NewRequirement`, `NewBlocker`, `NewTask`, `NewMilestone`.
  - `fun PlanEdit.apply(folderId, text, today)`.
  - `fun PlanEdit.current(plan)`.
- `InlineEdits.kt`: `class OpenEdit(edit, component)`, `fun inlineField`, `fun inlineArea`.
- `PlanTabs.kt`: `class PlanTabTitleProvider` and `class PlanFileIconProvider`.
- `ShownSessions.kt`: `fun hasPlan`, `fun managedPlanFolder`, `fun sessionFileOf`, `fun shownSessionFiles`, `fun tabFileOf`.

## Invariants

### Files and registration

- `src/main/resources/META-INF/plugin.xml` names `PlanFileSystem` (key `myair-plan`), `PlanEditorProvider`, `PlanFileIconProvider` (`order="first"`) and `PlanTabTitleProvider`. These names are resolved at runtime only, so a rename or move must update `plugin.xml`.
- `PlanFiles` keeps exactly one `PlanVirtualFile` per folder id for the IDE's lifetime (`file`). `LightVirtualFile` compares by identity, so an open plan is focused rather than opened twice, and a plan that comes back reuses its file.
- The manager's `ClaudeSessionVirtualFile` is never opened as a tab. `SessionTerminals` keys processes by file identity, so `startManager` returns the kept file while that process `runs`.
- `startManager` asserts the EDT and requires `folder.plan`. A new manager runs in `structure.startFolder(folder.id).startDir`, else in the IDE project's directory. A stored entry keeps the directories it was stored with.
- `managerStart`:
  - with no entry, returns a new session and `--session-id`;
  - with an entry that has a transcript, returns `--resume`;
  - with an entry that has no transcript yet, returns `--session-id`.
- `parseFolderTabPath` rejects an empty path or one containing `/`, `?` or `&`. `PlanVirtualFile` is kept out of Recent Files (`isIncludedInEditorHistory = false`).

### Editor and panel

- The panel's header shows the name, the task progress and the summaries `R <proven>/<all>` and the review's, not the description, which is the Ideal state section. `Folding.open(section, plan)` is by default true for Requirements, for Code review while a blocker is unresolved and for the other sections while empty.

- `ManagerTabEditor` builds nothing until `service.loaded` and while `foldersError` is set. Until then it shows "Loading..." or the error.
  - A plan or review that is gone sets `gone` for good: the editor shows only a notice, its manager's process is closed, and `FolderTabs.openHere` closes that tab before opening it again.
- `planViewOf` returns null when the folder or its plan is gone. It leaves the manager session out of the task rows.
- `PlanEdit.apply` returns a change applied by id to the structure as it is at save time.
  - A blank title or new item gives null, and the edit is refused.
  - An empty next step or description clears it.
  - `kind` requires an `M` or `T` id prefix.
- `PlanEdit.current` returns null once its item is gone. `PlanPanel` then drops that open edit.
- `PlanPanel.render` rebuilds only when what it shows changes: the plan, the open edit, session ids, and per session the title, live status, unread mark and short age. It keeps the open edit's text component, so the text, caret and focus survive.
- `inlineField` and `inlineArea` bind Enter, Ctrl+Enter and Esc through `registerCustomShortcutSet`, so Esc does not reach the editor.
- `sessionFileOf` returns the manager of a plan tab only while its folder still `hasPlan`.

## Side Effects

- `PlanFiles.startManager`:
  - writes `~/.my-air/plan-managers.json` through `PlanManagerStore.update`, which also prunes the entries of folders gone;
  - for a new manager, files its session in the plan folder through `ClaudeSessionsService.updateFolders(ChangeSource.AUTO)`, which writes `structure.json`.
- `PlanEditor`:
  - starts the manager's `claude` process when it first builds, through `SessionPane`;
  - in `planGone`, ends that process (`SessionTerminals.close`), drops the manager's file (`forgetManager`) and disposes the pane;
  - updates the tab presentation (`updateFilePresentation`) when the plan's name changes or the plan goes, and notifies `ActiveSession.tabFileChanged`.
- `PlanPanel` writes `structure.json` through `updateFolders(ChangeSource.UI)` for edits, the milestone's Mark Done/Reopen and a task's status, from its status button or its menu. Its task menus start new sessions through `NewSessionInDirAction` with a `PlanStart`, and open sessions through `ClaudeSessionOpener.open`.
- `PlanOpener.open`:
  - reads `plan-managers.json`;
  - may focus another IDE (`TabsSync.focusElsewhere`, a request file);
  - otherwise opens, and possibly first closes, the tab in this window.
- `PlanFileSystem.findFileByPath` instantiates `ClaudeSessionsService`.

## Verification

- Run: `./gradlew test --tests 'dev.myair.planview.*'`
