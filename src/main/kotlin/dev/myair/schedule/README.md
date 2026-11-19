# dev.myair.schedule

## Overview

Scheduled tasks: Claude sessions started at fixed weekday times while an IDE runs, as detached interactive sessions, with every run and failure shown in My Air. Spec: `docs/superpowers/specs/2026-11-19-scheduled-tasks-design.md`.

## Interface

- `ScheduledTasks.kt`: `Schedule`, `ScheduledTask` (`claudeArgs(sessionId)`), `edited`, `parseScheduledTasks`/`scheduledTasksJson`, `ScheduledTaskStore` (`~/.my-air/scheduled-tasks.json`).
- `Slots.kt`: `CATCH_UP`, `latestSlot`, `nextSlot`, `dueSlot` (pure).
- `RunRecords.kt`: `RunState`, `RunRecord`, `RunRecords` (`claim`, `exists`, `update`, `of`, `all`, `prune`; `~/.my-air/scheduled-runs`), `orphaned`, `startTimedOut`, `blocksNext`.
- `RunProject.kt`: `pickProject`, `preStartError` (pure).
- `ScheduledRuns.kt`: the application service: the 30 s tick, `runNow(taskId)`, `held(unread)`.
- `ui/TaskRows.kt`: `scheduleText`, `TaskRow`, `taskRow`, `runText` (pure).
- `ui/ScheduledTasksFiles.kt`: `ScheduledTasksVirtualFile`, `ScheduledTasksFileSystem` (`myair-scheduled://tasks`), `ScheduledTasksFiles`, `openScheduledTasks`, `OpenScheduledTasksAction`, `ScheduledTasksSingleTab`.
- `ui/ScheduledTasksEditor.kt`: `ScheduledTasksEditorProvider` (type id `myair-scheduled`), `ScheduledTasksEditor`.
- `ui/ScheduledTaskDialog.kt`: `ScheduledTaskDialog` (`task(): ScheduledTask?`).
