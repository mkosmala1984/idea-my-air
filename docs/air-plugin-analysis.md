# Reading JetBrains Air's code

What My Air learned from Air 263.7307.200 (bundled with IDEA 2026.3, IU-263.7307.200), and how to dig into a newer Air quickly. Write down new findings here.

## Getting the code

- **Where Air is.** The IDE bundles it at `E:/dev/apps/jetbrains/IntelliJ IDEA Ultimate/plugins/air-plugin/lib/`: `air-plugin.jar` plus about 280 modules in `modules/`. The gitignored `air-plugin/` in this repo is a copy that should match.
  - Check the version with `unzip -p air-plugin/lib/air-plugin.jar META-INF/plugin.xml | grep -m1 '<version>'`.
  - When the IDE updates, replace the copy.
  - A stale copy in `%APPDATA%/JetBrains/IntelliJIdea2026.3/plugins/air-plugin` (262.x, `until-build 262.*`) is ignored by 263.
- **Version diff.** Diff module lists between versions with `diff <(ls old/lib/modules) <(ls new/lib/modules)`. Diff class lists by listing each jar with `unzip -l`.
- **Decompile a module** with the IDE's Fernflower. The output goes in the scratchpad, never in the repo:
  ```bash
  IDE="/e/dev/apps/jetbrains/IntelliJ IDEA Ultimate"
  /c/Users/kosma/.jdks/openjdk-27/bin/java.exe -cp "$IDE/plugins/java-decompiler/lib/java-decompiler.jar" \
    org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler -dgs=1 -log=ERROR \
    air-plugin/lib/modules/intellij.air.frontend.session.view.jar "$OUT"
  ```
  - The result is a jar of `.java` files. Unzip it into its own folder.
  - Run several modules in parallel with `&` and `wait`. 8 modules take about 1 minute.
  - Run at most about 6 at once, with `-Xmx2g`, each into its own output folder. One run of 42 modules in parallel left 16 big jars truncated (no central directory) and printed no error.
  - Some classes come out as empty `.java` files: 76 across 42 modules, among them `AgentTurnLocalHistoryImpl` and `AgentSessionChangedFilesServiceImpl`. Read those with `javap -c -p`.
  - The transcript analysis (`SessionJsonlAnalyzer`, `ClaudeSessionJsonlDecoder`) is in `intellij.air.session.analysis.jar`, which is easy to miss.
- **Quick checks.** `javap` from a JDK of at least 25 is enough for signatures (`-p`), bytecode (`-c`) and annotations (`-v`), for example `/c/Users/kosma/.jdks/openjdk-27/bin/javap.exe`.
  - The JBR in the IDE has no `javap`.
  - Corretto 8 cannot read class version 69.
  - In Git Bash, pass a Windows classpath: `cygpath -w` each jar and join them with `;`.
- **Searching decompiled Kotlin:**
  - Every class carries a `d2 = {...}` metadata line that names all its members. Filter it out (`grep -v 'd2 = '`).
  - Suspend functions turn into `label`/`L$n` state machines. Search for the call you care about, not the control flow.
  - String literals and `LOG.info(...)` texts are the fastest way in.
- **User-visible texts** live in `messages/*Bundle.properties` inside each module jar. Grepping them, for example `close|stop|running`, finds a feature's classes by its wording.
- **Registry keys and extensions** are in each module jar's `*.xml`:
  ```bash
  unzip -p <jar> '*.xml' | grep -E 'registryKey|implementation='
  ```
  - Some keys and their defaults are declared only in `air-plugin.jar`'s `META-INF/plugin.xml`, not in a module.
  - Some features check a key in code with a default there: `Registry.is("air.sessions.fork", false)`. Grep the sources for `Registry` too.
- **The `claude` binary** (`~/.local/bin/claude`, a Bun bundle) holds its minified JS in plain text. `grep -aoE '.{0,200}<literal>.{0,200}'` shows how claude writes a file or reads an env var, e.g. `CLAUDE_CODE_SESSION_KIND` or the `sessions/<pid>.json` writer.
- **Finding a class's module:**
  ```bash
  for j in modules/*.jar; do unzip -l $j | grep -q '/Name.class$' && echo $j; done
  ```

