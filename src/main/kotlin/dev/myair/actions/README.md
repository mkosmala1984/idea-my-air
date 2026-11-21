# actions

## Overview

Menu and toolbar actions that start, find and switch Claude sessions, plus the `git` layer they and other packages
rely on. `Git` (`Git.kt`) runs the `git` on PATH. `Worktrees.kt` holds the pure parsing and choice logic, which the
tests cover. Worktree creation (`NewClaudeSessionInWorktreeAction.kt`) and preset starts (`PresetActions.kt`) are the
entry points other packages call: `startPreset` from `prompt`, `createWorktree` from `mcp`, `continueSession` from
`toolwindow`. `Git` and the other helpers (`runClaude`, `background`, `basePopup`, `RangeSize`, `BranchFate`,
`profileComboBox`, `NewSessionInDirAction`) serve `review`, `reviewview`, `dashboard`, `schedule`, `service`, `planview`
and the rest. Actions named in
`src/main/resources/META-INF/plugin.xml` (`NewClaudeSessionAction`, `NewClaudeSessionInWorktreeAction`,
`FocusClaudeSessionAction`, `NextSessionNeedingMeAction`, `InstallMyAirBuildAction`, `PresetsGroup`) are found
there by class name, so moving or renaming one means updating that file too.

## Interface

### Git (`Git.kt`)

