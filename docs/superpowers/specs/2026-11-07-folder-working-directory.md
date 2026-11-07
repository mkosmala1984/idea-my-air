# Folder working directory

## TL;DR

- **Goal:** any folder of the tree can name a directory/repository in which new sessions started from it and its subfolders run, worktrees included, without binding that directory (a bound directory stays unique and pulls sessions in; this one does neither).
- **Approach:** a new optional `Folder.workdir` (one path). A new session's directory is taken from the nearest folder up the path that has a `workdir` or a bound dir; in one folder `workdir` beats `dirs.first()`. A session started from a folder's menu is filed in the clicked folder.
- **MCP:** `workdir` in `my_air_folders_get`, `set_workdir` in `my_air_folders_apply`, a `folder` parameter in `my_air_session_start`.
- **Storage:** `workdir` in `structure.json` version 4, written only when set; a build before this one fails loudly once a folder has one.
- **Decisions needing input:** none left open.
- **Risk:** filing every session started from a folder's menu changes today's behaviour for bound folders too (a session started from `rtm-cloud/support` lands in `support`, not `rtm-cloud`). Agreed.
- **Out of scope:** the prompt panel and `New Session with Prompt`, presets and New Session with Same Settings, bind semantics.

## Behaviour

### Resolving the directory of a new session (`nodeDir`)

Walk from the selected row up to the root; the first folder having `workdir` or a non-empty `dirs` decides:

| That folder has | Directory |
|---|---|
| `workdir` (with or without `dirs`) | `workdir` |
| `dirs` only | `dirs.first()` (today's behaviour) |

A nearer folder always beats a farther one. A `workdir` that no longer exists is still the answer: the new-session actions stay visible and disabled (`isEnabled = dir.isDirectory()`); the walk does not go on to an ancestor. No folder up the path with either: the actions are hidden, as today. Session rows, project rows and the projects mode are unchanged.

`New Claude Session in Worktree...` takes the same directory: its repository's main checkout holds the new worktree (`createWorktree`, unchanged), the session starts in the worktree.

### Filing the new session

A session started from a folder's menu (either new-session action, folders mode, selection a `Folder`) is filed in the selected folder (`SessionFolders.assign(id, selectedFolder)`), whichever folder supplied the directory. A manual filing beats a brief's `folder` in `placeOf`, so an agent's `my_air_set_brief` does not move it; its title still applies. Rows of a plan (milestone, task, `no task`) keep today's `PlanStart` and are not filed by this rule. The project row (outside folders) and the projects mode: unchanged, not filed.

### Tree

- Menu, next to Bind/Unbind Directory, on every folder (plan folders included): `Set Working Directory...` (folder chooser; also replaces an existing one) and `Clear Working Directory` (only when set). Through `FolderEditing`, recorded in history like the other edits.
- Folder row: icon unchanged (a `workdir` does not make a folder a project). After the name and bound dirs, in grey: `⌂ <dirLabel(workdir)>` followed by its branch in brackets (`Git.headBranch`, read off the EDT as for bound dirs). A missing `workdir` in grey italics.
- Folder tooltip: `New sessions start in: <path> (from <folder path>)`, naming the folder that decided, a bound ancestor included; nothing when no folder decides.
- Subfolders do not show an inherited `workdir` in their row.

### MCP

- `my_air_folders_get`: every folder gets `"workdir": <path or null>` beside `dirs` (`McpJson`, both places that write `dirs`). Its description says what it does.
- `my_air_folders_apply`: change `{"op": "set_workdir", "path": <folder path>, "dir": <directory or null>}`. A non-null `dir` must be an existing directory; null clears it; an unknown folder throws. History source as the other folder changes.
- `my_air_session_start`: optional `folder` (a folder path, matched as elsewhere). Without `dir`, the directory comes from `folder` by the rule above; no folder up the path decides → error back to the agent. With `dir` too, `dir` is the directory. Either way the session is filed in `folder`. With `plan_task` too: `plan_task` decides where it shows (task beats filing), filing still happens. `SessionSystemPromptTest` keeps covering the tool list; the system prompt line for `my_air_session_start` mentions `folder`.

### Storage

`Folder.workdir: String?`. `structure.json` stays version 4; `workdir` is in `FOLDER_FIELDS[4]`, read with `json.get("workdir")?.asString`, written only when set (Gson leaves out nulls). A blank `workdir` throws on parse, as a blank bound dir does. Several folders may share one `workdir`, and a `workdir` may equal a bound dir of any folder.

## Tests

- `SessionFoldersTest`: parse/write round trip with and without `workdir`; a file without it equals today's output byte for byte; blank throws.
- Directory resolution as a pure function over the path of folders (extract from `nodeDir`): nearest wins; `workdir` beats `dirs` in one folder; missing `workdir` returned, not skipped; none → null.
- `FolderChangesTest`: `set_workdir` sets, replaces, clears; missing directory and unknown folder throw.
- `my_air_session_start` input handling (`sessionStartPreset`): `folder` alone resolves the directory; `folder` with no deciding ancestor fails; `dir` wins over `folder`.
- `McpJson`: `workdir` present, null when unset.
