# actions

## Overview

Menu and toolbar actions that start, find and switch Claude sessions, plus the `git` layer they and other packages
rely on. `Git` (`Git.kt`) runs the `git` on PATH. `Worktrees.kt` holds the pure parsing and choice logic, which the
tests cover. Worktree creation (`NewClaudeSessionInWorktreeAction.kt`) and preset starts (`PresetActions.kt`) are the
entry points other packages call: `prompt`, `toolwindow`, `mcp`, `planview`. Actions named in
`src/main/resources/META-INF/plugin.xml` (`NewClaudeSessionAction`, `NewClaudeSessionInWorktreeAction`,
`FocusClaudeSessionAction`, `NextSessionNeedingMeAction`, `InstallMyAirBuildAction`, `PresetsGroup`) are found
there by class name, so moving or renaming one means updating that file too.

## Interface

### Git (`Git.kt`)

- `object Git`
  - `run(dir, vararg args, input, ok)` / `bytes(...)`: run `git` in `dir`. Any exit code outside `ok` (default `{0}`) throws `GitFailure` with git's stderr.
  - `mainRoot(dir)`: the main checkout's root, also from inside a linked worktree (`--git-common-dir`).
  - `repo(dir): GitRepo`: local and remote branches (without `<remote>/HEAD`) and the default branch.
  - `state(dir): GitState?`: the work tree against its base, with changed files since `merge-base <base> HEAD` (untracked ones included). Returns null only when no `.git` exists up the path.
  - `baseOf(root)`: the stored base for the current branch, else the default branch.
  - `root(dir)`, `branchOf(dir)`, `currentBranch(dir)` (`detached at <sha7>` on a detached HEAD).
  - `head(dir): Head?` / `headBranch(dir)`: read from the HEAD file without running git; null for a missing folder.
  - `containedBases(dir, branch)`: up to `MAX_CONTAINED_BASES` branches whose tips HEAD contains, latest commit first.
  - `show(root, commit, path)`: file bytes at a commit.
  - `addWorktree(mainRoot, path, pick, fallback, inUse)`, `addBranchWorktree(mainRoot, path, branch, base)`.
  - `setBase(dir, branch, base)` / `storedBase(dir, branch)`: config key `branch.<branch>.myAirBase`.
  - `includedFiles(mainRoot)`: gitignored untracked files matching `.worktreeinclude`.
- `data class GitRepo(mainRoot, branches, default)`, `data class GitState(...)` (with `added`/`removed` sums), `data class FileChange(path, kind, added, removed)` with `enum Kind { ADDED, MODIFIED, DELETED, UNTRACKED }`.
- `class GitFailure : IllegalStateException`.
- `fun lineCount(bytes)`: line count as `git diff --numstat` gives it; 0 for binary (NUL in the first 8000 bytes).

### Worktree helpers (`Worktrees.kt`, no IDE dependencies)

- `baseChoices(branches, default, recent): BaseChoices` (popup order: default, recent, rest) and `pushRecent`, both bounded by `MAX_RECENT_BASES = 5`.
- `defaultBranch(remotes, originHead, local)`, `worktreeName(base, date, id)` (`<base>-<date>-<id4>`), `dirName(branch)`, `worktreePath(mainRoot, setting, name)`.
- `parseBranches(refs)`, `containedBases(refs, branch)`, `parseWorktrees(porcelain): List<Worktree>`, `copyIncluded(mainRoot, worktree, files)`.

### Starting sessions

- `NewClaudeSessionAction`: a new session in the project (`ClaudeSessionOpener.startNew`).
- `NewSessionInDirAction(text, inWorktree, dir, home, plan, folder)`: a session started from a tree row, in the row's directory, plan task and folder.
- `NewClaudeSessionInWorktreeAction`, and the helpers it uses:
  - `startInWorktree(project, dir, base, args, where, plan, branch, askBranch, folderId)`
  - `createWorktree(repo, base, branch, id, running): Path`: the popup-free core, used off the EDT.
  - `runningDirs()`, `rememberBase(repo, base)`, `recentBases(mainRoot)`, `basePopup(title, choices, chosen)`, `popupPoint(e)`
  - `internal background(project, title, work, done)`: runs work off the EDT and continues on the EDT; a failure opens an error dialog.
