# Close confirmation with working sessions: plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `cd539b42f1` |
| 2 | done | |
| 3 | | |

## TL;DR

- Goal and behaviour: `docs/superpowers/specs/2026-11-07-close-confirmation-design.md`.
- Three tasks: the pure decision and texts with tests; the wiring into `SessionTerminals`, a project close handler and an exit listener; CLAUDE.md and the full build.
- Nothing needs the user's input. The dialogs themselves can only be checked by hand in the sandbox (see Task 3).

Commits carry `Plan-Task: <n>/3` and `Plan: docs/superpowers/plans/2026-11-07-close-confirmation.md`.

## Task 1: decision and texts

Files: `src/main/kotlin/dev/myair/editor/CloseConfirmation.kt`, `src/test/kotlin/dev/myair/editor/CloseConfirmationTest.kt`.

- [x] Tests: `interrupts` for each status × tasks (null status with 0 tasks false; IDLE with a task true; WORKING and NEEDS_INPUT true). `confirmation` for one and several titles in each of PROJECT, EXIT, RESTART (title, message, buttons); empty titles throw.
- [x] Implement `interrupts`, `enum class Closing`, `data class Confirmation(title, message, ok, cancel)`, `confirmation(closing, titles)`.
- [x] `./gradlew test --tests dev.myair.editor.CloseConfirmationTest`, commit.

## Task 2: wiring

Files: `SessionTerminals.kt`, `CloseConfirmation.kt`, `plugin.xml`.

- [x] `SessionTerminals`: one private helper giving a file's registry status and running tasks, used by `retireIdle` and by new `interrupted(project: Project?)`; make it `Disposable` and register `ExitConfirmation` in `init`.
- [x] `ProjectCloseConfirmation` (skips while `isExitInProgress`) and `ExitConfirmation` (exit and restart), both through `Messages.showOkCancelDialog` with the warning icon.
- [x] `plugin.xml`: `<projectCloseHandler implementation="dev.myair.editor.ProjectCloseConfirmation"/>`.
- [x] `./gradlew test`, commit.

## Task 3: docs and build

- [ ] CLAUDE.md: the `SessionTerminals` paragraph names the questions; the Install action line says it asks when a session works. `docs/ideas.md`: drop the idea.
- [ ] `./gradlew test buildPlugin` (4 `ContextCardsTest` failures predate this change).
- [ ] Manual check in the sandbox, not done by the agent: close a project with a working session (Keep Open keeps it, Close Project ends it); exit with one working and one idle session (only the working one is listed); Install My Air Build and Restart with a working session.
- [ ] Commit.