## Module map (session parts)

| Module | Holds |
|---|---|
| `intellij.air.frontend.session.view` | session tab: `AgentThreadViewVirtualFile` (the one class My Air reflects on: `getThreadId`, `getSessionId`), `AgentThreadViewFileEditor`, tab lifecycle, close handlers, bundle `AgentThreadViewBundle` |
| `intellij.air.frontend.session.terminal` | terminal of a tab: `ToolWindowAgentThreadViewTerminalTabs`/`Tab` (`TransferableTerminalSession`), startup options |
| `intellij.air.backend.session.runtime` | open/close decisions and surface state: `AgentThreadViewBackendApiImpl`, `AgentThreadViewSurfaceService`, `decideOpenSurface` |
| `intellij.air.backend.session.terminal` | terminal process state: `DetachedTerminalRetirement`, `LocalTerminalClientProcesses` |
| `intellij.air.shared.session.terminal` | `SessionTerminalState` (`isDetached`, `retiresDetached`), `SessionTerminalCloseReason` |
| `intellij.air.backend.session.api` | `AgentSessionRowActivityService.isWorking` (row activity) |
| `intellij.air.backend.claude.threads` | Claude specifics: CLI flags and env (`ClaudeCliSupport`), live session index, daemon mode (`ClaudeDaemon*`), rename/colour engines, the hook bridge (`ClaudeHookBridgeKt`) |
| `intellij.air.backend.claude.common` | Claude transcript reader: `ClaudeThreadsStore`, `ClaudeTranscriptRowReader`, outline/usage/file-change scan states, live registry parser (`ClaudeLiveSessionRegistryKt`) |
| `intellij.air.session.analysis` | edits extracted from a transcript (`SessionJsonlAnalyzer`, `ClaudeSessionJsonlDecoder`) and line attribution (`SessionChangeAttributor`) |
| `intellij.air.backend.session.runtime` | also changed-files attribution (`changedfiles/`), manual file assignments (`AgentSessionFileAssignmentService`) |
| `intellij.air.backend.session.runtime.vcs` | per-turn LocalHistory labels (`AgentTurnLocalHistoryImpl`), ACP sessions only |
| `intellij.air.backend.session.editor` | external editor: `VISUAL` helper script and the `air/editor` HTTP handler |
| `intellij.air.backend.session.chain` | Run New Session After This (`ChainedSessionCoordinator`) |
| `intellij.air.frontend.session.toolwindow` | Agent Sessions tree, activity notifications (`AgentThreadsActivityNotificationService`) |
| `intellij.air.frontend.core` | sounds (`AirSound`, `sounds/air*.wav`) |
| `intellij.air.backend.worktree`, `frontend.worktree` | managed worktrees: create with local changes, commit/cherry-pick/push header, expiry |

## How Air runs a session tab

- **Two halves over RPC.** The frontend tab only reports surface events: `surfaceMaterialized`, `surfaceSelected`, `surfaceClosed` and `surfaceTabMoved`, sent by `AgentThreadViewFrontendLifecycleReporter`. The backend owns session and terminal state.
- **Opening** (`AgentSessionOpenSurfacePolicy`, `decideOpenSurface`): a session that already lives somewhere is redirected there. Switching between the TUI (terminal) and the GUI (chat) needs consent (`AskOffer`). A terminal session shows in one window at a time, and a tab moves between projects through `AgentThreadViewCrossProjectDock*` (`closesForMove`).
- **Closing a tab** (`AgentThreadViewPendingEditorLifecycleService.fileClosed`):
  - a move, a session still shown elsewhere, or `CLOSING_TO_REOPEN` changes nothing;
  - otherwise the tab reports `surfaceClosed`, the terminal loses `presented` and counts as detached.
  - If the session works, a hint shows once: "Your Agent Session is still running. You can find it in Agent Sessions."
