# Claude Code profiles: separate config directories per session

## TL;DR

- **Goal:** start a session in a *profile*: a separate Claude Code config directory (`CLAUDE_CONFIG_DIR`) instead of `~/.claude`, to try other skills, hooks, plugins and `CLAUDE.md` without memory, on the same account.
- **Approach (A, full isolation):** each profile is its own config directory with its own `projects/` and `sessions/`; My Air reads every profile's transcripts and registry, and a session's profile is the directory its files lie in. Every `claude` process of a session (start, `--resume`, `attach`, `stop`) gets that profile's `CLAUDE_CONFIG_DIR`.
- **Setup:** a new profile is an empty directory; the user logs in once per profile through Claude Code's own first-run flow (`/login`), which My Air opens for them.
- **Chosen by:** a Profile chip in the composer, a `profile` field of configurations (and so scheduled tasks), `my_air_session_start`'s `profile`; follow-on starts keep the session's profile.
- **My Air tools in a profile:** the user adds the IDE's MCP server to each profile by hand (`claude mcp add`); My Air shows the command after Add and changes nothing in the profile. Sessions start with the same system prompt, brief hook and plan prompt as in `default`.
- **Managers and reviewers:** a plan's or review's manager and each reviewer may run in any profile; it is stored with them. Since they are useless without My Air's tools, their start is refused in a profile whose `.claude.json` lacks the IDE's MCP server.
- **Decisions needing input:** none open.
- **Risks:** first start in each profile shows Claude Code's onboarding and each project's trust question once (not skippable by a documented key); scan cost grows with the number of profiles.
- **Out of scope:** sharing a login between profiles, copying parts of `~/.claude` into a profile, Claude Desktop sessions outside `default`.

## Requirements

