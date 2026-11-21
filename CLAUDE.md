# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

IntelliJ plugin "My Air" (Kotlin), a small take on JetBrains Air: lists Claude Code sessions, resumes them in editor tabs, tracks their live status, and switches to wherever a session already runs, across windows and across IDE instances. Windows only in practice (Win32 calls through JNA).

## Commands

```bash
./gradlew test buildPlugin                                   # all tests + build/distributions/my-air-0.1.0.zip
./gradlew test --tests dev.myair.store.TabStoreTest          # one class
./gradlew test --tests 'dev.myair.store.TabStoreTest.a withdrawn*' # one test (names are backtick sentences)
```

Run the sandbox IDE without the `CLAUDE*` variables of the Claude Code session that launches it. Inherited `CLAUDE_CODE_CHILD_SESSION` makes every `claude` started in a sandbox tab turn transcript saving off, and `--no-daemon` keeps a running Gradle daemon from passing them on:

```bash
env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde
```

Sandbox log: `.intellijPlatform/sandbox/my-air/IU-2026.3/log_runIde/idea.log` (appended across runs). Restart the sandbox after `buildPlugin`; it does not pick up a new build by itself.

Tools › Install My Air Build and Restart (`InstallMyAirBuildAction`, shown only in a project with `build/distributions/my-air-*.zip`) installs that zip over the running My Air (`PluginInstaller.installAfterRestart`: the platform's startup script swaps the folder) and restarts the IDE without the platform's own question; it ends every `claude` running in its tabs, so it asks first when one of them works (`ExitConfirmation`).

## Build setup

- Compiles against the locally installed IDE (`local("E:/dev/apps/jetbrains/IntelliJ IDEA Ultimate")`, IU-263.7307.200; `sinceBuild` 263), not a downloaded one. Its classes are Java 25 (class version 69), hence `jvmToolchain(25)` with the IDE's own JBR registered in `gradle.properties`.
- Tests are plain JUnit 5 without the IntelliJ test framework. IDE jars are on the test classpath, so tests may use libraries the IDE ships (Gson, JNA), but not platform services. Keep logic that needs tests out of IDE-service code, as `retarget` (`TabRetargeting.kt`) and `TabStore` are.
- `ClaudeHomeWatcherTest` and `TabStoreTest` measure real file-system events and timings.
- `air-plugin/` is the unpacked JetBrains Air plugin (gitignored), kept as a reference only: inspect it with `javap`, never put it on the classpath. `docs/air-plugin-analysis.md` says how to decompile and search it and what its session tabs do; add what a new analysis finds there.

## Where the data comes from

All state is read from files `claude` writes; the plugin never talks to a `claude` process. Unknown registry statuses and missing fields throw rather than being skipped: a new `claude` release that changes these formats should fail loudly. Details of each source: [docs/internals/data-sources.md](docs/internals/data-sources.md).

- **Profiles** (`store/Profiles.kt`): separate Claude Code config directories (`CLAUDE_CONFIG_DIR`) listed in `~/.my-air/profiles.json`; `default` is `~/.claude`, implicit. Every reader runs per profile and is merged; one session id in two profiles throws.
- **Transcripts** `<profile>/projects/<encoded-cwd>/<sessionId>.jsonl` (`ClaudeSessionScanner`): the directory name is lossy, so the cwd comes from the first line carrying one; one session may lie in several files; the date is the last message's timestamp, not the file's mtime; the scan is incremental and cached in `~/.my-air/scan-cache-*.json`.
- **Live registry** `<profile>/sessions/<pid>.json` (`LiveSessionRegistry`): an entry counts only if the pid's start time equals `procStart`; `/clear` and `/resume` keep the pid and rewrite `sessionId`; `bg`/`daemon`/`spare` kinds have special rules.
- **Tab store** `~/.my-air/` (`TabStore`), owned by this plugin: `ides/<pid>.json`, `requests/<pid>/`, `pins.json`, plus presets, plans, reviews, scheduled tasks and the search index. Writes go to a temp file and are moved into place (`writeAtomically`).

## How the pieces fit

Packages under `dev.myair`, by feature: `claude` (reading `claude`'s files: `ClaudeSession`, scanner, registry, watcher), `store` (files in `~/.my-air` and `writeAtomically`), `folders` (folder model, `structure.json` store and history, MCP change lists), `mcp` (the toolset and its queries and JSON), `service` (`ClaudeSessionsService`, the taskbar board, tab re-targeting), `editor` (session tabs and `ClaudeSessionOpener`), `tabs` (finding tabs and windows, cross-IDE sync), `actions` (menu actions, worktrees, `Git`), `toolwindow` (the Claude Sessions panel), `prompt` (the new-session panel of the empty editor area), `planview` (the plan tab), `review` and `reviewview` (review folders and their tab), `schedule` (scheduled tasks), `dashboard`, `search`, `branchdiff`. Each package has a `README.md` with its own notes. Tests sit in the package of what they test. Classes named in `plugin.xml` and `my-air-mcp.xml` are found at runtime only, so a move must update them.

`ClaudeHomeWatcher` feeds `ClaudeSessionsService` (application service), which refreshes on file events plus every 60 s, holds the session list and live statuses, re-targets open tabs by pid (`retarget`) and notifies the tool-window panels.

Feature internals live in `docs/internals/`; read the one for the area you are changing before editing it:

| Area | File |
|------|------|
| Watcher, terminal-backed session tabs, `ClaudeSessionOpener`, `TabsSync`, close confirmations | [sessions-and-tabs.md](docs/internals/sessions-and-tabs.md) |
| New sessions in worktrees, session configurations (`presets.json`), the composer (`PromptPanel`) | [new-sessions.md](docs/internals/new-sessions.md) |
| Session tree and pinned list, Session Context, Branch Diff integration | [session-ui.md](docs/internals/session-ui.md) |
| Plan folders (`structure.json` v3-5), plan tab, plan managers | [plans.md](docs/internals/plans.md) |
| Review folders, reviewers, `my_air_review_*` tools | [reviews.md](docs/internals/reviews.md) |
| Sessions dashboard, scheduled tasks | [dashboard-and-schedule.md](docs/internals/dashboard-and-schedule.md) |
| `taskbar/` WPF app and `board.json` | [taskbar.md](docs/internals/taskbar.md) |
| Decompiled JetBrains Air reference | [air-plugin-analysis.md](docs/air-plugin-analysis.md) |

Rules that hold across features:

- Keep logic that needs tests out of IDE-service code (as `retarget` and `TabStore`); tests have no platform services.
- Never read git, the registry or files on the EDT; the panels read off the EDT and rebuild when the answer lands.
- Session system-prompt text (`SessionSystemPrompt.kt`) must name every tool of `FoldersMcpToolset` (`SessionSystemPromptTest` checks it).
- `structure.json` is versioned; a build that writes a newer version stops older installs reading it, so bump only when a new field is used.
- When you change behaviour described in `docs/internals/`, update that file in the same commit (moved verbatim from the former CLAUDE.md, so it may lag the code: trust the code).
- When you add, remove or rename a public class or function, update the `README.md` of its package too (the interface index); `docs/internals/` describes behaviour across packages, the READMEs list what each package holds, and neither repeats the other.

Code, comments and strings are in English.