- `PresetActions.kt`:
  - `startPreset(project, preset, where, prompt, branch)`, `continueSession(project, session, workTree)`, `Preset.summary()`
  - `PresetAction`, `PresetsGroup` (toolbar buttons and dropdown)
  - `SameSettingsAction`, `SaveAsPresetAction` (on a session's menu)
- `PresetSettings.kt`: `PresetsEditor` (the list in Settings › Tools › My Air), `PresetDialog` (`preset(): Preset?`), `internal val MODELS = listOf("fable", "opus", "sonnet")`.

### Navigation and other actions

- `FocusClaudeSessionAction`: a popup of every IDE's session tabs (`TabsSync.allIdes`); the chosen one is focused.
- `NextSessionNeedingMeAction`: moves through `service.attentionQueue` (sessions that need input, longest waiting first, then live ones with an unread reply, oldest first), one press per session, wrapping round (`nextInQueue`).
- `OpenInDir.kt`: `openInIdea(project, dir)`, `openInTerminal(project, dir)`, `OpenInDirAction`, `openInIdeaAction`, `openInTerminalAction`.
- `InstallMyAirBuildAction` and `myAirBuild(dir)`: install `build/distributions/my-air-*.zip` over the running plugin, then restart.

### Diffs against the base (`BaseDiff.kt`)

- `showBaseDiff(project, state, selected)`, `showCombinedBaseDiff(project, state)`, `showBaseChangesTree(project, state)`.
- `plannotator: File?` (looked up on PATH once) and `reviewInPlannotator(project, state)`.
- `ShowChangesVsBaseAction(selected)`: on a session's menu.
- `val Kind.status: FileStatus`.

### Branch naming (`BranchNames.kt`)

- `suggestBranchName(dir, text, jiraTools)`: Haiku names a branch through `claude -p` (2 minute timeout).
- `ticketKey(text)`, `branchName(key, slug)`, `namingPrompt(text)`.

## Invariants

- Any git failure must surface. `Git.run` throws `GitFailure` unless the exit code is in `ok`, and callers list
  exit code 1 explicitly only where git uses it to mean "none" (`merge-base`, `rev-parse --verify -q`,
  `config --get`, `check-ignore`).
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
- `PresetsGroup` returns the same action instances while `PresetStore.read()` gives an equal result. A broken
  `presets.json` shows as a separator in the dropdown instead of throwing on every update.

## Side Effects

- Processes: `git` (in `Git`, synchronously, with stdin written on a separate future), `claude -p --model haiku --no-session-persistence` (`suggestBranchName`, so it leaves no transcript), the `plannotator review ...` command typed into a new Terminal tab.
- Repository writes: `git worktree prune`, `git worktree add`/`remove`, new branches (`-b`, `--track`), `git config branch.<branch>.myAirBase`.
- File system: `copyIncluded` copies `.worktreeinclude` files into the new worktree. `InstallMyAirBuildAction` schedules a plugin swap (`PluginInstaller.installAfterRestart`) and restarts the IDE.
- Application properties: `dev.myair.worktreeBases:<mainRoot>` (recent bases, `rememberBase`).
- `presets.json` through `PresetStore.write` (`SaveAsPresetAction`; `PresetsEditor` edits the list the settings page saves).
- IDE UI: popups, error dialogs, Terminal tool-window tabs (`openInTerminal`, `reviewInPlannotator`), diff windows and tabs, project open or focus (`openInIdea`, on a pooled thread), focus requests to other IDEs (`TabsSync.focusIn`).
- Threading: `background` runs work under `Task.Backgroundable`. `runningDirs` and `rememberBase` run on the EDT. `changesOf` builds its changes inside a read action, off the EDT.

## Verification

- Run: `./gradlew test --tests 'dev.myair.actions.*'` (`GitTest` runs real `git` in a temp directory; also `WorktreesTest`, `BranchNamesTest`, `InstallMyAirBuildTest`)
- Whole project: `./gradlew test buildPlugin`
