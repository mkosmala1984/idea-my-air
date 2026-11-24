# dev.myair.editor

## Overview

This package covers session tabs. A tab file (`ClaudeSessionVirtualFile`, URL `myair-session://<id>?cwd=…&project=…`, plus `&profile=…` for a non-default profile) opens a `ClaudeSessionEditor`: a `SessionHeader` above a `SessionPane`. The pane shows a terminal that `SessionTerminals` (an application service) runs apart from the tab. `SessionTerminals` builds the `claude` command line: the system prompt, the prompt file (a review or canvas manager's, or a plan's) and the brief-reminder hook. It decides when a process detaches, retires or ends, and asks before a close interrupts one. `ClaudeSessionOpener` is the single entry point for opening or starting a session. The package also holds tab icons, tab colours and tooltips.

## Interface

- Opening:
  - `ClaudeSessionOpener.open(project, session, switchToWindow)` (`ClaudeSessionOpener.kt`). It goes through these steps in order:
    1. it focuses an existing tab in this IDE (`SessionHosts.findTab`);
    2. it focuses a tab in another IDE (`TabsSync.focusElsewhere`);
    3. it opens the plan tab when the session is a plan's manager (`PlanOpener`), else the review tab when it is a review's manager (`managedReviewFolder`, `ReviewOpener`), else the canvas tab when it is a canvas's manager (`managedCanvasFolder`, `CanvasOpener`);
    4. it warns when the session runs elsewhere other than as a background job (`RunningElsewhere`);
    5. it refuses a session whose `cwd` is gone ("Cannot Resume Session");
    6. it opens a new tab with `--resume <id>` in the session's profile.
  - `ClaudeSessionOpener.startNew(project, sessionProject, cwd, id, args, plan, folderId, profile)` (`profile` defaulting to `default`) opens a pinned tab with `--session-id <id>` and files the session in `folderId`.
  - `projectBase(project)` and `RunningElsewhere` are in the same file.
- Tab file and its providers (`ClaudeSessionVirtualFile.kt`):
  - `ClaudeSessionVirtualFile(sessionId, cwd, project, profile: Profile, title, claudeArgs, restored, planStart)` is a `LightVirtualFile` that is left out of editor history. Its `@Volatile` fields are `pid`, `job`, `status`, `unread` and `mark`.
  - `ClaudeSessionTabTitleProvider`, `ClaudeSessionFileIconProvider` and `ClaudeSessionTabColorProvider` supply the tab's title, icon and colour.
  - `isOtherProject(sessionProject, ideBase)`.
- `ClaudeSessionFileSystem` (`ClaudeSessionFileSystem.kt`), protocol `myair-session` (`PROTOCOL`): resolves a saved tab URL on startup. `SessionTabRef(sessionId, cwd, project, profile)`, `sessionTabPath(sessionId, cwd, project, profile)` (adds `&profile=` only for a non-default one), `parseSessionTabPath`, `restartArgs` (`--resume` when a transcript exists, else `--session-id`) and `hasTranscript` (`SessionTabUrl.kt`) support it.
- Editor and pane:
  - `ClaudeSessionEditorProvider` (editor type `claude-session`, `HIDE_DEFAULT_EDITOR`) and `ClaudeSessionEditor` (`ClaudeSessionEditor.kt`).
  - `SessionPaneHost` and `SessionPane(project, file, checkFirst)` (`SessionPane.kt`). The pane has `show(view)`, `showElsewhere()` (the Show Here notice), `type(text)`, which sends text unsent and returns `false` without a view, and `send(text)`, which sends it as a bracketed paste and submits it, `false` without a view. `MESSAGE_CHARS` (400) and `messageToType(text, write)` decide what `send` types.
  - `CenteredTerminal(terminal, grid, columns)` and `TextGrid` (`CenteredTerminal.kt`).
  - `SessionHeader`, `HeaderText` (its `profile`, null for `default`, ends the first line) and `headerText(session, branch)` (`SessionHeader.kt`); `NewSessionHereAction`, the header's button, `NEW_SESSION_HERE_TOOLTIP`, and `newSessionHere`, which Session Context's button calls too (`ClaudeSessionEditor.kt`).
- Processes (`SessionTerminals.kt`):
  - `SessionTerminals` (`@Service(APP)`) offers `attach`, `release`, `detach` (whatever `keepDetached` says), `starting` (no view yet), `startDetached(file, project)` (no pane, detached from the start, `deferSessionStartUntilUiShown = false`), `close`, `runs`, `detached`, `retireIdle(live, sessions, now, held)`, `jobOf`, `interrupted`, `wouldInterrupt` and `getInstance()`.
  - `SessionTerminals.ProjectClose` is a `ProjectCloseListener`.
  - `claudeExecutable()` finds `claude` on PATH.
