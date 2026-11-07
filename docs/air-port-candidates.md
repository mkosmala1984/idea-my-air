# What to port from JetBrains Air 263.7307.200

## TL;DR

- **Goal:** Air features for Claude sessions that My Air can build from Claude's files and its own terminal, without ACP. Ranked by value over cost. Items already ported are left out.
- **Top 5:**
  1. Follow `/bg` sessions: hide `continued-in` predecessors, and run `claude attach` / `claude stop` on a live job (S+M).
  2. Uncommitted changes per session, with Commit/Revert (M). This is the idea "pokaż niezacomitowane zmiany".
  3. Notification and sound on finish or needs input when the tab is not visible (S).
  4. Fork: `--resume <id> --fork-session --session-id <new>`, here or in a new worktree (S).
  5. Ctrl+G edits the prompt in an IDE tab (M; Windows not yet tried).
- **Defect found:** a refresh fails with `NoSuchFileException` when a `sessions/<pid>.json` vanishes mid-read (D1).
- **Decisions for you:**
  - (a) Should a tab of a bg job run `claude attach` and leave the job running when it closes, or only warn? Should My Air start sessions with `--bg`?
  - (b) Per-session changes only in My Air (public API), or also Group by Session in the Changes view (`@ApiStatus.Internal`)?
  - (c) Should I try Ctrl+G on Windows?
  - (d) Rename a session with no process by appending `custom-title` to its transcript, as Air does, or allow only `/rename` in a live tab?
- **Out of scope:** TUI/GUI switching, Agent UI, Cloud, mobile, session log zip, Copy Session Link.

---

Evidence comes from Air's decompiled modules (Fernflower; `javap -c` where Fernflower left a file empty), the `claude` 2.1.319 binary's strings, and files under `~/.claude` on this machine. **[checked]** marks a claim verified directly in code or on disk while writing this report. Other claims come from research passes over the decompiled code that read the classes named. How to reproduce the reading is in `docs/air-plugin-analysis.md`.

Cost: S = up to a day, M = a few days, L = a week or more.

## Defects found during the research

### D1. Registry read fails when a session file disappears
- **Symptom.** `LiveSessionRegistry.read` lists `*.json`, then reads each file. A `claude` that exits in between deletes its file, so `readText` throws `NoSuchFileException`. The whole refresh fails ("Claude session refresh failed", `idea.log` 2026-11-06 14:50:49, `sessions\61256.json`) **[checked]**.
- **Fix.** Catch `NoSuchFileException` around that one read and treat it as "process gone". It is an expected race, not a format change, so the loud failures for unknown fields stay.
- **Cost:** S.

### D2. Background jobs and `continued-in` (part of candidate 1)
- **Duplicate entries.** `/bg` (or `claude --bg` on a resumed session) continues a conversation in a new session id. The old transcript gets `{"type":"continued-in","continuedInSessionId":…}` **[checked]**: `cd4bacec…` → `6ef71af9…`, and both are listed in the folder `projects/testflo-e2e-pipelines/bitbucket-pipelines-aws`. My Air shows both.
- **Air's handling.** `ClaudeThreadIndex.isListed` (`backend.claude.threads`) hides the predecessor once the successor has history.

## Ranked candidates

### 1. Sessions moved to the background (`claude --bg`, `/bg`)
- **What Air does.**
  - **Finding a running job.** `ClaudeDaemonSessionLauncher.findAttachJobId` (`backend.claude.threads`) finds a live `sessions/<pid>.json` record with the session's id, `kind` `bg` and a `jobId`.
  - **Opening it.** `ClaudeWriterHost.attachRunningJobWithoutHosting` then opens the session with `claude attach <jobId>` instead of `--resume`. It does this even with `air.claude.session.daemonHosted` off: "…so Air attaches the job, although air.claude.session.daemonHosted is off".
  - **Stopping it.** Mark Done runs `claude stop <jobId>` (`ClaudeSessionSource.stopSessionWorkers`).
  - **IDE exit.** Such sessions are left out of the close confirmation, since they outlive the IDE (`ClaudeLiveSessionSnapshot.outlivesIdeExit`).
  - **Other details.** `ClaudeCliSupport.buildAttachCommand` builds `[claude, attach, jobId]`. `jobId` is `daemonShort`, the first 8 hex digits of the session id.
