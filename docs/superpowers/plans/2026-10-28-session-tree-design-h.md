# Session Tree Design H Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `7d97117` |
| 2 | done | `696c2b6` |
| 3 | done | `57daaca` |
| 4 | done | `26bbf02` |

## TL;DR

- **Goal:** the Claude Sessions tree as drawn in artboard H of https://claude.ai/artifact/67rxFDLcdMfTjTPG2qD63j: a session row is status icon, title, task count, age; a folder, project or group row shows on its right how many sessions it holds and the icon of the most urgent one.
- **Approach:** the row labels (`needs input: …`, `working`, `waiting for prompt`, `unread reply`, `settled`, `Desktop`, project name, current task) move into the tooltip; an unread session's title turns bold; the count is painted by `SessionTrailer`, which already paints the age, from a pure `folderSummary` over the tree nodes.
- **Decided (2026-10-28):** sessions stay expandable (goal, tasks, added dirs, changed files as today); `5/6` stays in the row; of the extras only the folder count is built; status shown as the mock-up's coloured dots (tree rows and group counts only; editor tabs and the pinned list keep the IDE icons); a settled session is a grey italic title with no label; a session under `recent` keeps its project name grey in the row, elsewhere the project is in the tooltip.
- **Needs your input:** none.
- **Risks:** the count walks the subtree on every paint of a visible group row (hundreds of sessions: cheap, but measured in Task 3); the pinned list shares the row code and keeps its project suffix and IDE icons.
- **Out of scope:** header counters, "+N older sessions", the Session Context panel (artboard I), Projects/Folders toggle, dots on editor tabs.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** compact session rows and per-group session counts in the Claude Sessions tree.

**Architecture:** pure logic in `toolwindow/SessionTree.kt` (`Urgency`, `urgencyOf`, `FolderSummary`, `folderSummary`) and `toolwindow/SessionRenderers.kt` (`briefCount`, `tooltipLines`); painting in `SessionTrailer`; wiring in `SessionsPanel`.

**Tech Stack:** Kotlin, IntelliJ Platform Swing (`ColoredTreeCellRenderer`, `SimpleTextAttributes`), JUnit 5 without the platform test framework.

**Spec:** artboard "H. Drzewko sesji" (`project/Folders-qq8y.dc.html`) and its three notes on the canvas above; the answers given in the session of 2026-10-28.

## Global Constraints

- Code, comments, strings in English. No fallbacks; an unexpected node type in `folderSummary` throws.
- Tests are plain JUnit 5 in `src/test/kotlin/dev/myair/toolwindow/`; no platform services in tested code.
- Run tests with `./gradlew test --tests dev.myair.toolwindow.<Class>`; the full gate is `./gradlew test buildPlugin`.
- Every commit ends with `Plan-Task: N/4`, `Plan: docs/superpowers/plans/2026-10-28-session-tree-design-h.md`, then the `Co-Authored-By` line.

## Review Focus

1. A filter or the age limit is on: the count is of the sessions the tree shows, not of all sessions (test in Task 1).
2. Nested folders: a folder counts the sessions of its subfolders too, and the most urgent of them (test in Task 1).
3. A session row's children (goal, tasks, dirs, files) are never counted and never get a count (test in Task 1).
4. A long folder path runs under the count: the count covers it as the age covers a title (manual check in Task 4).
5. An unread session that is also settled: bold and grey italic do not combine into an unreadable row — settled wins (rule in Task 2 Step 3, checked by eye in Task 4).

---

