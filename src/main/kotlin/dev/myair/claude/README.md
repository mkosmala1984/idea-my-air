# dev.myair.claude

## Overview

This package reads the files that `claude` and Claude Desktop write. It covers session transcripts, the live process registry, Desktop session metadata and skill definitions. It also watches the Claude home folder for changes. It never talks to a `claude` process and writes nothing but its own scan cache. When a format is unknown or a field is missing, it throws rather than skipping the line, so a `claude` release that changes a format fails loudly. Callers (`dev.myair.service.ClaudeSessionsService`, the MCP toolset and the UI) pass in the directories, so tests can point it at temporary folders.

## Interface

- `ClaudeSession` (`ClaudeSession.kt`): one session as read from its transcript. Fields: id, cwd, title, `lastMessage`, changed files, the first prompts, `brief`, `project`, `desktop`, `addedDirs`, `model`, `effort`, `artifacts`, `background`, `skills`, `mcpServers`, `workDir`, `startBranch`, `continuedIn`.
  - `matches(query, branch)`: every word of the query must match, case-insensitively, the title, the cwd, `startBranch` or `branch`.
  - `isWithin(days, now)`: whether the last message falls within the given number of days.
  - `toString()` returns the id.
- `Artifact`, `BackgroundTask` (with `Kind`, `End`, `Status`, `Usage`, `Agents`) (`ClaudeSession.kt`): artifacts and background tasks found in a transcript.
- `mcpServers(tools)` (`ClaudeSession.kt`): the sorted server names taken from `mcp__<server>__<tool>` names. A server whose only tools are `authenticate`/`complete_authentication` is left out.
- `Path.isAtOrUnder(dir)` (`ClaudeSession.kt`): a text comparison, equal to `dir` or below `dir` plus the separator.
- `Brief`, `BriefTask`, `TaskStatus` (`Brief.kt`): what a session is for, set by `my_air_set_brief` (`SET_BRIEF`) or by a Desktop `mcp__ccd_turn__update_plan` (`UPDATE_PLAN`).
  - `Brief.next(previous, tool, input, result)`: returns the brief after an accepted call. `planTask`, `title` and `folder` keep their previous values when a call leaves them out.
  - `Brief.input(...)`: validates the tool's arguments.
  - `Brief.folderResult(id, path, created)`: formats the tool's result, `ok, folder <id> at '<path>'`.
  - `Brief.tasks(items)`: parses the task list.
  - Computed properties: `done` and `current`.
- `ClaudeSessionScanner(projectsDir, saved)` (`ClaudeSessionScanner.kt`): reads `<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`.
  - `scan(desktopProjects): List<ClaudeSession>`: the sessions, newest first.
  - `save()`: writes the scan cache.
  - `Saved(file, build)` with `Saved.forJar(dir, jar)`: names the cache file `scan-cache-<hash of jar path>.json` and tags it with a hash of the jar's `dev/myair/claude/*.class` entries.
  - `Transcript` and its parts `Conversation`, `Messages`, `Environment` and `Call`: the incremental parse state. `Transcript.toSession(file, desktopProject)` turns it into a session.
  - `parse(file)` and `read(file, from)` on the companion object.
  - `PROMPTS = 3`.
- `LiveSessionRegistry(dir, isSameProcess)` (`LiveSessionRegistry.kt`): reads `<dir>/<pid>.json` (`~/.claude/sessions`) with `read(): Map<String, LiveSession>`. `isSameProcess(pid, procStart)` compares the process start time with `procStart`, a Windows FILETIME.
- `LiveSession`, `SessionStatus` (`WORKING`, `NEEDS_INPUT`, `IDLE`; `waitRank`) (`LiveSessionRegistry.kt`). `Map<String, LiveSession>.shownBy(pid, job)` returns the entry a tab shows: the entry with that job, else the entry with that pid or whose `parkedBy` contains it.
- `DesktopSessionReader(dataDir)` (`DesktopSessions.kt`): `read()` maps `cliSessionId` to `originCwd` from `<dataDir>/*/claude-code-sessions/*/*/local_*.json`. Each file is cached by its modification time.
- `ClaudeHomeWatcher(dirs, projectsDir, quiet = 500 ms, maxWait = 700 ms, onChange)` (`ClaudeHomeWatcher.kt`), `Closeable`: calls `onChange` once a burst of file events settles.
- `skillFiles(name, home, cwd): SkillFiles?` and `SkillFiles` (`SkillFiles.kt`): find the definition of a skill or command, plain or `plugin:skill`. The result holds its frontmatter `description` (as a `Result`), the files of the skill folder and its `SkillOrigin`. The function returns `null` for a skill built into `claude`.
- `SkillOrigin` (`SkillOrigin.kt`): where a skill came from, with the installed and latest versions as read from local files. `installs(home)` and `skillOrigin(...)` are internal.

