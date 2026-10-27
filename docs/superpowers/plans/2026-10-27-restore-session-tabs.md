# Restore Session Tabs Implementation Plan

| Task | Status | Commit |
|---|---|---|
| 1 | done | `f3e7489` |
| 2 | done | `7a4184a` |
| 3 | done | `195dbda` |
| 4 | done | `e017a6f` |

## TL;DR

- **Goal:** session tabs open when the IDE closes come back after a restart, in the same order, split and pin state, each running `claude --resume <the session the tab showed last>`.
- **Approach:** give `ClaudeSessionVirtualFile` a URL in an own virtual file system (`myair-session://`), as Air does for its thread tabs (`AgentThreadViewVirtualFileSystem`: `DeprecatedVirtualFileSystem` + `NonPhysicalFileSystem`). The platform already saves every open tab's URL in `workspace.xml` and resolves it on startup; today it drops our tabs because a `LightVirtualFile` has no resolvable URL. No own persistence: order, splits and pins come from the platform.
- **Decided (2026-10-27):** both recommended options.
- **Was needed (answered):**
  1. A restored session that already runs elsewhere (another IDE opened it, a terminal): **(rec.)** the tab opens without starting `claude` and shows the message of `confirmRunningElsewhere` with Switch to Window / Open Anyway; or: drop the tab.
  2. A restored session whose directory no longer exists (worktree removed): **(rec.)** the tab shows "Session directory no longer exists: <cwd>" and nothing starts; or: drop the tab.
- **Risks:** if restored tabs that are not selected still get their editor at startup, every one of them starts `claude` at once (the platform has `isLazyComposite`, Task 2 checks it before anything is built on it); `DeprecatedVirtualFileSystem` is deprecated but it is what Air uses on 262; a tab whose session id changed through `/clear` must save the new id, so the URL is computed, not fixed.
- **Out of scope:** reopening a closed session tab from Recent Files (Ctrl+E, `IncludeInEditorHistoryFile`), restoring tabs of Air, restoring a tab's terminal scrollback.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox syntax.

**Architecture:** `editor/SessionTabUrl.kt` (pure: path codec, restart args), `editor/ClaudeSessionFileSystem.kt` (the file system, registered as `virtualFileSystem` key `myair-session`), `ClaudeSessionVirtualFile` reports that file system and a path computed from its current `sessionId`, `cwd`, `project`. `ClaudeSessionEditor` gets a not-started state for the two cases above.

## Global Constraints

- Code, comments, strings in English. No fallbacks: a path that does not parse throws; a missing `claude` throws as today.
- Tests are plain JUnit 5 (no platform services); everything decidable without the platform goes in `SessionTabUrl.kt`.
- Every commit ends with `Plan-Task: N/4`, `Plan: docs/superpowers/plans/2026-10-27-restore-session-tabs.md`, then the `Co-Authored-By` line.

## Task 1: path codec and restart arguments (pure, TDD)

**Files:** new `src/main/kotlin/dev/myair/editor/SessionTabUrl.kt`, test `src/test/kotlin/dev/myair/editor/SessionTabUrlTest.kt`.

- [x] `sessionTabPath(sessionId, cwd, project): String` → `<sessionId>?cwd=<url-encoded>&project=<url-encoded>`; `parseSessionTabPath(path): SessionTabRef(sessionId, cwd, project)`, throwing on anything else (missing key, extra key, empty id).
- [x] Tests: round trip with a Windows path holding spaces, `#`, `&`, `?` and non-ASCII; cwd equal to project; malformed paths throw.
- [x] `restartArgs(sessionId, claudeHome): List<String>`: `--resume <id>` when `<claudeHome>/projects/*/<id>.jsonl` exists, else `--session-id <id>` (a new session closed before its first message has no transcript, and `--resume` of it fails). Test with a temp `claudeHome`.

## Task 2: the file system, and the lazy-editor check

**Files:** new `src/main/kotlin/dev/myair/editor/ClaudeSessionFileSystem.kt`, `ClaudeSessionVirtualFile.kt`, `src/main/resources/META-INF/plugin.xml`.

- [x] `ClaudeSessionFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem`, protocol `myair-session`; `findFileByPath(path)`: the open `ClaudeSessionVirtualFile` with that session id in any open project if there is one (no second tab, no second process), else a new file from `parseSessionTabPath` with `restartArgs`, title `NEW_SESSION_TITLE` (the next refresh's `retarget` puts the real title). It touches `ClaudeSessionsService.getInstance()` as `openTab` does. `refresh`/`refreshAndFindFileByPath`: no-op / `findFileByPath`.
- [x] `ClaudeSessionVirtualFile` overrides `getFileSystem()` (the registered instance, `VirtualFileManager.getInstance().getFileSystem("myair-session")`) and `getPath()` = `sessionTabPath(sessionId, cwd, project)`, read at save time, so a `/clear`ed tab saves its new session.
- [x] Register `<virtualFileSystem key="myair-session" implementationClass="dev.myair.editor.ClaudeSessionFileSystem"/>`.
- [x] Gate, before Task 3: in the sandbox open three session tabs, select one, restart. Check in `idea.log` (the `claude for session … runs as pid` line) that only the selected tab started `claude`, and that the others start when selected. If all three start at startup: stop and bring it back to the TL;DR as a decision (start `claude` on first `selectNotify` instead of in the constructor).

## Task 3: tabs that must not start

**Files:** `src/main/kotlin/dev/myair/editor/ClaudeSessionEditor.kt`, `ClaudeSessionOpener.kt`.

Shape depends on TL;DR decisions 1 and 2; written for the recommended answers.

- [x] Restored files carry `restored = true`. For them only, `ClaudeSessionEditor` checks before starting: `!cwd.isDirectory()` → a label with the message; live in the registry with a pid that is not this tab's → the message and buttons of `confirmRunningElsewhere` (extract its text and host lookup so both use one function); Open Anyway starts the widget in place, Switch to Window brings the host forward and leaves the tab as is.
- [x] Starting the widget moves from the field initializer into a `start()` called from the constructor (not restored, or checks passed) or from Open Anyway; `getComponent` returns a panel whose content is swapped.
- [x] Tabs opened by `open`/`startNew` behave exactly as today (their checks ran in `ClaudeSessionOpener`).

## Task 4: proof

- [x] `./gradlew test buildPlugin`.
- [x] Sandbox (`runIde` command in CLAUDE.md), each case restarted once:
  - two tabs, one pinned (a new session) and one resumed, in a vertical split → same order, split and pin; each resumes its session (the transcript gets new lines under the same id);
  - a tab where `/clear` ran → restores the new session, not the cleared one;
  - a new session closed before its first message → starts with `--session-id` and the same id, no "No conversation found";
  - the session started in a terminal before the restart → the tab shows the running-elsewhere message, no second `claude` in `~/.claude/sessions/`;
  - a tab of a removed worktree → the directory message;
  - `~/.my-air/ides/<new pid>.json` lists the restored tabs (`TabsSync`).
- [x] `CLAUDE.md` "How the pieces fit": one sentence on the file system and restore; remove the line from `docs/ideas.md` if there is one.
