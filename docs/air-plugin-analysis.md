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
| `intellij.air.backend.claude.threads` | Claude specifics: CLI flags and env, live session index, daemon mode (`ClaudeDaemon*`) |

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

## What My Air took from it

- Detached sessions: `SessionTerminals` and `Retirement.kt`. See `docs/superpowers/specs/2026-11-07-detached-sessions-design.md`.
- Close/exit confirmation: `CloseConfirmation.kt`.
- Touchpoints to re-check on every Air update:
  - `com.intellij.air.thread.view.AgentThreadViewVirtualFile` with `getThreadId`/`getSessionId` (`SessionHosts`);
  - the registry key `air.inline.empty.state.prompt` (`PromptPanel`).
