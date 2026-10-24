# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

IntelliJ plugin "My Air" (Kotlin), a small take on JetBrains Air: lists Claude Code sessions, resumes them in editor tabs, tracks their live status, and switches to wherever a session already runs, across windows and across IDE instances. Windows only in practice (Win32 calls through JNA).

## Commands

```bash
./gradlew test buildPlugin                                   # all tests + build/distributions/my-air-0.1.0.zip
./gradlew test --tests dev.myair.TabStoreTest                # one class
./gradlew test --tests 'dev.myair.TabStoreTest.a withdrawn*' # one test (names are backtick sentences)
```

Run the sandbox IDE without the `CLAUDE*` variables of the Claude Code session that launches it. Inherited `CLAUDE_CODE_CHILD_SESSION` makes every `claude` started in a sandbox tab turn transcript saving off, and `--no-daemon` keeps a running Gradle daemon from passing them on:

```bash
env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde
```

Sandbox log: `.intellijPlatform/sandbox/my-air/IU-2026.2.3/log_runIde/idea.log` (appended across runs). Restart the sandbox after `buildPlugin`; it does not pick up a new build by itself.

## Build setup

- Compiles against the locally installed IDE (`local("E:/dev/apps/jetbrains/IntelliJ IDEA Ultimate")`, IU-262.10968.63), not a downloaded one. Its classes are Java 25 (class version 69), hence `jvmToolchain(25)` with the IDE's own JBR registered in `gradle.properties`.
- Tests are plain JUnit 5 without the IntelliJ test framework. IDE jars are on the test classpath, so tests may use libraries the IDE ships (Gson, JNA), but not platform services. Keep logic that needs tests out of IDE-service code, as `ClaudeSessionsService.retarget` and `TabStore` are.
- `ClaudeHomeWatcherTest` and `TabStoreTest` measure real file-system events and timings.
- `air-plugin/` is the unpacked JetBrains Air plugin (gitignored), kept as a reference only: inspect it with `javap`, never put it on the classpath.

## Where the data comes from

All state is read from files `claude` writes; the plugin never talks to a `claude` process.

- **Transcripts** `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl` (`ClaudeSessionScanner`). The directory name encodes the cwd lossily, so the cwd comes from the first line carrying a `cwd` field. Title: the last `custom-title` line, else the last `ai-title` line, else the first human prompt. Changed files: `Write`/`Edit`/`MultiEdit`/`NotebookEdit` `tool_use` items inside `"type":"assistant"` lines; `api-request-shape` lines also name those tools and must not count. Transcripts are append-only, so a rescan reads only complete lines after the stored offset; `subagents/` one level deeper is not listed.
- **Live registry** `~/.claude/sessions/<pid>.json` (`LiveSessionRegistry`), one per running `claude`: `sessionId`, `status` (`busy`/`shell`/`waiting`/`idle`; `shell` counts as working, as in claude's own list), `waitingFor`, `entrypoint`, `procStart`. A file outlives a killed process, so an entry counts only if the pid's start time equals `procStart`, a Windows FILETIME. `/clear` and `/resume` keep the pid and rewrite `sessionId`, which is how tabs follow their session. A file without `statusUpdatedAt` belongs to a `claude` still starting and is skipped.
- **Tab store** `~/.my-air/` (`TabStore`), owned by this plugin: `ides/<pid>.json` lists each IDE's session tabs, `requests/<pid>/` receives "focus this session" requests, `pins.json` holds the sessions pinned by hand, the running ones removed from the list and the settled ones (`Pins`; the list above the tree adds every session with a live process, `pinnedSessions`). Settled sessions are hidden from the list and the tree until the toolbar's Show All Sessions is on (`Pins.shown`); settling unpins, pinning unsettles. A removed running session returns once its transcript has grown and it no longer works; refresh prunes dismissals that are over. Writes go to a temp file and are moved into place.

Unknown registry statuses and missing fields throw rather than being skipped; a new `claude` release that changes these formats should fail loudly.

## How the pieces fit

- `ClaudeHomeWatcher` watches `sessions/` and every `projects/<dir>/`, debounced 500 ms quiet / 700 ms cap (Air's timings). `ClaudeSessionsService` (application service) refreshes on its events plus every 60 s, holds the session list and live statuses for all projects, and after each change re-targets open tabs by pid (`retarget`) and notifies subscribed tool-window panels.
- `ClaudeSessionVirtualFile` + `ClaudeSessionEditor` host a terminal widget running `claude --resume <id>` (or `--session-id <new uuid>`) with `CLAUDE_CODE_NO_FLICKER=1` and `CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL=true`. The tab learns the process pid from the tty connector; closing the tab ends the process.
- `ClaudeSessionOpener.open` goes, in order: a tab in this IDE (`SessionHosts.findTab`, which includes Air's tabs read through reflection on `getThreadId`/`getSessionId`) → a tab in another IDE running this plugin (`TabsSync.focusElsewhere`: `AllowSetForegroundWindow` for that IDE, then a request file) → a warning when the registry shows the session running in another program, offering its window (`SessionHosts.hostWindow`, the nearest ancestor of the `claude` process that owns a visible window) → a new tab.
- `NewClaudeSessionInWorktreeAction` (toolbar and Tools menu, next to the plain new-session action) lists branches in a popup: the default branch (`origin/HEAD`; without `origin` the one local `main`/`master`), the 5 recent bases (project `PropertiesComponent`), the rest. The pick becomes the base of branch `worktree-<id8>` checked out by `git worktree add --no-track` in `<worktrees dir>/<id8>`, `<id8>` being the start of the new session id; gitignored files matching `.worktreeinclude` are copied in, as `claude --worktree` does (`claude` itself takes no base branch). The worktrees dir is the one application setting (`MyAirSettings`, Settings › Tools › My Air), default `.claude/worktrees`, relative to the main checkout. `Git` runs the `git` on PATH and throws with its stderr; the pure parts (`baseChoices`, `pushRecent`, `defaultBranch`, `worktreePath`) are in `Worktrees.kt`, and `GitTest` runs real `git` in a temp dir.
- `TabsSync` republishes this IDE's tabs on tab open/close (project listener), project startup and re-targeting; it withdraws its file on dispose.

Code, comments and strings are in English.
