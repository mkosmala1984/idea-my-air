# Claude Code Profiles Implementation Plan

## TL;DR

- **Goal:** sessions (and plan/review managers and reviewers) run in a chosen Claude Code profile, a separate `CLAUDE_CONFIG_DIR`; My Air reads all profiles at once.
- **Approach:** a `Profile` model in `~/.my-air/profiles.json`; one scanner, registry and watched projects dir per profile, merged; the profile rides on `ClaudeSession`, `LiveSession`, the tab file, `Preset`, `PlanManager` and `Reviewer`, and turns into `CLAUDE_CONFIG_DIR` for every `claude` it starts.
- **Decisions needing input:** none. Task 1 is a check on this machine that needs one `/login` in a scratch profile from you.
- **Risks:** the IntelliJ terminal may not be able to unset an inherited variable (Task 1 decides: then a start throws when the IDE has `CLAUDE_CONFIG_DIR`); `ClaudeSessionsService` rebuilds its readers when profiles change, a new code path in the busiest class.
- **Out of scope:** as in the spec.

| Task | Status | Commit |
|---|---|---|
| 1 | done | `7f8955b` |
| 2 | done | `b22db37` |
| 3 | done | `eef3cc2` |
| 4 | done | `6e05c95` |
| 5 | done | `b31817f` |
| 6 | done | `4d53c09` |
| 7 | done | `4f4620c` |
| 8 | done | `d4e3387` |
| 9 | done | `4b10ee8` |
| 10 | done | `f3983c0` |
| 11 | done | `0d4b9f7` |

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start Claude Code sessions, plan and review managers and reviewers in separate config directories (profiles) and keep them all visible and resumable in My Air.

**Architecture:** `Profile(name, dir)` with an implicit `default` (`~/.claude`). `ClaudeSessionsService` keeps one `ClaudeSessionScanner` + `LiveSessionRegistry` per profile and merges them, stamping the profile name on each session and live entry. Everything that starts `claude` takes a `Profile` and turns it into an environment map (`profileEnv`).

**Tech Stack:** Kotlin, IntelliJ Platform 263, Gson, JUnit 5 (plain, no platform test framework).

**Spec:** `docs/superpowers/specs/2026-11-20-claude-profiles-design.md`

## Global Constraints

- Code, comments and strings in English.
- `default` is `Path.of(System.getProperty("user.home"), ".claude")` (`Homes.claude`), never in `profiles.json`, and gets no `CLAUDE_CONFIG_DIR`.
- My Air never writes a profile's `.claude.json` or `.credentials.json`; it only reads `.claude.json` (R24, R29).
- Missing fields, unknown keys, unknown profile names throw; no fallback to `default` for a name that is not listed. Absence of the `profile` key in My Air's own files means `default` (older files).
- Profile name: non-blank, not `default`, unique; dir: absolute, unique, not `~/.claude`.
- New profile directory default: `~/.my-air/claude-profiles/<name>`.
- `claude -p` helpers (branch names, dashboard analysis) always run in `default`.

## Review Focus