- **What My Air sees today.**
  - **The registry record.** The bg worker is a normal claude REPL started with `CLAUDE_CODE_SESSION_KIND=bg` and the daemon's environment. It writes its own `sessions/<worker pid>.json` with `kind:"bg"` and `jobId` (binary: `E4()` returns `bg|daemon|daemon-worker`, and the pid-file writer has `kind:s,entrypoint:i.CLAUDE_CODE_ENTRYPOINT`) **[checked]**.
  - **No parse failure.** Every claude sets `CLAUDE_CODE_ENTRYPOINT` at startup (`"cli"` by default) **[checked]**, and statuses are the same four. So `LiveSessionRegistry` parses the record and shows the session as live with the right status. A live `--bg` run has not been observed yet.
  - **Opening fails.** `ClaudeSessionOpener` warns "running in another program". `hostWindow` finds no window for a daemon worker, and Open Anyway starts `claude --resume`. Claude refuses that or forks it: "`claude attach X` opens the original" (binary). A restored tab hits the same guard.
- **Port.**
  1. Hide sessions with a `continued-in` line whose successor transcript exists (`ClaudeSessionScanner`). Cost: S.
  2. Read `kind` and `jobId` in `LiveSessionRegistry`. For a live bg record, the opener (`ClaudeSessionOpener`, restored tabs in `ClaudeSessionEditor`) runs `claude attach <jobId>` in the tab.
  3. The tab's process is then the attach client. Retargeting, `Retirement`, `interrupts` and the status must key on the session id or `jobId`, not the client's pid.
  4. Closing the tab, or retiring it, ends only the client. Close and Settle runs `claude stop <jobId>`. `ExitConfirmation`/`ProjectCloseConfirmation` skip bg sessions.
  5. Optional: `~/.claude/jobs/<short>/state.json` carries `state`, `detail`, `needs` ("confirm push: …"), `suggestedReply`, `output.result` and `tokens` **[checked]**. Air reads none of them. Session Context and the taskbar flyout could show `needs`. `state.json` does not say whether a job is alive; the registry does.
- **Cost.** S for item 1, M for items 2–4.
- **Value.** High. You use `/bg`: there are four jobs in `~/.claude/jobs`, and the daemon log shows `bg spawned … (slash)` on 2026-11-04.
- **Risks.** The `jobs/` format and `attach` behaviour are undocumented and change between CLI releases (`respawn` exists for that reason). A tab bound to a job splits the 1:1 link between pid and tab that `SessionTerminals` assumes today.

### 2. Uncommitted changes per session, with Commit/Revert
- **What Air does.**
  - **Where it shows.** Under each session in Agent Sessions, Air lists the uncommitted files that session changed. The setting reads "Show which sessions changed each file — Air reads the session history" **[checked]**.
  - **Attribution.**
    - `ClaudeStoreThreadBackend.loadThreadAnalysis` → `SessionJsonlAnalyzer` (`session.analysis`) takes the edits from the transcript: `tool_use` input (`file_path`, `edits`, `old_string`/`new_string`), `toolUseResult.structuredPatch`/`originalFile`, and Bash as evidence with no path.
    - `SessionChangeAttributor.attributeFile` replays them against the working tree's base text to attribute chunks of lines.
    - Changes come from `ChangeListManager`, or from `git status --porcelain` for a project that is not open.
  - **Manual moves.** Files can be moved by hand between sessions (`AgentSessionFileAssignmentService`, workspace.xml), and Reset Changed File Assignments drops the moves.
  - **Changes view.** The IDE's Changes view gets Group by › Agent Session (`<changesGroupingPolicy key="airSession" weight="35">`) **[checked]**.
  - **Commit and Revert.** Both delegate to the platform actions `CheckinFiles` and `ChangesView.RevertFiles` with the session's `Change`s (`AgentThreadsCommitChangesAction`) **[checked: class exists]**. Revert works on whole files.
- **Port.**
  - **Path-level version.** Map the uncommitted `Change`s of the session's work tree (`ChangeListManager`, or the `changesOf` My Air already builds) to the files the session wrote. My Air already collects those for the Files group from `Write`/`Edit`/`MultiEdit`/`NotebookEdit`. A file written by two sessions goes to the latest writer, or under both.
  - **Where it shows.** Show the result as a Changes section in Session Context and in the session's tree node. Add Commit… and Revert… that call the two platform actions.
  - **Later.** Line-level attribution from `structuredPatch`.
- **Cost.** M path-level, L line-level.
- **Value.** High when several sessions share one checkout. With one session per worktree, Session Context's "changes vs base" already covers it.
- **Risks.** `SimpleChangesGroupingPolicy` has `@ApiStatus.Internal` members **[checked, javap]**, so the Changes view grouping is internal API (decision b). A Bash edit (`sed -i`, a generator) is invisible to path matching.