- **Retirement** (`DetachedTerminalRetirement`): 60 s after detaching, and again every 60 s, it checks `isWorking`. The first check that finds the session not working closes it with `RETIRED`. Other close reasons are `ARCHIVED` (marked Done) and `PROJECT_CLOSED`.
- **Process apart from its view.** The platform's `TransferableTerminalSessionFactory` (application service, `intellij.terminal.frontend`, `@ApiStatus.Internal`):
  - `create(project, ShellStartupOptions, resolver?, fusInfo, deferSessionStartUntilUiShown)` starts the process;
  - `view` is the first view; `bind(project)` (or `prepareBind(project).commit().finish()`) gives a new view to the live process, in any project;
  - `processId()` returns the pid, `sessionState` (`NotStarted`/`Running`/`Terminated`) reports its state, and `close()` ends it.
  - With a deferred start, the process starts only once a view shows.
- **Close and exit:** `AgentThreadViewProjectCloseHandler` (`projectCloseHandler`) and `AgentThreadViewApplicationCloseVeto` ask first while sessions run.
- **Daemon mode.** The registry key `air.claude.session.daemonHosted` is off by default. When on, it runs Claude sessions as `claude --bg` jobs and opens them with `claude attach`, so they survive an IDE exit. Job state is in `~/.claude/jobs/<id>/state.json`. Even with the key off, a resume attaches a job that already runs.
- **Claude CLI.** Air starts `claude` with `--session-id`/`--resume`, `--settings`, `--append-system-prompt[-file]`, `--model`, `--effort` and `--permission-mode`, and with `CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL` and `CLAUDE_CODE_AUTO_CONNECT_IDE`. To see what a new Air passes, grep `backend.claude.threads` for `"--[a-z-]+"` and `"CLAUDE[A-Z_]*"`.

## Claude background jobs (`claude --bg`)

- **The registry record.**
  - A bg worker is a normal claude REPL started by the daemon with `CLAUDE_CODE_SESSION_KIND=bg`, `CLAUDE_BG_BACKEND=daemon` and `CLAUDE_JOB_DIR`, on top of the daemon's environment.
  - It writes its own `~/.claude/sessions/<worker pid>.json` with `kind:"bg"` and `jobId`. The other kinds are `interactive`, `daemon` and `daemon-worker`.
  - Every claude sets `CLAUDE_CODE_ENTRYPOINT` at startup (default `cli`). Statuses are the same four: `busy`, `shell`, `waiting`, `idle`.
  - Next to each `<pid>.json` lies a `<pid>.<hash>.key` file (`peerToken`, `procStartFt`).
- **How Air opens a job.**
  - `ClaudeDaemonSessionLauncher.findAttachJobId` finds a live record of the session with `kind` bg and a `jobId`.
  - `ClaudeWriterHost.attachRunningJobWithoutHosting` then runs `claude attach <jobId>` instead of `--resume`. It does this with `air.claude.session.daemonHosted` off too.
  - `jobId` is `daemonShort`, the session id's first 8 hex digits.
- **How Air stops a job.** Mark Done runs `claude stop <jobId>` (5 s timeout), then ends interactive processes of the session.
- **IDE exit.** Bg sessions are left out of the close confirmation (`outlivesIdeExit`).
- **What `--resume` does meanwhile.** claude refuses or forks a `--resume` of a session that a job runs; `attach` opens the original.
- **The job's files.** `~/.claude/jobs/<short>/state.json` has `state` (`working`/`blocked`/`done`/`stopped`/`failed`), `detail`, `needs`, `suggestedReply`, `tempo`, `tokens`, `name`, `output.result`, `respawnFlags`, `cwd` and `sessionId`.
  - `timeline.jsonl` holds `{at, state, detail, text}` lines.
  - Air reads only `state`, `template`, `backend`, `sessionId`, `daemonShort`, `cwd`, the dates and `respawnFlags`.
  - `state.json` does not say whether a job is alive. The registry does.
- **The old session.** Moving a session to the background (`/bg`) continues it under a new id. The old transcript gets `{"type":"continued-in","continuedInSessionId":…}`, and Air hides it (`ClaudeThreadIndex.isListed`).

## Transcript fields Air uses that My Air does not

