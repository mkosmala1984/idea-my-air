# Sessions that file and title themselves

## TL;DR

- **Goal:** the session tree organizes itself. With each `my_air_set_brief` call, the agent of a session names the folder it belongs in and a short title, without asking the user.
- **Approach:** `my_air_set_brief` gets two optional fields, `folder` (a path) and `title`. As with `plan_task`, nothing records the session itself: the scanner reads the call from the transcript. The folder is read from the tool's **result**, which names the folder's id, so a renamed or moved folder keeps its sessions. A missing folder is created in `structure.json` (snapshot `brief`), at most one level below an existing folder, never at the top level and never in a plan folder.
- **Order of places:** `plan_task` → filed by hand → explicitly unfiled by hand (new, "nowhere") → the brief's folder → the folder bound to the project → `sessions`. **Order of titles:** `custom-title` (`/rename`) → the brief's `title` → `ai-title` → the first prompt.
- **Sticky fields:** `folder`, `title` and now also `plan_task` keep their last value when a later call leaves them out. An agent cannot clear them.
- **Setting:** a new checkbox "Agents file and title their sessions" (on by default, effective only with briefs on). Off: the tool refuses `folder` and `title` and the prompt does not mention them.
- **Decisions for you:** none open. Assumed: (1) "nowhere" also beats the project binding, so a bound project's session can now be dropped on `sessions`; (2) the setting turned off leaves places and titles already given in effect; (3) `structure.json` goes to version 4, which an older My Air refuses to read.
- **Risks:** an agent can name folders badly (`rtm/bugfixes` next to `rtm/fixes`). The guards are case-insensitive matching, the one-level rule and the instruction to read `my_air_folders_get` first; undo through history remains. A session resumed from before this change gets the new instructions only after its next compaction.
- **Out of scope:** sorting the backlog of old sessions in batches, writing titles back to `claude` (`claude --resume`, Desktop and Air keep their own), Desktop sessions (they get no My Air system prompt).

The decision register for this design is `.claude/decisions/2026-11-06-15e92858-89c6-43b5-b3a5-c58eafa55ee8.md`.

## Why

Every session lands in its project's folder or in `sessions`, titled by `claude`'s `ai-title` or by its first prompt. Sorting sessions into folders and renaming them is manual work that grows with every session. The agent of a session already describes the session with `my_air_set_brief` at the right moments, and it knows best what the session is for.

## Behaviour

### `my_air_set_brief`

```
my_air_set_brief(goal, tasks, plan_task?, folder?, title?)
```

| Input | Result |
|---|---|
| `folder` and `title` left out | `ok`, as today |
| `title`: blank or longer than 60 chars | error `title must be non-blank, at most 60 chars` |
| `folder` names an existing folder outside every plan folder (matched ignoring case) | `ok, folder <id> at '<path>'`; nothing is written |
| `folder` names a missing folder whose parent exists, outside every plan folder | the folder is created (history snapshot with source `brief`); `ok, folder <id> at '<path>' (created)` |
| `folder` with one segment that does not exist (top level) | error: new folders go below an existing folder |
| `folder` whose parent does not exist either | error naming the missing parent |
| `folder` names a plan folder or lies in one | error: sessions in a plan are placed by `plan_task` |
| `folder` or `title` while the setting is off | error: filing sessions is turned off |

When a call is refused, nothing is written and the transcript marks the result `is_error`, so the scanner skips the call, as it skips refused briefs today.

### Reading the brief

- `Brief` gets `title: String?` and `folder: String?`, the id of the folder.
- An accepted `my_air_set_brief` call sets `title` and `plan_task` from its input and `folder` from its result text (`ok, folder <id> at ...`). A field the call leaves out keeps its previous value. A result that is neither `ok` nor `ok, folder ...` throws.
- `update_plan` (Desktop) keeps all three.

### Place of a session (`SessionFolders.placeOf`)

1. The plan folder of the task named by `plan_task`, while that task exists.
2. The folder the session is filed in by hand (`items`).
3. `sessions`, when the user dropped the session there (`unfiled`, new).
4. The brief's folder, while it exists and does not lie in a plan folder.
5. The folder bound to the session's project.
6. `sessions`.

Filing a session in any folder removes it from `unfiled`. Dropping it on `sessions` in the tree adds it to `unfiled`, for sessions of bound projects too, which the tree refuses today. The MCP `assign` with `path: null` keeps its meaning: it takes the session out of its folder without marking it unfiled.

### Title of a session

`custom-title` → the brief's `title` → `ai-title` → the first line of the first prompt (80 chars). Tabs, the tree, the pinned list, the taskbar board and Session Context all use `ClaudeSession.title`, so they all follow.

### `structure.json` version 4

A root field `unfiled`: an array of session ids, always written. Versions 1 to 3 read with an empty list. A session id both filed and listed in `unfiled` throws.

### Setting and prompt

- `MyAirSettings.fileSessions` (default `true`), checkbox below the briefs checkbox and enabled only while briefs are on.
- With both settings on, `appendedSystemPrompt` adds: give `title` (at most 60 chars, the user's language) in the first brief and change it only when the goal changes; before naming a `folder`, read `my_air_folders_get`, pick the existing folder that fits, and name a new one only when none does, one level below an existing folder; the user's own filing beats `folder`.
- The `my_air_history_list` description names the source `brief`.