- Lifecycle rules (`Retirement.kt`): `DETACHED_GRACE` (60 s), `retires(detachedFor, status, runningTasks, held = false)`, `runningTasks(tasks, processStart)`, `Release` (`IGNORE`, `CLOSE`, `DETACH`) and `releaseOf(owns, started, ended, keep, reopening, job)`.
- Close confirmation (`CloseConfirmation.kt`):
  - `interrupts(status, runningTasks)`;
  - `Closing` (`PROJECT`, `EXIT`, `RESTART`, `REMOVE_PLAN`, `DELETE_FOLDER`);
  - `Confirmation`, `confirmation(closing, titles)` and `confirmed(project, closing, titles)`;
  - `ProjectCloseConfirmation` (a `ProjectCloseHandler`) and `ExitConfirmation` (an `ApplicationListener`).
- `CloseAndSettleAction` (action `dev.myair.CloseAndSettle`, `CloseAndSettleAction.kt`): settles the session, closes its tab and runs `claude stop <job>` for a background job the profile's registry still lists.
- Prompts:
  - `appendedSystemPrompt(briefs, filing)`, `PlanStart(folderId, taskId)`, `sessionPlan(structure, sessionId, session, start, managed)` and `planPrompt(structure, folder, task, briefs, manager)`, and `turnIntoPlanMessage(plan, managerPrompt)`, what Turn into Plan sends its session (`SessionSystemPrompt.kt`).
  - `BRIEF_CALL`, `BRIEF_REMINDER`, `briefReminderCommand()` and `writeBriefReminderSettings(dir)` (`BriefReminder.kt`).