- **R1** `~/.my-air/profiles.json` lists profiles as `{name, dir}`; `default` is `~/.claude`, implicit and never in the file; a name that is blank, repeated or `default`, a relative `dir`, a `dir` repeated or equal to `~/.claude`, or an unknown key makes the read throw.
- **R2** Settings › Tools › My Air › Profiles adds a profile (name; directory, default `~/.my-air/claude-profiles/<name>`, created when missing) and removes one from the list without deleting its directory.
- **R3** After Add, My Air opens a new session tab of that profile in the IDE project's directory, so Claude Code's first-run flow (theme, login) runs there; the new profile's directory then holds `.credentials.json` and `.claude.json` written by `claude`, not by My Air.
- **R4** `ClaudeSessionsService` scans `projects/` and reads `sessions/` of every profile; a session read from profile P has `ClaudeSession.profile == P`, a live entry `LiveSession.profile == P`.
- **R5** One session id found in two profiles makes the refresh throw, naming both directories.
- **R6** The watcher covers `sessions/` and `projects/*` of every profile; a change of `profiles.json` rebuilds it and triggers a refresh.
- **R7** Each profile has its own scan cache file; `default` keeps today's file name, so upgrading does not rescan.
- **R8** A session tab's process gets `CLAUDE_CONFIG_DIR=<dir>` when its profile is not `default` and no `CLAUDE_CONFIG_DIR` when it is. The terminal cannot remove a variable the IDE has, so a `default` start throws while the IDE runs with `CLAUDE_CONFIG_DIR`; `claude stop` and `runClaude` (`claude -p`, reviewers) remove it instead.
- **R9** The tab URL carries `profile=<name>` for a non-default profile; a URL without it restores as `default`; a restored tab whose profile is no longer listed shows "Profile <name> is gone" and does not start.
- **R10** `--resume` vs `--session-id` (`restartArgs`, `hasTranscript`) looks only in the session's profile.
- **R11** `claude attach <job>`, `claude stop <job>` and every registry lookup of a job use the profile of the session the tab shows.
- **R12** `Preset` has an optional `profile` (name); `PresetDialog` edits it; a preset naming a profile not listed fails the start with that name.
- **R13** The composer has a Profile chip (Default, then listed profiles); a configuration sets it, the user may change it, it is kept in the last-start choices and in a draft.
- **R14** New Session Here, Continue Session, New Session with Same Settings and `presetOf(session)` carry the session's profile.
- **R15** `my_air_session_start` takes `profile` (a name; unknown fails back to the agent); `my_air_sessions_find` gives `profile` for a session not in `default`.
- **R16** A scheduled task runs in its preset's profile; `preStartError` names a profile no longer listed.
- **R17** Session Context reads skills and their origin (`skillFiles`, `SkillOrigin`) from the session's profile directory.
- **R18** A session of a non-default profile shows its profile name in the tree row's tooltip, the tab tooltip, the session header and a chip beside model and effort in Session Context; `default` shows nothing.
- **R19** Search (`ClaudeSession.matches`, `my_air_sessions_find`'s `query`) also matches the profile name.
- **R20** `continuationPrompt` names the transcript's real path (`ClaudeSession.transcript`), not `~/.claude/projects/*`.
- **R22** `PlanManager` (`plan-managers.json`, so also a review's `manager` in `review.json`) has a `profile` (name, left out of the file for `default`, so older files read as `default`); a manager starts and resumes with that profile's `CLAUDE_CONFIG_DIR`, and `hasTranscript` looks in it.
- **R26** A new plan's manager takes its profile from where it is started: the composer's Profile chip (`startAsPlan`), the adopted session's profile (Turn into Plan, `my_air_plan_set_manager`); an Open Plan with no manager yet starts it in `default`.
- **R27** New Review... has a Profile choice (default `default`) for the review's manager; a review's manager stored before this change is `default`.
- **R28** `Reviewer` (`review-profiles.json`) has an optional `profile` (name); its `claude --bg` runs with that profile's `CLAUDE_CONFIG_DIR`; a name not listed fails the run's start with that name. `learnSessions` finds the run's session by job id in the merged registry of all profiles.
- **R29** Before starting or resuming a manager or starting a reviewer in a profile other than `default`, My Air reads (never writes) that profile's `.claude.json` and refuses the start, naming the profile and showing R24's command, unless its `mcpServers` holds an `http` entry whose `url` equals that of `default`'s `idea` entry. In `default` nothing is checked.
- **R30** Removing a profile is also refused while a plan manager, a review manager or a reviewer profile names it.
- **R23** Remove refuses while any session of the profile has a live registry entry or a tab (open or detached) in this IDE, naming one of them. *(builder's call, from the builder check)*
- **R24** After Add, Settings shows, ready to copy, the command that adds the IDE's MCP server to the new profile at user scope: `claude mcp add --scope user --transport http <name> <url>` run with that profile's `CLAUDE_CONFIG_DIR`, set for that command only and removed from the shell after it, `<name>` and `<url>` read from the `http` entry of `default`'s `~/.claude.json` `mcpServers` whose name is `idea`; without such an entry it says so. My Air never writes a profile's `.claude.json`.
- **R25** A session started in a profile gets the same `--append-system-prompt`, brief hook (`--settings`) and plan prompt as one in `default`; whether its tools are there is the profile's business.
- **R21** `claude -p` helpers (branch names, dashboard analysis) run in `default`, with any inherited `CLAUDE_CONFIG_DIR` removed.

## Why

The user tests skills, hooks and `CLAUDE.md` variants. Doing it in `~/.claude` changes every running session and leaves memory behind. A profile is a clean Claude Code setup on the same account, whose sessions still show in My Air with their status like any other.

Goals: start any session in a profile; see and resume its sessions as usual; keep profiles fully apart (settings, skills, plugins, hooks, `CLAUDE.md`, memory, background jobs).
Non-goals: several accounts (works, but not designed for), editing a profile's contents from My Air.
Anti-goals: a profile session that vanishes from the tree or resumes in the wrong directory (`--resume` would start empty or fail); My Air writing into Claude Code's own state files.

## Facts this relies on (Claude Code docs, code.claude.com/docs/en/)

- `claude-directory`: with `CLAUDE_CONFIG_DIR` set, every `~/.claude` path lives under it, `.claude.json` included.
- `authentication`: credentials are `<config dir>/.credentials.json`; each config dir has its own login. Copying it is not documented; refresh-token rotation is not documented, hence no copying.
- `agent-view`: with `CLAUDE_CONFIG_DIR` the background supervisor is a separate instance with its own sessions, so `attach`/`stop` must run in the job's profile.
- `memory`: auto memory lives in `<config dir>/projects/<project>/memory/`, so an empty profile has none; `autoMemoryEnabled: false` in the profile's `settings.json` turns it off for good (the user's choice, not My Air's).
- `permissions`: folder trust is `projects[<path>].hasTrustDialogAccepted` in `.claude.json`; first-run onboarding has no documented key to skip it.

The first task of the plan checks these on this machine: start `claude` with `CLAUDE_CONFIG_DIR` set to a scratch directory, and record where `sessions/<pid>.json`, the transcript, `.claude.json` and `.credentials.json` land, and that `claude --bg` + `claude attach` work there.

## Approaches

- **A. Full isolation, My Air reads every profile (chosen).** Clean separation including memory and jobs; the most code, as `Homes.claude` (used by 8 files) becomes a list.
- **C. Profile directory with junctions of `projects/` and `sessions/` to `~/.claude`.** Scanner untouched; but memory would be shared through `projects/` and the profile could not be told from the files. Rejected by the user in favour of A.
- **B. CLI flags only (`--setting-sources`, `--settings`, `--plugin-dir`).** Does not meet the goal: the user's `CLAUDE.md` and `~/.claude/skills` still load (the dashboard analysis already runs `--setting-sources project` and still gets `CLAUDE.md`).

## Design

### Profiles

`store/Profiles.kt`: `data class Profile(val name: String, val dir: Path)`, `ProfileStore` reading `~/.my-air/profiles.json` (version 1, cached by modification time as `Presets` are, validated per R1). `Profiles.all()` is `default` (`~/.claude`) followed by the file's. `Homes.claude` stays only as the `default` profile's directory; its other uses move to the session's profile.

Settings (R2, R3): a list editor in `MyAirSettings`' configurable, next to configurations. Add asks name and directory, creates the directory, writes the file, then opens a new session tab of that profile (`ClaudeSessionOpener` as New Session does, with the profile). Remove only edits the file.

### Reading

`ClaudeSessionsService` holds one `ClaudeSessionScanner` and one `LiveSessionRegistry` per profile, rebuilt when `profiles.json` changes. Results are merged; the profile is stamped on each `ClaudeSession` and `LiveSession` (R4). The duplicate check (R5) runs on the merged list. The existing rule "one session in several files of one profile: latest message wins" stays within a profile.

`ClaudeHomeWatcher` takes a list of projects directories (R6). Scan cache: `Saved.forJar` gets the profile; `default` keeps `scan-cache-<jar hash>.json`, others add `-<8 hex of the dir's hash>` (R7).

Desktop sessions (`DesktopSessionReader`) apply to `default` only.

### Starting and following

`ClaudeSessionVirtualFile` gets `val profile: Profile`. `SessionTerminals.start` sets `CLAUDE_CONFIG_DIR` for a profile and throws for `default` while the IDE has one, since the terminal only adds variables (`addedProfileEnv`); `claude stop` (`GeneralCommandLine` with parent environment `NONE` and `processProfileEnv`) and `runClaude` (`ProcessBuilder`, `profileEnv`) remove it (R8). `SessionTabUrl` adds the optional `profile` key (R9); `parseSessionTabPath` accepts 2 or 3 keys, nothing else. `restartArgs`/`hasTranscript` take the profile's directory (R10). `CloseAndSettleAction`, `SessionPane`'s and `SessionTerminals`' registry reads and `claude stop`/`claude attach` use the file's profile (R11).

### Managers and reviewers

`PlanManager` gains `profile: String?` (null is `default`), which `PlanManagerStore` writes only when set (R22); `review.json` reuses `PlanManager`, so the review's manager gets it too, and format 1 stays readable. `PlanFiles.startManager` and `ReviewFiles` resolve it against `Profiles.all()` (a name no longer listed: the plan tab shows "Profile <name> is gone", as a session tab does), build the manager's `ClaudeSessionVirtualFile` with it and call `hasTranscript` with its directory. `PlanFiles.adopt` stores the adopted session's profile (R26); `startAsPlan` passes the chip's. New Review... gets the same Profile chip as the composer (R27).

`Reviewer.profile` (R28) goes into `reviewerArgs`' environment through `runClaude`, which now takes an environment map (empty: `CLAUDE_CONFIG_DIR` removed). Since `claude --bg` hands the run to the profile's own supervisor, `learnSessions` already finds it once the registry is merged (R4).

The tools check (R29) is one function, `myAirServerIn(profile): Boolean`, reading both `.claude.json` files with Gson; any unreadable file throws. It runs on every manager start or resume and every reviewer start, off the EDT where those already read git. A refused manager start shows its reason in the plan or review tab where the terminal would be; a refused reviewer leaves the run unstarted with the reason, as `runProblem` does.

Remove (R23, R30) checks the registry, this IDE's tabs, `plan-managers.json`, every `review.json` and `review-profiles.json`.

### Choosing

`Preset.profile: String?` (R12), resolved against `Profiles.all()` at start. `PromptPanel` chip after effort (R13), stored in `PromptChoices` and `Draft`. `presetOf(session)` sets it from `session.profile` (R14). `my_air_session_start` gets `profile` (R15). `ScheduledTask` inherits through `Preset` (R16).

### Showing

R17–R20 as stated. `matches` adds the profile name to its fields when not `default`.

### Helpers

`runClaude` (`ClaudePrint.kt`) removes `CLAUDE_CONFIG_DIR` from the environment of the branch-name and analysis calls (R21).

## Left to the builder

- The look of the Profiles list in Settings and of the Profile chip, following `PresetsEditor` and the existing chips.
- Whether profile rows get a colour or icon; not required.
- Test split: pure parts (`ProfileStore` validation, URL parsing, merge and duplicate check, `presetOf`) as JUnit tests as the codebase does.