- **`continued-in`.** It hides the predecessor of a session moved to the background.
- **`relocated` (`relocatedCwd`) and `worktree-state` (`worktreeSession`).** They file a session run in an agent-made worktree under the main checkout.
- **`last-prompt` and turn ends.** A turn ends at `stop_reason` `end_turn`, or at system `turn_duration`/`stop_hook_summary`. `isApiErrorMessage` marks a failed turn.
- **`message.usage`, per turn.** Air prices it with a LiteLLM table (`backend.session.cost`).
- **Outline items, parsed from the JSONL** (`JsonlOutlineScanState`). They are prompts, responses, tool calls and plan steps from `TodoWrite`/`ExitPlanMode`.
- **`agent-color` and `agent-name`.** Air writes both.
- **`isCompactSummary`.** It keeps compaction summaries out of the prompts.
- **What Air does not read.** Air does not read `file-history-snapshot`/`file-history-delta`. These lines are Claude's own checkpoints, backed by `~/.claude/file-history/<sessionId>/<hash>@v<n>`.

## What Air writes into Claude's files

- **Rename.** Air types `/rename <title>` into a live tab and polls the transcript for up to 10 s. Without a live tab it appends `custom-title` and `agent-name` lines and writes `<project dir>/<sessionId>/custom-title.json`.
- **Colour.** Air types `/color <name>`, or appends `agent-color`.
- **Mark Done.** Air renames the session to `"[archived] " + title` (`AgentThreadArchiveTitlesKt`). My Air shows that prefix as part of the title.
- **Hooks.** Air's `--settings` hooks post to the built-in server at `air/claude/hook` with a Bearer token (`ClaudeHookBridgeKt`). Its `PostToolUse` matcher is `Write|Edit|MultiEdit|NotebookEdit` and only signals a refresh.

## Other session features (for porting, see `docs/air-port-candidates.md`)

- **Changed files per session.**
  - Edits come from `tool_use` input and `toolUseResult.structuredPatch`/`originalFile`, with Bash as evidence that has no path.
  - They are replayed against the working tree's base to attribute lines.
  - Group by › Agent Session in the Changes view is `<changesGroupingPolicy key="airSession">`. `SimpleChangesGroupingPolicy` has `@ApiStatus.Internal` members.
  - Commit and Revert delegate to the actions `CheckinFiles` and `ChangesView.RevertFiles`.
  - The tree's Undo/Redo undo Done and folder changes, not edits.
- **Per-turn diffs** use LocalHistory system labels (`AgentTurnLocalHistoryImpl`). Only ACP sessions call it.
- **External editor.**
  - `VISUAL` points to a `#!/bin/sh` script that POSTs the file to `air/editor`, and the request returns when the tab closes. Air skips this on Windows.
  - claude on Windows runs `${VISUAL} "${file}"` with `shell:true` and waits for it (default `start /wait notepad`).
- **Fork.**
  - The command is `claude --resume <id> --fork-session --session-id <new>`, and only at the latest turn.
  - It is off by default (`air.sessions.fork`).
  - `claude` has no `--resume-session-at`.
- **Notifications.**
  - When the IDE is not active, Air shows a system notification. When the session is visible, it shows nothing. Otherwise it shows a balloon "'{0}' in {1} needs attention" / "has completed".
  - Sounds come from `sounds/airTaskComplete|airTaskError|airInputRequired.wav`, at most one per 3000 ms (`air.sounds.agent.cooldown.ms`).
- **Chained sessions.** `air.chainedSessions` is EAP-only, and terminal sessions are excluded (`supportsChainedSession`).

## What My Air took from it

- Detached sessions: `SessionTerminals` and `Retirement.kt`. See `docs/superpowers/specs/2026-11-07-detached-sessions-design.md`.
- Close/exit confirmation: `CloseConfirmation.kt`.
- Touchpoints to re-check on every Air update:
  - `com.intellij.air.thread.view.AgentThreadViewVirtualFile` with `getThreadId`/`getSessionId` (`SessionHosts`);
  - the registry key `air.inline.empty.state.prompt` (`PromptPanel`).
