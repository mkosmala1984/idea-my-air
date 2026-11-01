# My Air

An IntelliJ plugin that brings Claude Code sessions into the IDE: it lists them, resumes them in editor tabs, shows what each one is doing right now, and switches to wherever a session already runs — across windows and across IDE instances.

A small take on [JetBrains Air](https://www.jetbrains.com/air/), built for everyday work with `claude` on Windows.

## What it does

**Sessions, from the files `claude` writes.** All state comes from `~/.claude`: the transcripts in `projects/<encoded-cwd>/<sessionId>.jsonl` and the live registry in `sessions/<pid>.json`. The plugin never talks to a `claude` process, so it sees sessions started anywhere — this IDE, another IDE, a terminal, Claude Desktop.

**Session tabs.** A session opens in an editor tab that runs `claude --resume <id>` and follows the process as it goes. The tab's icon is the session's status (no process, working, needs input, unread), with a branch badge when it runs outside its project directory, and another project's icon when the session belongs elsewhere. Tabs survive a restart: URL, order, splits and pins are saved by the platform, and a restored tab starts when you first select it. Closing a tab ends its `claude`.

**Claude Sessions tool window.** The session list, with a pinned list above it, a search field (Ctrl+F), and two tree modes:

- **Projects** — sessions grouped by project, newest project first.
- **Folders** — your own folders, with drag and drop, nested subfolders, plus a `recent` list (the 10 newest sessions) and `sessions` (everything not filed, by project). A folder can be bound to one or more project directories and then holds their sessions — worktrees included — without any filing. A folder can also carry a plan (a plan folder): milestones and tasks with a status and a next step, each task holding the sessions whose brief names it, the rest under `no task`. The structure lives in `~/.my-air/structure.json` and is shared by every IDE and by agents; every change leaves a snapshot that can be undone.

A group row shows how many sessions it holds and the most urgent status inside it. **View Options** sets density (Compact or Loose), font size, and hides sessions older than 3, 7, 14 or 30 days. Settled sessions stay out of the way until **Show All Sessions** is on.

**Starting sessions.** New sessions start from the main toolbar, the Claude Sessions toolbar or the Tools menu:

- **New Claude Session** — plain `claude` in the project directory.
- **New Claude Session in Worktree…** — pick a base branch and get a new worktree on its own branch, laid out as `worktree-<name>` in `<worktrees dir>/<name>`, with `.worktreeinclude` files copied in. The picked base is stored per branch and carried by `git branch -m`.
- **New Claude Session with Prompt…** — a composer in a popup, Air style, also filling the empty editor area when no file is open: first message, model, effort, configuration, workspace (current checkout or a new worktree) and branch. Pasted images and files become `@path` mentions.

**Session configurations.** Named presets (`--model`, `--effort`, project directory, worktree base, `--add-dir` directories, own toolbar button) in `~/.my-air/presets.json`, edited in Settings › Tools › My Air. A session's menu offers **New Session with Same Settings** and **Save Settings as Configuration…**.

**Session Context tool window.** Cards describing the selected session tab: model and effort, a **continue this session** button, project, worktree, branch and its base with the changes since it (per-file diff, combined diff, tree, or a Plannotator review), added directories, files written outside the work tree, `md`/`html` outputs, artifacts, background tasks with their status and usage, MCP servers and skills (grouped by plugin, pinnable, and typeable into the session's prompt).

**Attention.** Sessions that need input or have an unread reply are bold in the tree and the list, and marked on their tab. **Next Claude Session Needing Me** (Alt+Shift+K) cycles through them. **Close and Settle** closes a tab and marks the session done.

**Focus across IDEs.** Opening a session goes, in order: a tab in this IDE → a tab in another IDE running this plugin (that IDE is brought to the foreground) → a warning with the window of the program already running it → a new tab. **Focus Claude Session…** (Tools menu) picks a session open in any IDE and switches to its window and tab.

**Taskbar widget (`taskbar/`).** A small WPF app that lives in the Windows 11 taskbar: counts of sessions needing input, with an unread reply, and working; a hover flyout lists them; a bubble announces new arrivals; a click opens the session in the IDE. The IDE writes `~/.my-air/taskbar/board.json`; the app just reads it and sends focus requests back.

**MCP tools**, offered through the IDE's MCP server (the bundled MCP Server plugin, Settings › Tools › MCP Server) when it is enabled:

| Tool | Purpose |
| --- | --- |
| `my_air_sessions_find` | Find sessions by date, folder, filed/unfiled state and words in the title or project path |
| `my_air_folders_get` | Read the folder structure |
| `my_air_folders_apply` | Create, rename, move and delete folders, bind and unbind directories, make a folder a plan folder, file sessions — one all-or-nothing write |
| `my_air_plan_get` | Read the plans of the project a directory belongs to (every plan folder inside its bound folder), with the sessions of each task |
| `my_air_plan_apply` | Add, update, move and delete milestones and tasks of one plan, named by its folder path — one all-or-nothing write |
| `my_air_history_list` | List snapshots of earlier structure changes |
| `my_air_history_restore` | Undo one of them by name |
| `my_air_set_brief` | Let a session describe its goal and tasks, and name the plan task it works on, shown in the tree and the context pane |

## Requirements

- IntelliJ IDEA 2026.2 or newer (build 262+), with the Terminal plugin.
- Optional: the MCP Server plugin (bundled with the IDE) with its server enabled, for the MCP tools. Without it the plugin works, just without them.
- [Claude Code](https://docs.claude.com/en/docs/claude-code) installed and on `PATH`.
- Windows in practice: Win32 calls go through JNA.

## Build

The build compiles against a locally installed IDE rather than a downloaded one, so point both places at your installation:

- `build.gradle.kts` — `intellijPlatform { local("E:/dev/apps/jetbrains/IntelliJ IDEA Ultimate") }`
- `gradle.properties` — `org.gradle.java.installations.paths=.../IntelliJ IDEA Ultimate/jbr`

The IDE's classes are Java 25, hence `jvmToolchain(25)` and its own JBR. Then:

```bash
./gradlew test buildPlugin        # tests, then build/distributions/my-air-0.1.0.zip
./gradlew test --tests dev.myair.store.TabStoreTest
./gradlew test --tests 'dev.myair.store.TabStoreTest.a withdrawn*'
```

Install the zip with **Settings › Plugins › ⚙ › Install Plugin from Disk…**, then restart the IDE.

To run a sandbox IDE instead, drop the `CLAUDE*` variables of the session that launches it (`CLAUDE_CODE_CHILD_SESSION` would make every `claude` in a sandbox tab stop saving transcripts) and keep the daemon from passing them on:

```bash
env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde
```

The sandbox log is `.intellijPlatform/sandbox/my-air/IU-2026.2.3/log_runIde/idea.log`. A restarted sandbox does not pick up a new `buildPlugin` by itself.

The taskbar app needs .NET 10; `pwsh taskbar/build.ps1` runs its tests, builds it in Release and starts it.

## Settings

**Settings › Tools › My Air**:

- **Worktrees directory** — where new worktrees go, default `.claude/worktrees`, relative to the main checkout.
- **Session briefs** — offer `my_air_set_brief` to agents and show what they write.
- **Session tab width** — columns of the `claude` terminal, centered in a wider tab (0 fills it).
- **Session Configurations** — the presets in `~/.my-air/presets.json`.

## Files it keeps

Everything under `~/.my-air/`, shared by every IDE:

| Path | What |
| --- | --- |
| `ides/<pid>.json` | The session tabs of each running IDE |
| `requests/<pid>/` | "Focus this session" requests from other IDEs and the taskbar app |
| `pins.json` | Pinned, dismissed and settled sessions |
| `structure.json`, `history/` | The folder structure and its snapshots |
| `presets.json` | Session configurations |
| `seen/<id>` | When each session was last seen, for unread marks |
| `taskbar/board.json` | The board the taskbar app reads |
| `images/` | Images pasted into a prompt (older than 30 days are pruned) |
| `scan-cache-<hash>.json` | Per-jar transcript scan offsets |

## Development

Code, comments and strings are in English. `CLAUDE.md` describes how the pieces fit together and the exact shape of the files `claude` writes; read it before changing the scanner or the registry.

Packages under `dev.myair`, by feature: `claude` (reading `claude`'s files), `store` (`~/.my-air` files, atomic writes), `folders` (folder model and history), `mcp` (the toolsets), `service` (`ClaudeSessionsService`, the taskbar board, tab re-targeting), `editor` (session tabs and the opener), `tabs` (finding tabs and windows, cross-IDE sync), `actions` (menu actions, worktrees, `Git`), `toolwindow` (the panels), `prompt` (the new-session composer). Tests sit in the package of what they test.

Tests are plain JUnit 5 — no IntelliJ test framework — so logic that needs tests is kept out of IDE-service code. Classes named in `plugin.xml` and `my-air-mcp.xml` are resolved at runtime only; moving one means updating the XML too.
