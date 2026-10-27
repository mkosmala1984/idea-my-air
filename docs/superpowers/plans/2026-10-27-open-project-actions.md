# Open Project Actions Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | |
| 2 | done | |
| 3 | done | |
| 4 | in progress: sandbox check by the user | |

## TL;DR

- **Goal:** "Open in IDEA" and "Open in Terminal" for a project row, a folder bound to a project and a session row (its cwd) in the tree's menu, and as two links in the context pane's Environment section (the session's cwd).
- **Approach:** `openInIdea(dir)` and `openInTerminal(project, dir)` in `actions/OpenInDir.kt`, called by `OpenInDirAction` (tree menu, shown/enabled like `NewSessionInDirAction`) and by the Environment links. IDEA: this IDE only, an open project gets focus, else `ProjectUtil.openOrImportAsync`. Terminal: the IDE's Terminal tool window of the current project, a new tab at `dir` (`TerminalToolWindowManager`).
- **Decided (2026-10-27):** built-in terminal, this IDE only, a session opens its cwd.
- **Needs your input:** none.
- **Risks:** the Terminal tool-window API (`TerminalToolWindowManager.createShellWidget` or the 262 equivalent) must be checked with `javap`, the plugin already uses `LocalTerminalDirectRunner`; `openOrImportAsync` is `ide.impl` API; a project open in another IDE instance opens a second window here.
- **Out of scope:** another IDE instance or launcher, an external terminal, toolbar buttons, a configurable command, opening a file at a line.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox syntax.

**Architecture:** `toolwindow/SessionTree.kt` `nodeDir` gains a `ClaudeSession` case; `actions/OpenInDir.kt` holds the two openers and `OpenInDirAction`; `SessionsPanel` adds both actions to the `ClaudeSessionsPopup` group after the two new-session actions; `SessionContextPanel` adds two links to the Environment project row.

## Global Constraints

- Code, comments, strings in English. No fallbacks: a failing process start or an unresolvable path throws.
- Nothing blocking on the EDT: `ProcessBuilder.start` and project opening go through `executeOnPooledThread` / the async API.
- `NewSessionInDirAction`'s update rule is reused, not copied: extract `dirAvailability(e, dir)` if the third copy would appear, otherwise let `OpenInDirAction` mirror it in three lines.
- Every commit ends with `Plan-Task: N/4`, `Plan: docs/superpowers/plans/2026-10-27-open-project-actions.md`, then the `Co-Authored-By` line.

## Task 1: `nodeDir` for session rows

**Files:** `src/main/kotlin/dev/myair/toolwindow/SessionTree.kt`, test in `src/test/kotlin/dev/myair/toolwindow/` (next to the existing `nodeDir` test, if any; else `SessionTreeTest`).

- [x] Failing test: a `TreePath` ending in a `ClaudeSession` node returns `Path.of(session.cwd)`; existing project and folder cases unchanged.
- [x] Add `is ClaudeSession -> Path.of(item.cwd)` to `nodeDir`. Check that `NewSessionInDirAction` now also appears on session rows: it must not (or it is wanted, then say so in the TL;DR). If unwanted, add a separate `openDir` function instead of changing `nodeDir`.
- [x] `./gradlew test --tests <class>`.

## Task 2: the openers and the menu action

**Files:** new `src/main/kotlin/dev/myair/actions/OpenInDir.kt`.

- [x] `javap` the IDE's `com.intellij.ide.impl.ProjectUtil` (`openOrImportAsync`, `focusProjectWindow`, `findProject`/`isSameProject`) and pick the call; record the signature in a comment only if it is non-obvious.
- [x] `openInIdea(dir)`: if `ProjectManager.openProjects` has a project with `basePath == dir` focus it, else open it.
- [x] `javap` `org.jetbrains.plugins.terminal.TerminalToolWindowManager` (jar `plugins/terminal/lib/terminal.jar`); `openInTerminal(project, dir)` creates a shell widget at `dir` in the Terminal tool window and activates it.
- [x] `OpenInDirAction` mirrors `NewSessionInDirAction.update` (hidden without a directory, disabled when `!isDirectory()`).
- [x] Register both in `SessionsPanel` popup (`src/main/kotlin/dev/myair/toolwindow/SessionsPanel.kt:167`), after the worktree item, before the first separator; icons `AllIcons.Nodes.Folder` and `AllIcons.Debugger.Console`.

## Task 3: Environment links

**Files:** `src/main/kotlin/dev/myair/toolwindow/SessionContextPanel.kt` (`content`, Environment section).

- [x] On the project row (line 110) add two `link`s, "IDEA" and "Terminal", aligned right, calling the Task 2 openers with `context.cwd`; shown only when `context.cwdExists`.
- [x] Not worktree-specific: the same `context.cwd` as the row below shows for the branch.

## Task 4: proof

- [ ] Unit test for what is pure (Task 1). The openers are platform calls: verify in the sandbox IDE (`runIde` command in CLAUDE.md): right click a project row → Open in IDEA focuses an open project and opens a closed one; Open in Terminal adds a Terminal tool-window tab in that directory (`pwd` shows it); the same two links in the Environment section behave identically; a session row of a removed worktree shows the items disabled.
- [ ] Add the finished feature to `CLAUDE.md` "How the pieces fit" in one sentence and remove the line from `docs/ideas.md`.