### Task 1: Folder summary

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt`
- Test: `src/test/kotlin/dev/myair/toolwindow/SessionTreeTest.kt`

**Interfaces:**
- Produces:
  - `enum class Urgency { NEEDS_INPUT, UNREAD, WORKING }` — declaration order is priority, first most urgent.
  - `fun urgencyOf(live: LiveSession?, unread: Boolean): Urgency?` — `NEEDS_INPUT` → `NEEDS_INPUT`, `WORKING` → `WORKING`, `IDLE` and unread → `UNREAD`, else null.
  - `data class FolderSummary(val sessions: Int, val urgent: Urgency?)`
  - `fun folderSummary(node: DefaultMutableTreeNode, urgency: (ClaudeSession) -> Urgency?): FolderSummary?` — null for a `ClaudeSession` node and for its children (`BriefGoal`, `BriefTaskRow`, `AddedDir`, `ChangedFile`); for `Folder`, `Path`, `RecentSessions`, `UnfiledSessions` the count of `ClaudeSession` nodes below it (not descending into a session) and the minimum `Urgency` by ordinal among them; throws on any other user object.

- [x] **Step 1: Write the failing tests** in `SessionTreeTest`:
  - `` `a folder counts the sessions below it, subfolders included, and names the most urgent` ``: a `foldersTree` with folder A holding session s1 and subfolder B holding s2 and s3; urgency s1 → `WORKING`, s2 → `NEEDS_INPUT`, s3 → null. Assert A → `FolderSummary(3, NEEDS_INPUT)`, B → `FolderSummary(2, NEEDS_INPUT)`, an empty folder → `FolderSummary(0, null)`.
  - `` `a session and its children get no summary` ``: a session with a brief and a changed file; `folderSummary` of the session node and of each child is null.
  - `` `a filtered tree counts only the sessions it shows` ``: `foldersTree(structure, sessions, "title s1")`; the folder's summary is `FolderSummary(1, …)`.
  - `` `urgency follows the live status, unread only when idle` ``: the four cases of `urgencyOf`, plus `WORKING` with `unread = true` → `WORKING`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.toolwindow.SessionTreeTest` — expect compilation failure on the missing names.
- [x] **Step 3: Implement** the four declarations above in `SessionTree.kt`.
- [x] **Step 4: Run** the same command — expect PASS.
- [x] **Step 5: Commit** `Count the sessions under each tree group and find the most urgent`.

### Task 2: Compact session row and its tooltip

**Files:**
- Create: `src/main/kotlin/dev/myair/toolwindow/StatusDots.kt`
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionRenderers.kt`
- Test: `src/test/kotlin/dev/myair/toolwindow/BriefTextTest.kt`

**Interfaces:**
- Produces:
  - `StatusDots.kt`: `class StatusDot(color: JBColor, ring: Boolean = false) : Icon`, 16×16 scaled, an 8 px filled circle centred, or with `ring` a 10 px circle stroked 1.6 px with a 3/4 arc gap; `val NEEDS_INPUT_DOT = StatusDot(JBColor(0xE56D17, 0xF0883E))`, `val UNREAD_DOT = StatusDot(JBColor(0x3574F0, 0x548AF7))`, `val WORKING_DOT = StatusDot(JBColor(0x3574F0, 0x548AF7), ring = true)`, `val IDLE_DOT = StatusDot(JBColor(0xC9CCD6, 0x5A5D63))`; `fun dotOf(live: LiveSession?, unread: Boolean): Icon?` — null without live, else by `urgencyOf` (Task 1), `IDLE_DOT` when it is null.
  - `fun briefCount(brief: Brief?): String?` — `"done/total"`, null without tasks; replaces `briefLabel`, which is deleted.
  - `fun tooltipLines(session: ClaudeSession, live: LiveSession?, unread: Boolean, settled: Boolean, project: Path?): List<String>` — in order: the status line (`"Needs input: <waitingFor>"` or `"Needs input"`, `"Working"`, `"Unread reply"`, `"Waiting for prompt"`, none without `live`), `"Settled"`, `"Desktop"`, `"Project: <project.fileName>"` when `project` is given, then goal, tasks and first prompt as today.

- [x] **Step 1: Write the failing tests**, replacing the two existing ones:
  - `` `a row shows the task count only` ``: `briefCount` gives `"1/3"` for the fixture brief, `"0/1"`, and null for no tasks and no brief.
  - `` `the tooltip carries the status, settled, Desktop and project before the brief` ``: needs-input live with `"dialog open"`, `settled = true`, a `desktop = true` session, `project = Path.of("/rtm")` → `listOf("Needs input: dialog open", "Settled", "Desktop", "Project: rtm", "Goal: Move AGC config", "✓ export", "▶ map", "○ e2e", "Asked: Migrate AGC")`.
  - `` `an idle session reads as unread reply or waiting for prompt` ``: idle live, `unread = true` → first line `"Unread reply"`; `false` → `"Waiting for prompt"`; no live and no brief → `listOf("Asked: Migrate AGC")`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.toolwindow.BriefTextTest` — expect compilation failure.
