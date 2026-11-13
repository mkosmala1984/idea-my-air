# dev.myair.dashboard

## Overview

The sessions dashboard: one editor tab per IDE that lists, for every session not settled, what needs the user now, what was left unfinished (grouped by area of work, one chip per open signal) and what is finished (merged or without signals, settled in bulk). Spec: `docs/superpowers/specs/2026-11-13-session-dashboard-design.md`.

## Interface

- `Dashboard.kt`: `SessionGit`, `Chip`, `chipText`, `Row`, `Area`, `Dashboard`, `dashboardOf` (pure), `areaOf`.
- `GitFacts.kt`: `class GitFacts` with `read(sessions): Map<String, Result<SessionGit>>`, blocking, cached per work tree root.
- `DashboardFiles.kt`: `DashboardVirtualFile`, `DashboardFileSystem` (`myair-dashboard://sessions`), `DashboardFiles` (application service, the one file), `OpenDashboardAction`, `DashboardSingleTab` (project listener closing a second tab).
- `DashboardEditor.kt`: `DashboardEditorProvider` (type id `myair-dashboard`) and `DashboardEditor`.