- A profile added but never started: its `projects/` and `sessions/` do not exist yet. Expected: Add creates both empty dirs, so scanner, registry and watcher see an empty profile; a listed profile whose dirs are missing throws naming the dir (Task 3 test `a profile without its sessions dir throws naming it`).
- A profile name with spaces or `&`/`=` in the tab URL. Expected: round-trips (Task 4 test `a profile name with spaces and ampersands round-trips`).
- `/clear` in a profile session keeps the pid and rewrites `sessionId` in that profile's `sessions/`. Expected: merged registry keyed by session id still finds it by pid (Task 3 test `merged live entries keep pid and profile`).
- A profile removed from `profiles.json` by hand while a plan manager names it. Expected: plan tab shows "Profile <name> is gone", nothing starts in `default` (Task 9 test `a manager of an unlisted profile is refused`).
- The IDE itself launched with `CLAUDE_CONFIG_DIR`. Expected: `default` sessions do not inherit it (Task 4 test `default profile env clears CLAUDE_CONFIG_DIR`; behaviour per Task 1's finding).

---

### Task 1: Check Claude Code's behaviour with `CLAUDE_CONFIG_DIR` on this machine

No code. Records facts the later tasks build on; a finding that contradicts the spec stops the plan.

**Files:**
- Modify: this plan, section "Task 1 findings" (append at the end)

- [ ] **Step 1:** Create `C:\Users\kosma\AppData\Local\Temp\claude-profile-check` with empty `projects` and `sessions` subdirs. In PowerShell: `$env:CLAUDE_CONFIG_DIR='<that dir>'; claude` in `E:\dev\projects\personal\my-air`. Ask the user to go through onboarding and `/login`, send one message, then `/exit`.
- [ ] **Step 2:** Record where these landed: `sessions/<pid>.json` (while running), `projects/<encoded cwd>/<id>.jsonl`, `.claude.json`, `.credentials.json`. Expected: all under the scratch dir, none new in `~/.claude`.
- [ ] **Step 3:** With the same env: `claude --bg "say hi"`, then `claude attach <job>` and `claude stop <job>`. Expected: the job's registry file is in the scratch `sessions/` with `kind: bg`; `claude stop <job>` without the env fails to find it.
- [ ] **Step 4:** Find out whether `ShellStartupOptions.envVariables` is merged into the IDE's environment or replaces it, and whether a key can be removed (read the terminal frontend classes with `javap`, as `docs/air-plugin-analysis.md` describes, or test in `runIde` with `CLAUDE_CONFIG_DIR` set before launching Gradle). Record which: (a) removable → `profileEnv` removes it; (b) not removable → `SessionTerminals.start` throws `IllegalStateException("The IDE runs with CLAUDE_CONFIG_DIR=<v>; My Air's default profile is ~/.claude")` for `default` when the IDE has it.
- [ ] **Step 5:** Delete the scratch dir. Commit the findings.

```bash
git add docs/superpowers/plans/2026-11-21-claude-profiles.md
git commit -m "Plan: profiles, Task 1 findings"
```

### Task 2: Profile model and store

**Files:**
- Create: `src/main/kotlin/dev/myair/store/Profiles.kt`
- Test: `src/test/kotlin/dev/myair/store/ProfilesTest.kt`

**Interfaces:**
- Produces:
  - `data class Profile(val name: String, val dir: Path)`; `val Profile.isDefault: Boolean`
  - `const val DEFAULT_PROFILE = "default"`
  - `fun parseProfiles(text: String, defaultDir: Path): List<Profile>` (file's profiles only, validated)
  - `fun profilesJson(profiles: List<Profile>): String` (`{"version":1,"profiles":[{"name":..,"dir":..}]}`)
  - `class ProfileStore(val file: Path, val defaultDir: Path)` with `fun all(): List<Profile>` (default first, cached by mtime as `PresetStore` does), `fun byName(name: String): Profile` (throws `IllegalArgumentException("no profile '<name>'; profiles: <names>")`), `fun write(profiles: List<Profile>)` (atomic, `writeAtomically`), and `companion fun getInstance(): ProfileStore` over `Homes.myAir.resolve("profiles.json")`, `Homes.claude`.
  - `fun profileEnv(profile: Profile): Map<String, String?>`: `CLAUDE_CONFIG_DIR` to the dir, or to null for `default` (null = remove).

- [ ] **Step 1: Write the failing tests** in `ProfilesTest`:
  - `the file's profiles follow default` — `ProfileStore` over a temp file with one profile `test` → `all().map { it.name } == listOf("default", "test")`, `all().first().dir == defaultDir`.
  - `a missing file means only default`.
  - `rejects` cases, each `assertThrows<IllegalArgumentException>` on `parseProfiles`: blank name; name `default`; repeated name; relative dir `rel/x`; repeated dir; dir equal to `defaultDir`; unknown key `"colour"` in a profile; version other than 1.
  - `json round-trips` — `parseProfiles(profilesJson(list), d) == list`.
  - `byName of an unlisted profile names the known ones` — message contains `no profile 'x'` and `default`.
  - `default profile env clears CLAUDE_CONFIG_DIR` — `profileEnv(default) == mapOf("CLAUDE_CONFIG_DIR" to null)`; a profile at `C:\p` gives `mapOf("CLAUDE_CONFIG_DIR" to "C:\\p")`.
- [ ] **Step 2:** Run `./gradlew test --tests dev.myair.store.ProfilesTest`. Expected: FAIL, unresolved references.
- [ ] **Step 3:** Implement `Profiles.kt` with Gson, as `Presets.kt` parses.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "Profiles: model and store"` with trailers `Plan-Task: 2/11` and `Plan: docs/superpowers/plans/2026-11-21-claude-profiles.md`.

### Task 3: Read every profile

**Files:**
- Modify: `src/main/kotlin/dev/myair/claude/ClaudeSession.kt:23-29` (field), `claude/LiveSessionRegistry.kt:28-31` (field), `claude/ClaudeSessionScanner.kt:100-108` (`forJar`), `claude/ClaudeHomeWatcher.kt:23-36,60-66`, `service/UnscannedSessions.kt:10-15`, `service/ClaudeSessionsService.kt:48-51,119,134-147,256-257`
- Create: `src/main/kotlin/dev/myair/service/ProfileReads.kt`
- Test: `src/test/kotlin/dev/myair/service/ProfileReadsTest.kt`, `claude/ClaudeSessionScannerTest.kt`, `claude/ClaudeHomeWatcherTest.kt`, `service/UnscannedSessionsTest.kt` (create if missing)

**Interfaces:**
- Consumes: `Profile`, `ProfileStore.all()`, `DEFAULT_PROFILE`.
- Produces:
  - `ClaudeSession.profile: String = DEFAULT_PROFILE`, `LiveSession.profile: String = DEFAULT_PROFILE`
  - `fun mergeSessions(byProfile: Map<Profile, List<ClaudeSession>>): List<ClaudeSession>` — stamps `profile`, throws `IllegalStateException("session <id> is in <dirA> and <dirB>")` on a repeated id.
  - `fun mergeLive(byProfile: Map<Profile, Map<String, LiveSession>>): Map<String, LiveSession>` — stamps, throws the same way.
  - `fun requireProfileDirs(profile: Profile)` — throws naming `<dir>/projects` or `<dir>/sessions` when missing.
  - `Saved.forJar(dir: Path, jar: Path, profile: Profile): Saved` — `default`: `scan-cache-<jar8>.json` unchanged; else `scan-cache-<jar8>-<8 hex of sha256(profile dir)>.json`.
  - `ClaudeHomeWatcher(dirs: List<Path>, projectsDirs: List<Path>, ...)`.
  - `ClaudeSessionsService.profileHome(name: String): Path` for later tasks (the listed profile's dir; throws when unlisted).

- [ ] **Step 1: Write the failing tests:**
  - `ProfileReadsTest › sessions get their profile` — two profiles, one session each → profiles `default`, `test`.
  - `ProfileReadsTest › one id in two profiles throws naming both dirs`.
  - `ProfileReadsTest › merged live entries keep pid and profile` — entry pid 42 in `test` → `merged["s"]!!.pid == 42L`, `.profile == "test"`.
  - `ProfileReadsTest › a profile without its sessions dir throws naming it`.
  - `ClaudeSessionScannerTest › the default profile keeps its cache file name` and `› another profile gets its own cache file` (names as above; the jar fixture the existing tests use).
  - `ClaudeHomeWatcherTest › a transcript in a second projects dir triggers a change` (follow the existing timing test).
  - `UnscannedSessionsTest › an unscanned session keeps its live entry's profile`.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.service.*' --tests 'dev.myair.claude.*'`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement. In `ClaudeSessionsService`, replace `scanner`/`registry`/`watcher` with a `Readers(profiles: List<Profile>)` holder (scanners, registries, watcher over every profile's `sessions` + `~/.my-air` + seen dir and every profile's `projects`), rebuilt at the start of `refresh()` when `ProfileStore.all()` differs from the last one (closing the old watcher, saving the old scanners). `~/.my-air` is already watched, so editing `profiles.json` triggers that refresh. Desktop sessions go only to the `default` scanner (`scan(desktop.read())`, others `scan()`). `dispose` saves every scanner.
- [ ] **Step 4:** Run the same command, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `"Profiles: read sessions and registry of every profile"`, `Plan-Task: 3/11`.

### Task 4: Start, resume and follow a session in its profile

**Files:**
- Modify: `editor/ClaudeSessionVirtualFile.kt:36-44,71`, `editor/SessionTabUrl.kt` (whole), `editor/ClaudeSessionFileSystem.kt:16,27`, `editor/ClaudeSessionOpener.kt:61,80-90`, `editor/SessionTerminals.kt:212-240`, `editor/SessionPane.kt:106`, `editor/CloseAndSettleAction.kt:39-40`, `actions/ClaudePrint.kt:21-26`
- Test: `src/test/kotlin/dev/myair/editor/SessionTabUrlTest.kt`, `actions/ClaudePrintTest.kt` (create if missing)

**Interfaces:**
- Consumes: `Profile`, `profileEnv`, `ProfileStore.byName`, `ClaudeSession.profile`, `LiveSession.profile`.
- Produces:
  - `ClaudeSessionVirtualFile(..., val profile: Profile, ...)` as a required parameter after `project`.
  - `data class SessionTabRef(sessionId, cwd, project, profile: String)`; `sessionTabPath(sessionId, cwd, project, profile: String)` adds `&profile=<enc>` only when not `default`; `parseSessionTabPath` accepts exactly `{cwd, project}` or `{cwd, project, profile}`.
  - `restartArgs(sessionId: String, home: Path)` / `hasTranscript(sessionId, home)` unchanged in shape, called with the profile's dir.
  - `ClaudeSessionOpener.startNew(..., profile: Profile = ProfileStore.getInstance().byName(DEFAULT_PROFILE))`.
  - `runClaude(args, dir, input, timeout, env: Map<String, String?> = profileEnv(default))`: applies `env` to the `ProcessBuilder` (null removes).

- [ ] **Step 1: Write the failing tests:**
  - `SessionTabUrlTest › a default session's path has no profile key` — `sessionTabPath(...,"default")` has no `profile=`.
  - `SessionTabUrlTest › a profile name with spaces and ampersands round-trips` — `"a b&c=d"`.
  - `SessionTabUrlTest › a path without a profile reads as default`.
  - `SessionTabUrlTest › an unknown key throws`.
  - `ClaudePrintTest › a null env value removes the variable` — runs `cmd /c set CLAUDE_CONFIG_DIR` through the `ProcessBuilder` setup extracted as `fun processBuilder(args, dir, env): ProcessBuilder` with `CLAUDE_CONFIG_DIR` present in the parent env replaced by an explicit value then by null; asserts `environment()` lacks the key.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.editor.SessionTabUrlTest' --tests 'dev.myair.actions.ClaudePrintTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Every `ClaudeSessionVirtualFile(` call passes a profile: `ClaudeSessionOpener.open` the session's (`byName(session.profile)`), restore (`ClaudeSessionFileSystem`) the URL's; a restored tab whose profile is not listed opens with a file whose start shows `"Profile <name> is gone"` in the pane where the directory-gone message shows today, and does not start. `SessionTerminals.start` merges `profileEnv(file.profile)` into `envVariables` per Task 1's finding (removal, or the `default` throw). The registry reads at `SessionTerminals.kt:218`, `SessionPane.kt:106` and `CloseAndSettleAction.kt:39` read `<file.profile.dir>/sessions`; `claude stop` gets `profileEnv(file.profile)` (`GeneralCommandLine.withEnvironment`, removing via `withParentEnvironmentType` + explicit map as Task 1 found). Branch names (`BranchNames.kt:40`) and analysis (`Analyzer.kt:58`) keep the default `env` (R21).
- [ ] **Step 4:** Run the tests, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `"Profiles: sessions start, resume, attach and stop in their profile"`, `Plan-Task: 4/11`.

### Task 5: Profile in configurations, follow-on starts, MCP and scheduled tasks

**Files:**
- Modify: `store/Presets.kt:36-57,81,96-108`, `actions/PresetActions.kt:40-63`, `editor/ClaudeSessionEditor.kt:87-88`, `mcp/SessionStart.kt:26-39`, `mcp/FoldersMcpToolset.kt:64-110` (+ the sessions JSON function it uses), `schedule/ScheduledRuns.kt:150-165`, `schedule/RunProject.kt:15-21`, `schedule/ScheduledTasks.kt` (parser keys)
- Test: `store/PresetsTest.kt`, `mcp/SessionStartTest.kt`, `schedule/RunProjectTest.kt`, the existing MCP JSON test of `my_air_sessions_find`

**Interfaces:**
- Consumes: `ProfileStore.byName`, `startNew(..., profile)`.
- Produces: `Preset.profile: String? = null` (written only when set; parsed key `profile`); `presetOf(session, name)` sets `profile = session.profile.takeIf { it != DEFAULT_PROFILE }`; `sessionStartPreset(..., profile: String?)`; `preStartError(task, dir, folderExists, claudeFound, profileListed: Boolean)`; `continuationPrompt(session)` names `session.transcript`.

- [ ] **Step 1: Write the failing tests:**
  - `PresetsTest › a preset's profile round-trips and is left out when null`.
  - `PresetsTest › presetOf keeps a non-default profile`; `› presetOf of a default session has no profile`.
  - `PresetsTest › continuationPrompt names the transcript path` — session with `transcript = Path.of("C:/p/projects/x/s.jsonl")` → prompt contains that path, not `~/.claude`.
  - `SessionStartTest › profile overrides the configuration's`.
  - `RunProjectTest › an unlisted profile stops the run` → `"profile <name> is no longer listed"`.
  - MCP JSON test: a session with profile `test` has `"profile":"test"`; a default one has no `profile` key.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.store.PresetsTest' --tests 'dev.myair.mcp.*' --tests 'dev.myair.schedule.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. `startPreset` resolves `preset.profile ?: DEFAULT_PROFILE` with `byName` before anything else (an unlisted name: error dialog with the message, nothing started) and passes it to `startNew`/`startAsPlan`. `continueSession` and `newSessionHere` pass `byName(session.profile)`. `my_air_session_start` gets `@McpDescription("Profile name, a Claude Code config directory listed in My Air's settings; default: default.") profile: String? = null`; an unknown name fails back to the agent through `byName`'s message. `my_air_sessions_find` adds `profile` when not default; `ClaudeSession.matches` adds `profile` to its fields when not default (R19). `ScheduledRuns` builds its file with `byName(task.preset.profile ?: DEFAULT_PROFILE)` after `preStartError` passed.
- [ ] **Step 4:** Run, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `"Profiles: configurations, follow-on starts, MCP and scheduled tasks"`, `Plan-Task: 5/11`.

### Task 6: Profile choosers in the composer and the configuration dialog

**Files:**
- Modify: `prompt/PromptChoices.kt:20-40` (+ `Draft` and last-start storage in the same file), `prompt/PromptPanel.kt` (chip after effort), `actions/PresetSettings.kt` (`PresetDialog` field)
- Test: `prompt/PromptChoicesTest.kt`

**Interfaces:**
- Consumes: `ProfileStore.all()`, `Preset.profile`.
- Produces: `Choice.profile: String = DEFAULT_PROFILE`; `toPreset` sets `profile = profile.takeIf { it != DEFAULT_PROFILE }`; choosing a configuration resets it to the configuration's.

- [ ] **Step 1: Write the failing tests:** `PromptChoicesTest › toPreset carries the chosen profile`; `› a draft keeps its profile`; `› choosing a configuration takes its profile`.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.prompt.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the `Choice` field and storage, then the chip (items: `Default`, then `ProfileStore.all().drop(1)` names; built like the effort chip) and a combo in `PresetDialog` (`Default` = null).
- [ ] **Step 4:** Run, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5:** `runIde` (command in CLAUDE.md): with one profile listed, the chip lists it and a session started with it shows up in the tree. Record as `checked:`.
- [ ] **Step 6: Commit** `"Profiles: chooser in the composer and configuration dialog"`, `Plan-Task: 6/11`.

### Task 7: Show the profile and read skills from it

**Files:**
- Modify: `toolwindow/SessionRenderers.kt:63` (`tooltipLines`), `editor/TabMark.kt:24` (`tabTooltip`), `editor/SessionHeader.kt:23` (`headerText`), `toolwindow/SessionContextPanel.kt` (chip beside model/effort; `skillFiles` call at `:512` with the session's profile dir), `toolwindow/SessionContext.kt` if the chip's data lives there
- Test: `toolwindow/SessionRenderersTest.kt`, `editor/SessionHeaderTest.kt`, `editor/TabMarkTest.kt`

**Interfaces:**
- Consumes: `ClaudeSession.profile`, `ClaudeSessionsService.profileHome`.
- Produces: `tabTooltip(title, sessionProject, worktree, profile: String)`; tooltip line `"Profile: <name>"`; `HeaderText` gains `profile: String?` (null for default).

- [ ] **Step 1: Write the failing tests:** `tooltip names a non-default profile`, `tooltip of a default session has no profile line`, `header carries the profile`, `tab tooltip names the profile`.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.toolwindow.*' --tests 'dev.myair.editor.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement; the Session Context chip uses the existing chip component, text = profile name, only when not default.
- [ ] **Step 4:** Run, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `"Profiles: shown in tooltips, header and Session Context; skills read from the profile"`, `Plan-Task: 7/11`.

### Task 8: Profiles in Settings

**Files:**
- Create: `src/main/kotlin/dev/myair/actions/ProfileSettings.kt` (list editor, Add/Remove, as `PresetsEditor` in `PresetSettings.kt`)
- Create: `src/main/kotlin/dev/myair/store/ProfileChecks.kt`
- Modify: `MyAirSettings.kt:74` (`MyAirConfigurable` adds the Profiles group)
- Test: `src/test/kotlin/dev/myair/store/ProfileChecksTest.kt`

**Interfaces:**
- Consumes: `ProfileStore`, `ClaudeSessionsService.live`, `SessionHosts.openTabs`, `PlanManagerStore`, `ReviewProfileStore` and review files (read for R30, fields added in Tasks 9–10; until then only R23 is checked and Task 9/10 extend `removeRefusal`).
- Produces:
  - `fun ideServer(claudeJson: Path): Pair<String, String>?` — the `idea` entry of `mcpServers` with `type == "http"`: name and url; null when absent; unreadable file throws.
  - `fun mcpAddCommand(profile: Profile, server: Pair<String, String>): String` — exactly `$env:CLAUDE_CONFIG_DIR='<dir>'; claude mcp add --scope user --transport http <name> <url>` (PowerShell, as the user's shell).
  - `fun removeRefusal(profile: String, live: Collection<LiveSession>, tabProfiles: Collection<String>, managerProfiles: Collection<String>, reviewerProfiles: Collection<String>): String?` — the first reason, naming one session id / plan / reviewer.
  - `fun createProfileDirs(profile: Profile)` — creates `dir`, `dir/projects`, `dir/sessions`.

- [ ] **Step 1: Write the failing tests:** `ProfileChecksTest › ideServer reads the http idea entry`; `› ideServer is null without one`; `› mcpAddCommand` (exact string for `test`, `C:\p`, `idea`, `http://127.0.0.1:64342/stream`); `› remove is refused while a session of the profile is live`; `› remove is refused while a tab shows it`; `› remove is allowed otherwise`; `› createProfileDirs makes projects and sessions`.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.store.ProfileChecksTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement the checks, then the Settings group: list of `name — dir`, Add (dialog: name, dir prefilled `~/.my-air/claude-profiles/<name>`; `createProfileDirs`, `write`, then `ClaudeSessionOpener.startNew` with that profile in the open project, then a dialog showing `mcpAddCommand` in a selectable text field, or "No http MCP server named idea in ~/.claude.json" when `ideServer` is null), Remove (shows `removeRefusal` and does nothing, else writes the list without it; the directory is kept), Open Folder.
- [ ] **Step 4:** Run, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5:** `runIde`: add a profile, log in through the opened tab, run the shown command in a terminal, start a session in the profile, see it in the tree with its status; Remove is refused while it runs. Record as `checked:`.
- [ ] **Step 6: Commit** `"Profiles: Settings list with Add, Remove and the MCP command"`, `Plan-Task: 8/11`.

### Task 9: Plan and review managers in a profile

**Files:**
- Modify: `store/PlanManagers.kt:11,24-39`, `store/Reviews.kt:124,212`, `planview/PlanFiles.kt:42-45,88-101,111-126`, `planview/FolderTabs.kt:83-86`, `reviewview/ReviewFiles.kt:41-44,57-68`, `planview/NewPlan.kt` (`startAsPlan` takes the profile), `planview/TurnIntoPlan.kt`, `mcp/FoldersMcpToolset.kt:252` (set_manager), `reviewview/NewReviewAction.kt` (+ its dialog/popup), the plan/review tab's pre-start check (where a missing directory is shown today), `store/ProfileChecks.kt` (extend `removeRefusal` callers with manager profiles)
- Test: `store/PlanManagersTest.kt`, `store/ReviewsTest.kt`, `planview/PlanFilesTest.kt`, `reviewview/ReviewFilesTest.kt`, `store/ProfileChecksTest.kt`

**Interfaces:**
- Consumes: `ProfileStore.byName`, `ideServer`, `profileEnv`.
- Produces:
  - `PlanManager(session, cwd, project, profile: String = DEFAULT_PROFILE)`; JSON key `profile` written only when not default, read when present (both `plan-managers.json` and `review.json`'s `manager`).
  - `managerStart(existing, newId, cwd, project, profile: String, hasTranscript: (String, Profile) -> Boolean)` — a new manager gets `profile`.
  - `fun managerToolsRefusal(profile: Profile, defaultClaudeJson: Path): String?` in `ProfileChecks.kt` — null for default; else null when the profile's `.claude.json` holds an `http` entry with the url of `ideServer(defaultClaudeJson)`; else `"Profile <name> has no My Air MCP server. Run:\n<mcpAddCommand>"`; a profile without `.claude.json` is refused the same way.
  - `PlanFiles.adopt(folderId, manager, running, firstArgs)` stores `manager.profile` = the adopted session's.

- [ ] **Step 1: Write the failing tests:**
  - `PlanManagersTest › profile round-trips and default is left out`; `› a file without profile reads as default`.
  - `ReviewsTest › a review manager's profile round-trips`.
  - `PlanFilesTest › a new manager takes the given profile`; `› an existing manager keeps its profile and checks its transcript there`.
  - `ProfileChecksTest › managerToolsRefusal is null for default`; `› refuses a profile without the server`; `› accepts a profile with the same url under another name`; `› remove is refused while a plan manager names it`.
  - `PlanFilesTest › a manager of an unlisted profile is refused` (the pure part: resolving the stored name throws `byName`'s message).
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.store.*' --tests 'dev.myair.planview.*' --tests 'dev.myair.reviewview.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. `startManager` (plan and review) resolves the stored profile with `byName` and runs `managerToolsRefusal` before building the file; either failure shows in the tab where a missing directory shows today and starts nothing. `startAsPlan` gets the composer's profile; Turn into Plan and `my_air_plan_set_manager` store the adopted session's profile; a new manager from Open Plan is `default`. New Review... gets a Profile choice (default `default`) stored on the review's new manager.
- [ ] **Step 4:** Run, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `"Profiles: plan and review managers run in their profile"`, `Plan-Task: 9/11`.

### Task 10: Reviewers in a profile

**Files:**
- Modify: `store/ReviewProfiles.kt:12-30`, `review/Reviewers.kt`, `reviewview/ReviewPanel.kt:335-345`, `store/ProfileChecks.kt` callers (reviewer profiles for R30)
- Test: `store/ReviewProfilesTest.kt`, `store/ProfileChecksTest.kt`

**Interfaces:**
- Consumes: `ProfileStore.byName`, `managerToolsRefusal`, `runClaude(..., env)`.
- Produces: `Reviewer(name, prompt, model, effort, profile: String? = null)` (key `profile`); `Reviewers.start(dir: Path, args: List<String>, profile: Profile): String` runs `runClaude(..., env = profileEnv(profile))`.

- [ ] **Step 1: Write the failing tests:** `ReviewProfilesTest › a reviewer's profile is read`; `› a reviewer without profile is default`; `ProfileChecksTest › remove is refused while a reviewer names it`.
- [ ] **Step 2:** Run `./gradlew test --tests 'dev.myair.store.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. In `ReviewPanel`'s run start, off the EDT, resolve the reviewer's profile with `byName` and `managerToolsRefusal` before `Reviewers.start`; a failure becomes the run's `Result.failure` with that message, so it shows as `runProblem` reasons do. `learnSessions` needs nothing: the registry is merged (Task 3).
- [ ] **Step 4:** Run, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5:** `runIde`: a review profile with one reviewer in the test profile runs, its session shows under the review folder, it reports. Record as `checked:`.
- [ ] **Step 6: Commit** `"Profiles: reviewers run in their profile"`, `Plan-Task: 10/11`.

### Task 11: Docs and full verification

**Files:**
- Modify: `CLAUDE.md` ("Where the data comes from": profiles; "How the pieces fit": `Profile`, `profileEnv`, readers per profile), `src/main/kotlin/dev/myair/README.md`, `claude/README.md`, `editor/README.md`, `service/README.md` where they name `~/.claude` as the only home, `src/main/resources/META-INF/plugin.xml:5` description.

- [ ] **Step 1:** Update the docs.
- [ ] **Step 2:** Run `./gradlew test buildPlugin`. Expected: BUILD SUCCESSFUL, `build/distributions/my-air-0.1.0.zip`.
- [ ] **Step 3:** Restart `runIde` on the new build: a tab of a profile session survives an IDE restart and resumes in its profile (`--resume`, transcript continues in the profile's `projects/`). Record as `checked:`.
- [ ] **Step 4:** Fill the Requirements evidence table and the status table.
- [ ] **Step 5: Commit** `"Profiles: docs"`, `Plan-Task: 11/11`.

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 2 | test `ProfilesTest` › `the file's profiles follow default`; test `ProfilesTest` › `rejects bad files`; commit b22db37 |
| R2 | 8 | test `ProfileChecksTest` › `createProfileDirs makes projects and sessions`; commit 77289f4; not done: runIde check needs the user |
| R3 | 8 | commit 77289f4; not done: runIde check needs the user |
| R4 | 3 | test `ProfileReadsTest` › `sessions get their profile`; test `ProfileReadsTest` › `merged live entries keep pid and profile`; commit eef3cc2 |
| R5 | 3 | test `ProfileReadsTest` › `one id in two profiles throws naming both dirs`; commit eef3cc2 |
| R6 | 3 | test `ClaudeHomeWatcherTest` › `a transcript in a second projects dir triggers a change`; commit eef3cc2 |
| R7 | 3 | test `ClaudeSessionScannerTest` › `the default profile keeps its cache file name`; test `ClaudeSessionScannerTest` › `another profile gets its own cache file`; commit eef3cc2 |
| R8 | 1, 4 | test `ProfilesTest` › `default profile env clears CLAUDE_CONFIG_DIR`; test `ProfilesTest` › `an added env sets a profile's dir and refuses default while the IDE has CLAUDE_CONFIG_DIR`; test `ClaudePrintTest` › `a null env value removes the variable`; checked: terminal env merge read from terminal.jar bytecode (Task 1); commit 6e05c95 |
| R9 | 4 | test `SessionTabUrlTest` › `a default session's path has no profile key`; test `SessionTabUrlTest` › `a path without a profile reads as default`; test `SessionTabUrlTest` › `a profile name with spaces and ampersands round-trips`; commit 6e05c95 |
| R10 | 4 | test `SessionTabUrlTest` › `a session with a transcript resumes, one closed before its first message starts again under its id`; commit 6e05c95 |
| R11 | 1, 4 | checked: terminal env merge read from terminal.jar bytecode (Task 1); commit 6e05c95 |
| R12 | 5, 6 | test `PresetsTest` › `a preset's profile round-trips and is left out when null`; test `RunProjectTest` › `an unlisted profile stops the run`; commit b31817f |
| R13 | 6 | test `PromptChoicesTest` › `toPreset carries the chosen profile`; test `PromptChoicesTest` › `a draft keeps its profile`; test `PromptChoicesTest` › `choosing a configuration takes its profile`; commit 0b194a4; not done: runIde check needs the user |
| R14 | 5 | test `PresetsTest` › `presetOf keeps a non-default profile`; test `PresetsTest` › `presetOf of a default session has no profile`; commit b31817f |
| R15 | 5 | test `FolderQueriesTest` › `the json names a session's profile unless it is default`; test `SessionStartTest` › `profile overrides the configuration's`; commit b31817f |
| R16 | 5 | test `RunProjectTest` › `an unlisted profile stops the run`; commit b31817f |
| R17 | 7 | commit 4f4620c |
| R18 | 7 | test `BriefTextTest` › `tooltip names a non-default profile`; test `BriefTextTest` › `tooltip of a default session has no profile line`; test `TabTitleTest` › `tab tooltip names the profile`; test `SessionHeaderTest` › `header carries the profile`; commit 4f4620c; not done: runIde check needs the user |
| R19 | 5 | test `ClaudeSessionTest` › `a session is found by its non-default profile name and a default one is not found by default`; commit b31817f |
| R20 | 5 | test `PresetsTest` › `continuationPrompt names the transcript path`; commit b31817f |
| R21 | 4 | test `ClaudePrintTest` › `a null env value removes the variable`; test `ProfilesTest` › `default does not read the file`; commit `6e05c95`; commit `b31817f` |
| R22 | 9 | test `PlanManagersTest` › `profile round-trips and default is left out`; test `PlanManagersTest` › `a file without profile reads as default`; test `ReviewsTest` › `a review manager's profile round-trips`; test `PlanFilesTest` › `an existing manager keeps its profile and checks its transcript there`; commit 4b10ee8 |
| R23 | 8 | test `ProfileChecksTest` › `remove is refused while a session of the profile is live`; test `ProfileChecksTest` › `remove is refused while a tab shows it`; commit 77289f4 |
| R24 | 8 | test `ProfileChecksTest` › `remove is allowed otherwise`; commit 77289f4 |
| R25 | 4 | test `ProfileChecksTest` › `mcpAddCommand refuses a quote in the directory`; commit 6e05c95 |
| R26 | 9 | test `PlanFilesTest` › `a new manager takes the given profile`; commit 4b10ee8 |
| R27 | 9 | commit 4b10ee8 |
| R28 | 10 | test `ReviewProfilesTest` › `a reviewer's profile is read`; test `ReviewProfilesTest` › `a reviewer without profile is default`; commit f3983c0 |
| R29 | 9, 10 | test `ProfileChecksTest` › `refuses a profile without the server`; test `ProfileChecksTest` › `accepts a profile with the same url under another name`; test `ProfileChecksTest` › `managerToolsRefusal is null for default`; commit 4b10ee8 |
| R30 | 8, 9, 10 | test `ProfileChecksTest` › `remove is refused while a plan manager names it`; test `ProfileChecksTest` › `remove is refused while a reviewer names it`; commit f3983c0 |

## Task 1 findings

- Steps 1-3 (on-disk layout, `--bg`/`attach`/`stop` per config dir) deferred to the runIde checks of Tasks 8 and 11, where the user logs into a profile; the docs (claude-directory, authentication, agent-view) are what Tasks 2-10 build on.
- Step 4, terminal: `ShellStartupOptions.envVariables` is merged over the IDE's environment (`LocalOptionsConfigurer.getTerminalEnvironment`: parent env when `isPassParentEnvs`, then ours, then Settings › Terminal env vars, which win); only `put`/`putAll`, no removal. So outcome **(b)**: a `default` start throws when the IDE has `CLAUDE_CONFIG_DIR`; a profile start puts its dir.
- Step 4, `GeneralCommandLine` (`claude stop`): `getEnvironment()` holds overrides only; removing a parent variable needs `withParentEnvironmentType(NONE)` plus `System.getenv()` without the key. `ProcessBuilder` (`runClaude`) removes with `environment().remove(key)`.