- [x] **Step 3: Implement** `StatusDots.kt`, `briefCount`, the new `tooltipLines`, and change `appendSession(item, live, settled, unread, project: Path?, projectInRow: Boolean, dots: Boolean)`:
  - icon: with `dots` (the tree) `dotOf(live, unread)`; without (the pinned list) `live.status.icon`, `UNREAD_ICON` when idle and unread, as today; none without live;
  - title attributes: settled → `GRAY_ITALIC_ATTRIBUTES`; else no live → `GRAYED_ATTRIBUTES`; else unread → `REGULAR_BOLD_ATTRIBUTES`; else `REGULAR_ATTRIBUTES`;
  - then `"  " + briefCount` in `GRAYED_ATTRIBUTES`;
  - `projectInRow` appends `"  " + cwd.fileName` in `GRAYED_ATTRIBUTES`; nothing else in the row.
  Tree renderer: parent `RecentSessions` → `project = null, projectInRow = true`; parent `Folder` not bound to `item.cwd` → `project = item.cwd, projectInRow = false`; otherwise `project = null, projectInRow = false`; always `dots = true`. `PinnedRenderer`: `project = null, projectInRow = true, dots = false`.
- [x] **Step 4: Run** the same command — expect PASS; then `./gradlew test`.
- [x] **Step 5: Commit** `Move session row labels into the tooltip, keeping the task count`.

### Task 3: Paint the count on group rows

**Files:**
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionTrailer.kt`
- Modify: `src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt:101-112`

**Interfaces:**
- Consumes: `folderSummary`, `urgencyOf`, `Urgency` (Task 1).
- Produces: `SessionTrailer(component, rowAt, bounds, session, summary: (row: Int) -> FolderSummary?, background, buttons)`; `val Urgency.dot: Icon` in `StatusDots.kt` — `NEEDS_INPUT` → `NEEDS_INPUT_DOT`, `UNREAD` → `UNREAD_DOT`, `WORKING` → `WORKING_DOT` (also what `dotOf` uses).

- [x] **Step 1: Implement** in `SessionTrailer.paint`: a row with no session and a non-null summary paints, right-aligned at `right()`, the count in `GRAYED_ATTRIBUTES` colour and, before it with a `JBUI.scale(4)` gap, `urgent?.dot`, covering the text under it with `cover` as the age does. Hover changes nothing on these rows.
- [x] **Step 2: Wire** the tree: `summary = { folderSummary(tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode) { s -> urgencyOf(service.live[s.id], isUnread(s)) } }`; the pinned list passes `{ null }`.
- [x] **Step 3: Build** `./gradlew test buildPlugin` — expect BUILD SUCCESSFUL.
- [x] **Step 4: Measure** in the sandbox (Task 4 command) with every group expanded: scroll the tree; the IDE's `idea.log` must show no `UI was frozen` entries.
- [x] **Step 5: Commit** `Show each tree group's session count and most urgent status`.

### Task 4: Check in the sandbox and update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (the sentence "get "unread reply" and the idle icon with a blue dot in the rows and on their tab" and the tree description)

- [x] **Step 1: Run** `env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`, open Claude Sessions in Folders mode and check against artboard H in the light and the dark theme: each session state (orange dot needs input, blue dot and bold unread, blue ring working, grey dot waiting, no dot and grey no process, settled grey italic with Show All), the project name grey under `recent` only, the tooltip lines, an expanded session's children, the counts on a bound folder, a plain folder, an empty folder (`0`), `recent` and `sessions`, and a long folder path covered by the count. Take a screenshot.
- [x] **Step 2: Update** `CLAUDE.md`: tree rows show status as coloured dots (`StatusDots.kt`), unread sessions a bold title, while tabs and the pinned list keep the idle icon with a blue badge; row labels live in the tooltip; group rows show their session count and most urgent status (`folderSummary`).
- [x] **Step 3: Run** `./gradlew test buildPlugin` — expect BUILD SUCCESSFUL.
- [x] **Step 4: Commit** `Describe the compact session rows in CLAUDE.md`.
