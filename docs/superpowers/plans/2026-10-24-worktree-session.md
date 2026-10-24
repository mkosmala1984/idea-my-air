# New session in a worktree

## TL;DR

- **Goal:** a second toolbar/Tools-menu action, "New Claude Session in Worktree", next to the unchanged "New Claude Session". It opens an inline popup listing the repo's branches; picking one creates a git worktree with a new branch off it and starts a new session in that worktree.
- **Popup order:** the repo's default branch at the top, then up to 5 recently chosen bases (newest first), then all other branches; speed search; preselected = the last chosen base, else the default branch.
- **Approach:** the plugin runs `git worktree add --no-track -b worktree-<id8> <worktrees-dir>/<id8> <base>` itself (`claude --worktree` takes no base branch), then reuses `startNew` with the worktree as cwd. `<id8>` = first 8 hex chars of the new session id, so the worktree, branch and session share a name.
- **Worktrees directory:** plugin setting (Settings › Tools › My Air), default `.claude/worktrees` (Claude Code's own location; `claude` has no setting for it). A relative path resolves against the main checkout's root, an absolute one is used as is.
- **Decided:** branch name generated (`worktree-<id8>`, no prompt); default branch = target of `origin/HEAD`, without `origin` the one existing local `main`/`master`, anything else an error naming `git remote set-head origin -a`; gitignored files matching `.worktreeinclude` (e.g. `.env`) are copied into the new worktree, as `claude -w` does.
- **Decisions needing your input:** none.
- **Risks:** a worktrees directory inside the repo shows up as untracked in the main checkout unless gitignored; the plugin does not touch `.gitignore`.
- **Out of scope:** removing worktrees when a session ends, choosing an existing branch to check out as is, worktree status in the session tree.

## Behaviour

| Situation | Result |
|---|---|
| Click the action (toolbar or Tools menu) | branches are read off the EDT, then a list popup opens at the click (`guessBestPopupLocation` of the action's data context) |
| Popup content | `[default]`, separator, `[recent − default − missing]` (≤5), separator, `[all branches − shown above]`; local `refs/heads/*` and remote `refs/remotes/*/*` without `*/HEAD`, short names (`main`, `origin/feature-x`) |
| Preselection | first recent that still exists; none → the default branch (index 0) |
| A recent base was deleted | not shown; stays out of the stored list on the next write |
| Pick a branch | background task "Creating worktree": `git worktree add`, then the `.worktreeinclude` copy; success → base pushed to recents, tab opens with `claude --session-id <id>` in the worktree, the `FolderTarget` assignment applies as for a plain new session |
| `git` fails (not a repo, path exists, …) | error dialog with git's stderr; recents unchanged; no tab |
| Escape | nothing happens |
| Project opened from a linked worktree | a relative worktrees directory still resolves against the main checkout: root = parent of `git rev-parse --path-format=absolute --git-common-dir` |
| Setting = `.claude/worktrees` (default) | `<main-root>/.claude/worktrees/<id8>` |
| Setting = absolute, e.g. `E:/wt` | `E:/wt/<id8>`; ids are unique, so repos can share one directory |
| Setting blank | the settings page refuses to save it (`validationOnApply`), so the action never sees a blank value |
| `.worktreeinclude` absent in the main checkout | nothing is copied |
| `.worktreeinclude` present | every file of the main checkout that is untracked, matches a pattern in it and is gitignored is copied to the same relative path in the worktree; a tracked file is never copied (the checkout already has it) |
| Copy fails (I/O) | error dialog like a git failure; the worktree stays on disk, no tab, recents unchanged |

The include list comes from git, so pattern semantics are git's own, no glob code in the plugin: `git ls-files -z --others --ignored --exclude-from=.worktreeinclude` (untracked files matching the include patterns), then `git check-ignore -z --stdin` keeps those the standard ignore rules exclude *(my call: Claude Code's extra `**/` rule for wholly ignored directories is not reproduced; git's matching covers the `.env` case)*.

`--no-track` keeps a remote base from becoming the upstream of the new branch. Recents live in the project-level `PropertiesComponent` under `dev.myair.worktreeBases` *(my call: one project window = one repo, no shared file needed)*. Enablement matches the existing action (`basePath != null`); a non-repo fails loudly on click *(my call: no git call in `update()`)*.

## Code shape

New file `src/main/kotlin/dev/myair/WorktreeSessions.kt`:

```kotlin
/** Popup content: [items] in display order, separators above [recentStart] and [restStart], [selected] preselected. */
data class BaseChoices(val items: List<String>, val recentStart: Int, val restStart: Int, val selected: Int)

fun baseChoices(branches: List<String>, default: String, recent: List<String>): BaseChoices  // asserts default in branches
fun pushRecent(recent: List<String>, chosen: String): List<String> = (listOf(chosen) + (recent - chosen)).take(5)
fun defaultBranch(originHead: String?, hasOrigin: Boolean, local: List<String>): String       // decision 2, throws otherwise
fun worktreePath(mainRoot: Path, setting: String, name: String): Path = mainRoot.resolve(setting).resolve(name)  // resolve keeps an absolute setting

/** Thin wrapper: runs git in [dir], returns stdout, throws with stderr on a non-zero exit. */
object Git {
    fun run(dir: Path, vararg args: String): String
    fun branches(dir: Path): List<String>
    fun defaultBranch(dir: Path): String
    fun mainRoot(dir: Path): Path
    fun addWorktree(root: Path, name: String, base: String): Path
    fun includedFiles(mainRoot: Path): List<String>  // relative paths; empty without .worktreeinclude
}

fun copyIncluded(mainRoot: Path, worktree: Path, files: List<String>)  // creates parent dirs, Files.copy

class NewClaudeSessionInWorktreeAction : DumbAwareAction()  // popup via BaseListPopupStep: getSeparatorAbove, defaultOptionIndex, isSpeedSearchEnabled
```

New file `src/main/kotlin/dev/myair/MyAirSettings.kt`:

```kotlin
/** Application-level, stored in `myAir.xml`; shared by every project of this IDE. */
@Service @State(name = "MyAirSettings", storages = [Storage("myAir.xml")])
class MyAirSettings : SimplePersistentStateComponent<MyAirSettings.State>(State()) {
    class State : BaseState() { var worktreesDir by string(".claude/worktrees") }
}

/** Settings › Tools › My Air: one field, `textFieldWithBrowseButton` (folders only), blank rejected on apply. */
class MyAirConfigurable : BoundConfigurable("My Air")  // Kotlin UI DSL panel bound to state.worktreesDir
```

`plugin.xml`: `<applicationConfigurable parentId="tools" instance="dev.myair.MyAirConfigurable" id="dev.myair.settings" displayName="My Air"/>`. `addWorktree(root, name, base)` takes the resolved path from `worktreePath` instead of building it.

`ClaudeSessionOpener.startNew(project)` becomes `startNew(project, cwd = projectBase(project), id = UUID…)`; `NewClaudeSessionAction` calls it exactly as now. `Git.run` uses `GeneralCommandLine` + `CapturingProcessHandler` (platform, no Git4Idea dependency).

## Tasks

Branch `worktree-session`, worktree `.claude/worktrees/worktree-session`.

| Task | Status | Commit |
|---|---|---|
| 1 | done | `4c1c2df` |
| 2 | done | `4c1c2df` |
| 3 | done | `c6c8a50` |
| 4 | done (sandbox check pending) | `6ac3100` |
| 5 | done (sandbox check pending) | `0070a33` |

1. **Pure logic + tests** — `baseChoices`, `pushRecent`, `defaultBranch`, `worktreePath` with `WorktreesTest`: order and dedup (default also in recents), cap at 5, missing recent dropped, preselection with/without recents, separators' indices when recents are empty, every branch of decision 2, relative and absolute worktrees directory.
   - [x] write failing tests  - [x] implement  - [x] `./gradlew test --tests dev.myair.WorktreesTest`
2. **`Git` + tests on a real temp repo** (`git` on PATH, as `ClaudeHomeWatcherTest` uses the real FS): `branches` lists local and remote without `origin/HEAD`; `defaultBranch` with a bare `origin` clone; `mainRoot` from inside a linked worktree; `addWorktree` from a remote base creates the dir, branch HEAD = base sha, `git config branch.<b>.remote` empty; existing path → exception carrying stderr; `includedFiles` with `.gitignore` = `.env`, `secret.txt`, `cache/` and `.worktreeinclude` = `.env`, `tracked.txt`, `cache/keep.json` returns `.env` and `cache/keep.json` only (`secret.txt` ignored but not included, `tracked.txt` tracked); no `.worktreeinclude` → empty; `copyIncluded` recreates `cache/keep.json` with the same bytes.
   - [x] tests  - [x] implement  - [x] run class
3. **`startNew(project, cwd)` refactor** — behaviour of the existing action unchanged.
   - [x] refactor  - [x] `./gradlew test`
4. **Action + popup + registration** — `plugin.xml` action `dev.myair.NewClaudeSessionInWorktree` (icon `AllIcons.Vcs.Branch`, Tools menu), toolbar group `DefaultActionGroup(newSession, newSessionInWorktree, newFolder, mode, refresh)`.
   - [x] implement  - [x] `./gradlew test buildPlugin`  - [ ] sandbox check below
5. **Settings** — `MyAirSettings` + `MyAirConfigurable` + `plugin.xml` registration; the action reads `service<MyAirSettings>().state.worktreesDir`.
   - [x] implement  - [x] `./gradlew buildPlugin`  - [ ] sandbox: change the directory to an absolute path, create a session, `git worktree list` shows it there; blank value is refused

## Verification

- `./gradlew test buildPlugin` green.
- Sandbox (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`), project = a clone with `origin`: popup shows the default branch first and preselected; pick `origin/<x>` → tab starts in `.claude/worktrees/<id8>`, `git worktree list` shows `worktree-<id8>` at the base sha; reopen popup → `origin/<x>` second and preselected; pick 6 different bases → only 5 recents; on my-air itself (no `origin`) the default is `main`; with `.env` gitignored and listed in `.worktreeinclude`, the new worktree has it.
- "New Claude Session" still starts in the project dir (cwd in the session tree).