- Tab marks: `TabMark`, `tabMark(...)` and `tabTooltip(title, sessionProject, worktree, profile)` (`TabMark.kt`).
- Icons and colours (`SessionIcons.kt`):
  - `sessionIcon(status, unread)`, `projectIcon(dir, name, size)` and `projectColor(dir, onRead)`;
  - the icon constants `CLAUDE_ICON`, `NEW_SESSION_ICON`, `NEW_SESSION_HERE_ICON`, `CONTINUE_SESSION_ICON`, `OFFLINE_SESSION_ICON`, `UNREAD_SESSION_ICON`, `NEEDS_INPUT_ICON`, `REVIEW_ICON`, `CANVAS_ICON` (the platform's `AllIcons.Toolwindows.WebToolWindow`) and `WORKING_ICON` (an `AnimatedIcon` of the 8 internal `WORKING_FRAMES` at 100 ms);
  - the colours `UNREAD_BLUE`, `NEEDS_INPUT_AMBER` and `CLAUDE_ORANGE`.
- `plugin.xml` registrations:
  - `projectCloseHandler` `ProjectCloseConfirmation`;
  - `fileEditorProvider` `ClaudeSessionEditorProvider`;
  - `virtualFileSystem` `myair-session` `ClaudeSessionFileSystem`;
  - the icon, title and colour providers;
  - the listener `SessionTerminals$ProjectClose`;
  - the action `dev.myair.CloseAndSettle`.

  `ExitConfirmation` is registered at runtime by the `SessionTerminals` constructor (`addApplicationListener`).

## Invariants

- Each tab file has one process: `SessionTerminals` keys its `Runtime` by file identity.
  - A second pane that attaches takes the process, and the previous pane shows `showElsewhere()`.
  - A process started for a runtime that was replaced or removed meanwhile is closed at once (`runtimes[file] !== runtime`).
- `attach`, `detach`, `starting`, `startDetached`, `runs` and `wouldInterrupt` assert the EDT (`ThreadingAssertions.assertEventDispatchThread`). `startDetached` checks that the file has no process yet, `starting` that it has one.
- `SessionTerminals.start` throws `Profile <name> is gone` when the file's profile is no longer listed, before writing or registering anything.
- `release` follows `releaseOf`:
  - when the pane does not own the process, nothing happens;
  - a process that has not started, or has ended, is closed;
  - a platform reopen (`CLOSING_TO_REOPEN`) detaches the process;
  - otherwise the process detaches when `keep` is on and it is not a job view, and is closed when either condition fails.
- A detached process retires only when it has been detached at least `DETACHED_GRACE`, is `IDLE`, has no background task it started still open and is not `held` (its session in `retireIdle`'s `held`) (`retires`, `runningTasks`). `retireIdle` asserts that every detached runtime has `detachedAt`.
- A view of a background job never counts as interrupting (`wouldInterrupt`). `interrupts` is true for `WORKING`, for `NEEDS_INPUT`, or when a background task runs.
- `confirmation` requires a non-empty list of titles. `ProjectCloseConfirmation` does not ask while the application exits, because `ExitConfirmation` asks then.
- `parseSessionTabPath` requires a non-empty id, a `?`, and exactly the keys `cwd` and `project`, optionally `profile` (missing reads as `default`). `ClaudeSessionFileSystem.findFileByPath` always returns a new file marked `restored`; for a profile no longer listed, one with an empty profile directory and no `claudeArgs`.
- A restored pane (`checkFirst`) does not start while its profile is no longer listed (`Profile <name> is gone`), while its `cwd` is not a directory, or while the registry shows the session running other than as a job, unless the user picks Open Anyway.
- `startNew` asserts that an editor window exists and that the file opened in the current window. `projectBase` asserts that the project has a base path.
- `sessionPlan` asserts that a managed plan folder exists. `planPrompt` asserts that the folder has a plan. `turnIntoPlanMessage` asserts that the plan is empty (`Plan()`).
- `briefReminderCommand` asserts that its strings contain no single quote. `claudeExecutable` asserts that `claude` is on PATH.
- `EditorGrid` (in `SessionPane.kt`) requires exactly one visible terminal editor that has a character grid.
- `ClaudeSessionVirtualFile` is not writable and is left out of editor history (`OptionallyIncluded`).

## Side Effects

- `SessionTerminals.start` reads the live registry at `<profile dir>/sessions` and starts `claude` with the profile's `CLAUDE_CONFIG_DIR` (`addedProfileEnv`); it starts `claude` through `TransferableTerminalSessionFactory` on the EDT.
  - The terminal uses the Ghostty emulator and `NON_SHELL` process type, runs in the file's `cwd`, and sets `CLAUDE_CODE_NO_FLICKER=1` and `CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL=true`.
  - The command is one of:
    - `claude attach <jobId>` for a session that a job runs;
    - `claude --append-system-prompt <appendedSystemPrompt>`, then `--settings ~/.my-air/hooks/brief-reminder.json` when briefs are on, then `--append-system-prompt-file <~/.my-air/prompts/…>` for a review's manager (`ReviewTabs.managerPrompt`), else for a canvas's manager (`CanvasTabs.managerPrompt`), else for a session in a plan, then the file's `claudeArgs`.
  - A coroutine waits for the session state `Terminated`, then calls `ended` on the EDT.
- The prompt file is written through `PromptFiles.write`. It is deleted on `close`, and when the process ends by itself.
- `writeBriefReminderSettings` writes `<dir>/brief-reminder.json` atomically. The hook starts `powershell -EncodedCommand` on every prompt and reads the transcript.
- `close` ends the terminal session and so the process. `retireIdle` closes retired processes and calls `TabsSync.publish()`. `ProjectClose` closes the project's processes.
- `release` of a working session shows a "My Air" notification once per run.
- `CloseAndSettleAction`:
  - updates the pins (settle), closes the process and the tab;
  - when the profile's registry still lists the job, runs `claude stop <job>` in the session's profile (`processProfileEnv`: the IDE's environment with `CLAUDE_CONFIG_DIR` removed for `default`, set otherwise) on a pooled thread with a 5 s timeout (`STOP_TIMEOUT_MS`) and logs a timeout or a non-zero exit as an error.
- `ClaudeSessionOpener` shows dialogs ("Session Already Running", "Cannot Resume Session"), brings windows to the front, opens and pins tabs, and files sessions through `updateFolders`.
- `confirmed` shows a warning OK/Cancel dialog.
- `hasTranscript` lists `<profile dir>/projects/*/<id>.jsonl` of the session's profile.
- `projectColor` reads `ProjectIconPalette` on the EDT (`invokeLater`) and caches the colour. `ClaudeSessionTabColorProvider` then repaints the tab (`updateFilePresentation`).
- `SessionPane.type` and `send` send text to the terminal; `send` first writes a text of several lines or over `MESSAGE_CHARS` characters into `~/.my-air/messages` (`PromptFiles.write`). `ClaudeSessionEditor` subscribes to `ClaudeSessionsService` to update its header.

## Verification

- Run: `./gradlew test --tests 'dev.myair.editor.*'`