### 3. Notification and sound on finish / needs input
- **What Air does.**
  - **Routing.** `AgentThreadsActivityNotificationServiceKt.routeAgentThreadsNotification` (`frontend.session.toolwindow`):
    - IDE not active: a system notification (`SystemNotifications`);
    - session open in a tab of the active IDE: nothing;
    - otherwise: a balloon "'{0}' in {1} needs attention" / "has completed" with Open session.
  - **Expiry.** An attention balloon expires once the question is answered.
  - **Sounds.** `AirSound` in `frontend.core` plays `airTaskComplete.wav`, `airTaskError.wav` and `airInputRequired.wav` **[checked]**. They play only when the session is not being viewed, at most one per `air.sounds.agent.cooldown.ms` = 3000. Settings › Sounds has a checkbox per event.
- **Port.** `ClaudeSessionsService` already sees status transitions and `unread`. On working → idle or → needs input, check whether `SessionHosts.visibleSessions` shows the session. If it doesn't, notify through the existing "My Air" group, or `SystemNotifications` when the IDE is inactive. Play a sound through `com.intellij.util.ui.playSound`.
- **Cost:** S.
- **Value.** High in the IDE. The taskbar bubble covers this only outside the IDE.
- **Risks.**
  - `playSound` is `@ApiStatus.Experimental`.
  - Every IDE running My Air sees the same transition, so only the IDE that holds the session's tab (or one chosen IDE) should notify.
  - Air's `.wav` files belong to JetBrains; ship your own sounds or use the system ones.

### 4. Fork a session
- **What Air does.** `ClaudeSessionSource.forkThreadFromOutlineItem` → `ClaudeCliSupport.buildForkResumeCommand` runs `claude --resume <source> --fork-session --session-id <new uuid>` in a new terminal **[checked]**.
  - **Only the latest turn.** Only the latest prompt can be forked (`rememberLatestForkablePrompt`).
  - **Where.** `AirWorktreeForkHandler` offers "Fork in this workspace" or "Fork in new worktree".
  - **Off by default:** `air.sessions.fork` = false **[checked]**.
- **Port.** Add Fork Session to the tree, tab and Session Context menus. It opens a pinned tab with those flags in the session's `workDir`, or in a new worktree through `createWorktree`. It complements Continue Session, which starts a new conversation with only a pointer to the old one. A fork keeps the whole context.
- **Cost:** S.
- **Value.** Medium to high.
- **Risks.** `claude --help` lists no `--resume-session-at`, so forking at an older message is not possible. Air does not do it either.

### 5. Ctrl+G opens the prompt in the IDE
- **What Air does.**
  - **Launch.** `AgentExternalEditorLaunchParticipant` (`backend.session.editor`) sets `VISUAL` for Claude **[checked]** to a helper script (`AgentExternalEditorScriptStore`, `#!/bin/sh`) **[checked]**. The script POSTs the file path to the built-in server, `air/editor`, with a per-launch Bearer token.
  - **IDE side.** `AgentExternalEditorHttpRequestHandler` opens the file in a tab and answers when `fileClosed` fires. Claude then reads the file back.
  - **Disabled on Windows:** `OS.CURRENT == OS.Windows` **[checked]**; the log says "the external editor stays the agent default on Windows".
- **Windows facts from the binary** **[checked]**:
  - claude resolves `VISUAL`, then `EDITOR`, then `start /wait notepad`;
  - it spawns `${editor} "${file}"` with `shell:true` and waits for it to exit;
  - it refuses paths with cmd.exe metacharacters.
  - So a `.cmd` helper that blocks on `curl.exe` should work. This has not been tried.
- **Port.**
  - Set `VISUAL` in `SessionTerminals`' env to a `~/.my-air/editor/<token>.cmd`.
  - Register `com.intellij.httpRequestHandler` with prefix `myair/editor`, which opens the file and completes on `FileEditorManagerListener.fileClosed`.
- **Cost:** M.
- **Value.** Medium to high for long prompts and plan files. The composer covers only the first message.
- **Risks.** The Windows behaviour is untested (decision c). The built-in server port changes between IDE runs, so the script must be written per launch. A restored tab needs a fresh script.