- `object Git`
  - `run(dir, vararg args, input, ok)` / `bytes(...)`: run `git` in `dir`. Any exit code outside `ok` (default `{0}`) throws `GitFailure` with git's stderr.
  - `mainRoot(dir)`: the main checkout's root, also from inside a linked worktree (`--git-common-dir`).
  - `mainCheckout(dir)`: the same read from the files git keeps, so callable on the EDT.
  - `worktrees(mainRoot): List<Worktree>`: the main checkout first, after pruning those whose directory is gone.
  - `changedLines(root, from)`: old-side line ranges by path of `git diff -U0 <from>`; null when `from` is no commit or not an ancestor of HEAD (after a rebase).
  - `commitsBetween(root, from)`: commits HEAD has and `from` lacks; null when `from` is no commit or not an ancestor of HEAD.
  - `repo(dir): GitRepo`: local and remote branches (without `<remote>/HEAD`) and the default branch.
  - `state(dir, against)`: the work tree against `against`, else its base, with changed files since `merge-base <base> HEAD` (untracked ones included). Returns null only when no `.git` exists up the path.
  - `baseOf(root)`: the stored base for the current branch, else the default branch.
  - `root(dir)`, `branchOf(dir)`, `currentBranch(dir)` (`detached at <sha7>` on a detached HEAD).
  - `head(dir): Head?` (`Head(root, branch)`, branch null when detached) / `headBranch(dir)`: read from the HEAD file without running git; null for a missing folder.
  - `gitDirs(dir)`: the git dir and the common dir of the work tree, read from files; null as for `head`.
  - `nearestBases(dir, branch, keepContaining)`: every other branch, nearest first: fewest commits of HEAD it lacks, then fewest of its own (`%(ahead-behind:HEAD)`, git 2.41); without `keepContaining` (a review's Change Base...) none that lacks no commit of HEAD, as it leaves nothing to review. Session Context keeps them.
  - `rangeSize(root, range)`: `RangeSize(commits, files)` of `<start>..<end>`.
  - `branchFate(root, branch): BranchFate?`: commits its upstream (else its base) lacks and the first-parent merge into its base holding its tip; null when the branch is missing or is its own base.
  - `uncommitted(root)`: absolute paths of the files differing from HEAD, untracked ones included.
  - `show(root, commit, path)`: file bytes at a commit.
  - `addWorktree(mainRoot, path, pick, fallback, inUse)`, `addBranchWorktree(mainRoot, path, branch, base)`.
  - `setBase(dir, branch, base)` / `storedBase(dir, branch)`: config key `branch.<branch>.myAirBase`.
  - `includedFiles(mainRoot)`: gitignored untracked files matching `.worktreeinclude`.
  - `internal headerPath(line)`: the path of a `---`/`+++` diff header, null for `/dev/null`.
- `data class BranchFate(unpushed, merged)` with `Merged(sha, pr)` (`pr` from a `(pull request #<n>)` subject).
- `data class GitRepo(mainRoot, branches, default)`, `data class GitState(...)` (with `added`/`removed` sums), `data class FileChange(path, kind, added, removed, stage)` with `enum Kind { ADDED, MODIFIED, DELETED, UNTRACKED }` and `enum Stage { UNCOMMITTED, COMMITTED, PUSHED }`.
- `class GitFailure : IllegalStateException`.
- `fun lineCount(bytes)`: line count as `git diff --numstat` gives it; 0 for binary (NUL in the first 8000 bytes).

### Worktree helpers (`Worktrees.kt`, no IDE dependencies)

- `baseChoices(branches, default, recent): BaseChoices` (popup order: default, recent, rest) and `pushRecent`, both bounded by `MAX_RECENT_BASES = 5`.
- `defaultBranch(remotes, originHead, local)`, `worktreeName(base, date, id)` (`<base>-<date>-<id4>`), `dirName(branch)`, `worktreePath(mainRoot, setting, name)`.
- `parseBranches(refs)`, `nearestBases(refs, branch, keepContaining)`, `parseWorktrees(porcelain): List<Worktree>`, `copyIncluded(mainRoot, worktree, files)`.
- `declineProjectMcpServers(worktree)`: adds the `.mcp.json` servers its `.claude/settings.local.json` has not decided on to `disabledMcpjsonServers`.

### Starting sessions

- `NewClaudeSessionAction`: a new session in the project (`ClaudeSessionOpener.startNew`).
- `NewSessionInDirAction(text, inWorktree, dir, home, plan, folder)`: a session started from a tree row, in the row's directory, plan task and folder.
- `NewClaudeSessionInWorktreeAction`, and the helpers it uses:
  - `startInWorktree(project, dir, base, args, where, plan, branch, askBranch, folderId, open)`: `open(cwd, id, args)` starts the session once the worktree is made, by default in a session tab.
  - `createWorktree(repo, base, branch, id, running): Path`: the popup-free core, used off the EDT.
  - `runningDirs()`, `rememberBase(repo, base)`, `recentBases(mainRoot)`, `basePopup(title, choices, chosen)`, `popupPoint(e)`
  - `internal background(project, title, work, done)`: runs work off the EDT and continues on the EDT; a failure opens an error dialog.
- `PresetActions.kt`:
  - `startPreset(project, preset, where, prompt, branch, planName)`: in the preset's profile; with `planName` the session starts as the manager of a new plan (`planview.startAsPlan`) instead of in a session tab.
  - `continueSession(project, session, workTree)` (in the session's profile), `Preset.summary()`
  - `internal presetProfile(preset, profiles): Result<Profile>`: the preset's profile, `default` when it names none; a failure naming it when it is no longer listed.
  - `PresetAction` (starts with the preset's `prompt`, or with `askMore` opens `prompt.showPromptPopup` on it; with model and effort pairs, `Preset.variants`, it first lists them in a popup under the button, `variantLabel`, and starts `withVariant` of the pick), `PresetsGroup` (toolbar buttons and dropdown, where a preset with pairs is a submenu of them)
  - `SameSettingsAction`, `SaveAsPresetAction` (on a session's menu)
- `PresetSettings.kt`: `PresetsEditor` (the list in Settings › Tools › My Air), `PresetDialog(preset, takenNames, dialogTitle, ideBase, name)` (`preset(): Preset?`; a Profile row), `Row.profileComboBox(prop)` (a profile combo; a stored name no longer listed stays chosen as missing and OK is refused until a listed one is chosen), `internal profileChoices(listed, stored)`, `internal val MODELS = listOf("fable", "opus", "sonnet")`, `internal const val OWN_SETTING`.
- `ProfileSettings.kt`: `ProfilesEditor` (the Claude Profiles list in Settings › Tools › My Air; Add, Remove, Open Folder; `reload()` rereads `profiles.json`), with the private `NewProfileDialog` (directory prefilled as `~/.my-air/claude-profiles/<name>` until edited, validated with `parseProfiles` and refused inside the default profile's directory; a section of `CopyItem` checkboxes under their group headings to copy once from the default profile, look and behaviour ticked, each item's availability read once off the EDT and an unavailable one disabled with its reason, red when the source is broken) and `McpCommandDialog` (the `claude mcp add` command to copy).

### Navigation and other actions

- `FocusClaudeSessionAction`: a popup of every IDE's session tabs (`TabsSync.allIdes`); the chosen one is focused.
- `NextSessionNeedingMeAction`: moves through `service.attentionQueue` (sessions that need input, longest waiting first, then live ones with an unread reply, oldest first), one press per session, wrapping round (`nextInQueue`).
- `OpenInDir.kt`: `openInIdea(project, dir)`, `openInTerminal(project, dir)`, `OpenInDirAction`, `openInIdeaAction`, `openInTerminalAction`.
- `InstallMyAirBuildAction` and `myAirBuild(dir)`: install `build/distributions/my-air-*.zip` over the running plugin, then restart.

### Diffs against the base (`BaseDiff.kt`)

- `showBaseDiff(project, state, selected)` (in the editor's preview tab), `showCombinedBaseDiff(project, state)`, `showBaseChangesTree(project, state)`.
- `plannotator: File?` (looked up on PATH once) and `reviewInPlannotator(project, state)`.
- `ShowChangesVsBaseAction(selected)`: on a session's menu.
- `val Kind.status: FileStatus`.

### Running `claude` (`ClaudePrint.kt`)

- `runClaude(args, dir, input, timeout, env)`: runs a `claude` command line, writing `input` to stdin, and returns its stdout; `env` defaults to `profileEnv` of the `default` profile.
- `processBuilder(args, dir, env)`: a `ProcessBuilder` with the IDE's environment changed by `env`, a null value removing the variable.

### Branch naming (`BranchNames.kt`)

- `suggestBranchName(dir, text, jiraTools)`: Haiku names a branch through `claude -p` (2 minute timeout).
- `ticketKey(text)`, `branchName(key, slug)`, `namingPrompt(text)`.

## Invariants

- Any git failure must surface. `Git.run` throws `GitFailure` with stderr unless the exit code is in `ok`, and callers
  list exit code 1 explicitly only where git uses it to mean "none" (`merge-base`, `rev-parse --verify -q`,
  `config --get`, `check-ignore`). The exception is the private `isAncestor` (`merge-base --is-ancestor`, for
  `branchFate`): it runs its own process with stderr merged into stdout, reads 0 as yes and 1 as no, and throws a
  `GitFailure` naming only the exit code otherwise.
- `Git.state` returns null only when no `.git` exists up the path. Exit code 128 is not read as "no repository".
  A missing default branch, a base that names no commit, or a base with no shared history goes into
  `GitState.problem`, with `changes == null`. Another base is never put in its place.
- `defaultBranch`: with an `origin` remote, `origin/HEAD` must be set; without one, exactly one of local `main`/`master` must exist. Otherwise it throws.
- `baseChoices` requires `default in branches`. `createWorktree` requires `base in repo.branches`.
- `Git.addWorktree` removes another worktree that holds the branch only when all of these hold: it is not the main
  checkout, it is not locked, it is not `inUse` (no running session's `cwd`/`workDir` lies in it, `runningDirs`),
  and it is clean (`status --porcelain --untracked-files=all` is empty). Otherwise it creates the new branch
  `fallback` off the pick (`addBranchWorktree`).
- `addBranchWorktree` uses `--no-track` and always stores the base (`branch.<branch>.myAirBase`).
- `parseWorktrees` expects each block to open with `worktree `, and the main checkout comes first.
- `dirName` throws on a branch with no letters or digits. `branchName` throws unless Haiku's reply is one slug line
  (`[a-z0-9]+(-[a-z0-9]+)*`). The ticket key is added afterwards, never taken from the model's reply.
- `startInWorktree`: a `branch` and `askBranch` are mutually exclusive. A first message from the branch popup
  requires that `args` hold no `--`. `startPreset` requires a `branch` only together with `preset.worktree`.
- `myAirBuild` throws when several `my-air-*.zip` files exist. `InstallMyAirBuildAction` checks that the zip's
  root entry has the installed plugin folder's name.
- `ProfilesEditor` never removes `default` and refuses a removal while `removeRefusal` names a use of the profile (a live session, a tab or detached process, a plan or review manager, a reviewer profile); Add requires a project. Add with ticked items needs an absent or empty directory, and registers nothing when the copy or the registration fails.
- `runClaude` throws on a timeout, an interrupt or a non-zero exit with what `claude` printed; a timeout or an interrupt kills the process with its descendants, never a process it left once it exited.
- `PresetsGroup` returns the same action instances while `PresetStore.read()` gives an equal result. A broken
  `presets.json` shows as a separator in the dropdown instead of throwing on every update.

## Side Effects

- Processes: `git` (in `Git`, synchronously, with stdin written on a separate future), `claude -p --model haiku --no-session-persistence --output-format text`, with `--allowedTools` when Jira tools are set (`suggestBranchName`, through `runClaude`, so it leaves no transcript), any `runClaude` command line (stdout and stderr to temp files, stdin written on its own thread pool), the `plannotator review ...` command typed into a new Terminal tab.
- Repository writes: `git worktree prune`, `git worktree add`/`remove`, new branches (`-b`, `--track`), `git config branch.<branch>.myAirBase`.
- File system: `copyIncluded` copies `.worktreeinclude` files into the new worktree. `declineProjectMcpServers` adds the undecided `.mcp.json` servers to its `.claude/settings.local.json` `disabledMcpjsonServers`. `InstallMyAirBuildAction` schedules a plugin swap (`PluginInstaller.installAfterRestart`) and restarts the IDE.
- Application properties: `dev.myair.worktreeBases:<mainRoot>` (recent bases, `rememberBase`).
- `presets.json` through `PresetStore.write` (`SaveAsPresetAction`; `PresetsEditor` edits the list the settings page saves).
- `profiles.json` through `ProfileStore.write`, at once on `ProfilesEditor`'s Add and Remove; Add runs `copyFromDefault`, which copies the ticked items, then creates the profile's directories (`createProfileDirs`) and writes `profiles.json`; then Add refreshes `ClaudeSessionsService` and opens a session tab of the profile for its first login (`ClaudeSessionOpener.startNew`).
- IDE UI: popups, error dialogs, Terminal tool-window tabs (`openInTerminal`, `reviewInPlannotator`), diff windows and tabs, project open or focus (`openInIdea`, on a pooled thread), focus requests to other IDEs (`TabsSync.focusIn`).
- Threading: `background` runs work under `Task.Backgroundable`. The profile copy and registration run in a `Task.Modal` that cannot be cancelled; `NewProfileDialog` reads `copyAvailability` once on a pooled thread. `runningDirs` and `rememberBase` run on the EDT. `changesOf` builds its changes inside a read action, off the EDT.

## Verification

- Run: `./gradlew test --tests 'dev.myair.actions.*'` (`GitTest` runs real `git` in a temp directory; also `WorktreesTest`, `BranchNamesTest`, `InstallMyAirBuildTest`, `ClaudePrintTest`, `PresetActionsTest`)
- Whole project: `./gradlew test buildPlugin`