## Invariants

- Transcripts are append-only.
  - A scan reads only the complete, newline-terminated lines after the stored offset (`forEachCompleteLine`).
  - A file shorter than its stored offset is read again from the start.
  - Cache entries for files that no longer exist are dropped (`scan`).
- Each session id appears once in the scan result. The copy with the latest message wins, and among copies with the same message time, the longest one. A session whose `continuedIn` id is also in the result is dropped (`scan`).
- `scan` and `save` are `@Synchronized`. `save` without a `Saved` throws.
- The saved cache is discarded when its `build` hash differs from the current jar's. `forJar` asserts that the jar holds `dev/myair/claude/` classes.
- `toSession` returns `null` for a transcript with no title and no prompt. A titled transcript without a `cwd` or without a message throws.
- The parser requires these fields and throws when one is missing (`Line.check`):
  - in attachments: `additionalWorkingDirectories`, `workingDirectory`, skill-listing `names`/`isInitial`, and `addedNames`/`removedNames` in a deferred-tools delta;
  - in background launches: a shell's or monitor's `command`, an agent's `prompt`, a workflow's `scriptPath`;
  - in task notifications: `task-id` and `summary`;
  - in artifact results: `artifact_id`, `title`, `url`, `path`;
  - on every message: its `timestamp`;
  - on every edit: its file path.
  An unknown task-notification status throws (`BackgroundTask.Status.valueOf`).
- Sidechain and `isMeta` lines are not messages. The model of a `<synthetic>` reply is ignored.
- The brief changes only on a result of the brief tool that is not an error.
- `Brief.input` uses `require` to enforce these limits:
  - the goal is non-blank and at most 200 characters;
  - there are at most 15 tasks, each non-blank and at most 120 characters;
  - at most one task is `in_progress`;
  - `planTask` matches `T[1-9][0-9]*`;
  - the title is non-blank and at most 60 characters.
  An unknown task status, or a `my_air_set_brief` result in an unknown format, throws.
- `mcpServers` asserts that every name has the form `mcp__<server>__<tool>`.
- `LiveSessionRegistry` rules:
  - These fields are required: `kind`, `procStart`, `status`, `entrypoint`, `cwd`, `startedAt`, and `jobId` for `bg`.
  - An unknown `kind` (other than `interactive`, `bg`, `daemon`, `daemon-worker`) throws, and so does an unknown `status` (other than `busy`, `shell`, `waiting`, `idle`).
  - These files are skipped: `spare` workers, daemons, files without `statusUpdatedAt`, files whose pid start time differs from `procStart`, and files deleted while being read.
  - Each session id gets one entry. A job's worker wins; otherwise the last record wins. Parked processes go into `parkedBy`.
- `DesktopSessionReader` asserts that each `cliSessionId` appears in only one Desktop session and that each file has an `originCwd`.
- `skillFiles` asserts that a skill is defined in at most one place. A broken frontmatter is kept as `Result.failure` and does not throw.

## Side Effects

- `ClaudeHomeWatcher` creates a `WatchService` on the given directories, on `projectsDir` and on each of its subdirectories. It registers every new project directory as it appears.
  - It runs a daemon thread named "Claude home watcher" that calls `onChange` on that thread.
  - `close()` closes the service and joins the thread.
- `ClaudeSessionScanner` reads:
  - the directory listing and the file sizes;
  - the transcripts, through a `FileChannel` from the stored offset in 64 KiB chunks;
  - the cache file, lazily on the first scan.
- `ClaudeSessionScanner` writes the cache file through `dev.myair.store.writeAtomically`, at most once every 60 s (`SAVE_INTERVAL_MS`) and on `save()`. `forJar` opens the plugin jar as a zip to hash its classes.
- `LiveSessionRegistry.read` reads `<dir>/*.json`. It queries the operating system for process start times (`ProcessHandle`) and starts no process.
- `DesktopSessionReader.read` lists and parses the Desktop `local_*.json` files.
- `skillFiles` and `SkillOrigin` read these local files and make no network calls:
  - in Claude's home: `skills/`, `commands/`, `plugins/installed_plugins.json`, `plugins/known_marketplaces.json`, `plugins/synced/` and `skills/synced/`;
  - `<cwd>/.claude/` and the plugin install folders, together with their `marketplace.json` and `plugin.json` files;
  - `~/.agents/.skill-lock.json`.

## Verification

- Run: `./gradlew test --tests 'dev.myair.claude.*'`