### 6. Session actions in the terminal and tab menus
- **What Air does.** Air puts its session actions into the reworked terminal's context menu (`AgentThreads.SessionView.TerminalPopup` → `Terminal.ReworkedTerminalContextMenu`) and the tab menu: Pin, Rename, Fork, Copy (Session ID, Session Path), Mark Unread, Done, Color.
  - **Rename** types `/rename <title>` into a live tab and waits up to 10 s for the transcript to show it **[checked: `"/rename "`]**. Without a live tab it appends `custom-title` and `agent-name` lines, plus a sidecar `<project dir>/<id>/custom-title.json`.
  - **Color** types `/color <name>` or appends `agent-color` **[checked: `"/color "`]**.
  - **Mark Unread** toggles to Mark Read.
- **Port.**
  - Add the same entries to My Air's tab and terminal menus.
  - Rename: `ClaudeSessionEditor.type("/rename …")` with Enter for a live tab (decision d for the rest).
  - Mark Unread: write a `seen/<id>` older than the last message (`Seen.kt`).
  - Read `agent-color` in `ClaudeSessionScanner` to tint rows and tabs.
- **Cost:** S.
- **Value.** Medium.
- **Risks.** Appending to a transcript that a running claude is writing can interleave lines.

### 7. More fields in presets and the composer
- **Air's presets** (`frontend.launch.presets`, `AgentSessionPreset`):
  - permission mode (`default`/`acceptEdits`/`plan`/`auto`/`dontAsk`/`bypassPermissions` → `--permission-mode`);
  - plan mode and plan effort;
  - "Full Access" (`--dangerously-skip-permissions`);
  - fast mode;
  - injected instructions (`prePrompt`, sent before the first prompt and hidden from the title);
  - a default preset, and "Start a new session with: Last used / Default".
- **Port.** Add `permissionMode` and `instructions` (→ `--append-system-prompt`, concatenated with `appendedSystemPrompt`) to `Preset` and `PresetDialog`, and a permission-mode chip in `PromptPanel`. Your bg jobs carry `--permission-mode auto` in `respawnFlags`, so you use it.
- **Cost:** S.
- **Value.** Medium.
- **Risks.** Low. Like model and effort, these flags must not be passed on `--resume`.

### 8. Keep the PC awake while sessions work
- **What Air does.** Air's setting "Prevent system sleep while agents are working (also while waiting for review)".
- **Port.** Call `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` through JNA (already used) while any live session is working. Clear it when none is. Keep it in `ClaudeSessionsService`, as one application-wide state.
- **Cost:** S.
- **Value.** Medium for long runs.
- **Risks.** The call must come from one long-lived thread, or the state is dropped when that thread ends.

