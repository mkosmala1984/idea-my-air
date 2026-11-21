# dev.myair.dashboard

## Overview

The sessions dashboard: one editor tab per IDE that lists, for every session not settled, what needs the user now, what was left unfinished (grouped by area of work, one chip per open signal), what is finished (merged or without signals, settled in bulk) and, folded, what waits for its git read (Checking git). Specs: `docs/superpowers/specs/2026-11-13-session-dashboard-design.md`, and for Analyze `docs/superpowers/specs/2026-11-13-dashboard-session-analysis-design.md`.

## Interface

- `Dashboard.kt`: `SessionGit`, `Chip`, `chipText`, `RowAnalysis` (`None`, `Current`, `Failed`), `Row`, `Area`, `Dashboard` (with `checking`), `dashboardOf` (pure), `areaOf`, `analysisCandidates(board, days, now)`.
- `GitFacts.kt`: `class GitFacts(file)` with `read(sessions, progress): Map<String, Result<SessionGit>>`, blocking, newest session first, `progress` getting the sessions each git read completes; cached per work tree root and per (root, branch), loaded from `file` (`~/.my-air/dashboard-git.json` from `DashboardEditor`) at the first read and written back after a read that ran git.
- `DashboardFiles.kt`: `DashboardVirtualFile` (a `SingleTabFile`, see `tabs/SingleTab.kt`), `DashboardFileSystem` (`myair-dashboard://sessions`), `DashboardFiles` (application service, the one file), `OpenDashboardAction` (`openSingleTab`).
- `DashboardEditor.kt`: `DashboardEditorProvider` (type id `myair-dashboard`) and `DashboardEditor`, with Analyze and its days chooser.
- `Analyses.kt`: `Verdict`, `Analysis`, `AnalysisStore` (`~/.my-air/analysis/<sessionId>.json`).
- `Digest.kt`: `Excerpt`, `excerptOf` (prompts and last agent texts of a transcript), `digestOf` (what Haiku reads).
- `Analyzer.kt`: `ANALYSIS_SCHEMA`, `analysisPrompt(digest)`, `analysisArgs`, `parseAnalysis`, `analyze` (one `claude -p` process), `analyzeSession`, `AnalysisRun` (3 at a time, cancellable).