### 9. Tokens and cost per session
- **What Air does.**
  - **Tokens.** `ClaudeThreadsStore` (`backend.claude.common`) reads `message.usage` of assistant lines (`input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, with the 5 min/1 h split) per turn.
  - **Prices.** `backend.session.cost` prices them with a LiteLLM table: bundled, refreshed from `resources.jetbrains.com`, with long-context tiers.
  - **Turns.** A turn ends at `stop_reason` `end_turn` or system `turn_duration`/`stop_hook_summary`.
- **Port.**
  - Sum usage per session and per turn in the scanner's incremental pass. Deduplicate by `message.id`, because one API response is split across several lines.
  - Show tokens and turn durations in Session Context.
  - Cost needs a price table: bundle a small one for Claude models only.
- **Cost.** S for tokens, M for cost.
- **Value.** Medium.
- **Risks.** A subscription user pays no per-token price, so cost can mislead. Tokens and context size are the more useful numbers.

### 10. Run a session after another finishes
- **What Air does.**
  - **Trigger.** `backend.session.chain`: a queued session starts when its parents are no longer working or waiting (`ChainedSessionPredecessorLookup`).
  - **Prompt.** It gets the held prompt, plus either the parent's transcript as `User:`/`Assistant:` text up to 12 000 chars (`PredecessorTranscriptContextBuilder`) or a fork of the parent.
  - **Re-runs.** "Trigger on all parent session updates" re-runs it after every later follow-up.
  - **Availability.** `air.chainedSessions` is EAP-only, and `supportsChainedSession` excludes terminal sessions. Air does not offer this for Claude TUI.
- **Port.** Store a queued prompt per parent in `~/.my-air`. When the registry shows the parent idle with no open background task (the `retires` test without the 60 s), start it through `startPreset`. Its first message names the parent transcript, as `continuationPrompt` does.
- **Cost:** M.
- **Value.** Medium. It is a natural fit with plans.
- **Risks.** "Idle" also covers a parent that stopped to ask something in plain text. Don't fire on `NEEDS_INPUT`.

### 11. Worktree extras
- **What Air has beyond My Air** (`backend.worktree`, `frontend.worktree`):
  - create a worktree carrying the current uncommitted and untracked changes (`diff --binary --full-index` + `apply`);
  - the session header's Commit split button with Cherry-pick onto the source branch ("Apply to the base branch", undo history), Push, and "Commit and Cherry-Pick";
  - Settings › Worktree: a list of worktrees with Committed / Cherry-picked / Uncommitted (`git cherry`) and Delete Worktree (removes the branch too);
  - automatic deletion after N days of inactivity, only for idle, unarchived sessions (`ManagedWorktreeExpirationPolicy`).
- **Port.** Each is `git` plus public VCS UI.
  - "Carry my changes" is a checkbox in the composer and `NewClaudeSessionInWorktreeAction`.
  - A worktree list with clean/merged state fits Settings › Tools › My Air.
- **Cost:** M each.
- **Value.** Medium. You create many worktrees: `.claude/worktrees` in several projects.
- **Risks.** Deleting worktrees automatically loses work if the merged test is wrong. Keep it manual.

### 12. Per-turn diffs
- **What Air does.** For ACP sessions only, `AgentTurnLocalHistoryImpl` (`backend.session.runtime.vcs`) puts LocalHistory system labels at turn start and end and diffs between them. Nothing in `backend.claude.*` calls it, so Claude terminal sessions get none of this.
- **Better source for My Air.** Claude's own checkpoints:
  - `file-history-snapshot` lines map a user message id to `trackedFileBackups` (`backupFileName` `<hash>@v<n>`, `version`, `backupTime`);
  - the pre-edit copies are in `~/.claude/file-history/<sessionId>/` **[checked]**, and there are also `file-history-delta` lines.
- **Port.** Session Context lists turns (from user prompts). Each turn opens a diff of its files: the backup at that turn against the next backup or the current file.
- **Cost:** M/L.
- **Value.** Medium. `claude`'s own `/rewind` already restores code per turn, so this would add a view, not an undo.
- **Risks.** The file-history format is undocumented. Reading LocalHistory back needs `LocalHistoryImpl` (`@ApiStatus.Internal`).

### 13. Session outline
- **What Air does.** The tab's Structure view (`AgentThreadViewStructureViewBuilder`) lists user prompts, responses, tool calls, plan steps from `TodoWrite`/`ExitPlanMode`, and input requests. It is parsed from the transcript (`JsonlOutlineScanState`). Copy Text and Show Full Text re-read the item from the JSONL.
- **Port.** `getStructureViewBuilder` on `ClaudeSessionEditor`, fed by the scanner. Clicking a prompt cannot scroll the terminal, so the outline is for reading and copying.
- **Cost:** M.
- **Value.** Low to medium.

### 14. Full-text search over transcripts
- **What Air does.** `AgentSessionSearchService` (`backend.session.search`) searches message text across sessions with excerpts. It is sped up by a trigram Bloom filter in `system/air/session-search-v1.mvstore`.
- **Port.** Extend `ClaudeSession.matches` with a per-session text index built in the scanner's incremental pass. Expose it in the search field and in `my_air_sessions_find`.
- **Cost:** M.
- **Value.** Medium with hundreds of sessions.
- **Risks.** Memory and the scan cache size. Index user and assistant text only.

### 15. Smaller items
- **Single session tab** (`air.singletab`, off by default): one pinned session tab per editor, and opening another session replaces it. This relates to the idea "domyślne przypinanie zakładki z sesją". S.
- **Generate Commit Message** (`vcs.commit`, `AgentCommitMessagePromptBuilder`): a prompt of `[Instructions] [History] [Diff] [Current message]`, the last 10 messages of the author, and an answer in `<commit_message>` tags. It is doable with `claude -p --model haiku --no-session-persistence`, as `suggestBranchName` does. S/M. Low value if another commit-message generator is installed.
- **Drop files onto the terminal** types their quoted paths (`formatDroppedFilePaths`). S.
- **Code notes** (`*.sessionNotes`, `air.codeNotes.ui.enabled`): notes on code ranges, attached to a session and sent as Markdown in the next prompt. L.

## Interop with Air on the same transcripts
- **Done prefix.** Air marks a session Done by renaming it `"[archived] " + title` (`AgentThreadArchiveTitlesKt`) **[checked]**. My Air would show the prefix in the title. It could strip it and treat the session as settled.
- **Rename sidecar.** Air's rename writes a `custom-title.json` sidecar next to the transcript folder. My Air ignores it, which is correct, because the `custom-title` line is written too.
