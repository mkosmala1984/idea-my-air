# dev.myair

## Overview

The root package of the My Air plugin. It holds one file, `MyAirSettings.kt`: the IDE-wide settings and their page in
Settings › Tools › My Air, which also hosts the Claude Profiles group (its editor is `actions/ProfileSettings.kt`). Everything else lives in the feature packages below.

## Interface

- `MyAirSettings` (`@Service`, `@State(name = "MyAirSettings", storages = [Storage("myAir.xml")])`): `worktreesDir`,
  `briefs`, `fileSessions`, `terminalColumns`, `keepDetached`, `jiraTools`, `MyAirSettings.getInstance()`.
- `MyAirSettings.Options` (`BaseState`): defaults `worktreesDir = ".claude/worktrees"`, `briefs = true`,
  `fileSessions = true`, `terminalColumns = 0`, `keepDetached = true`,
  `jiraTools = "mcp__atlassian__getJiraIssue mcp__atlassian__getAccessibleAtlassianResources"`.
- `MyAirConfigurable` (`BoundConfigurable("My Air")`), registered in `plugin.xml` as `applicationConfigurable`
  `dev.myair.settings` under `tools`; it embeds `actions.ProfilesEditor` in the group Claude Profiles (`reload()` on
  reset) and `actions.PresetsEditor` for `~/.my-air/presets.json`.
- Feature packages:
  - [actions](./actions/README.md): menu and toolbar actions that start, find and switch sessions; `Git`, worktrees,
    diffs against a base, presets, branch naming.
  - [branchdiff](./branchdiff/README.md): `SessionDiffTargetProvider` for the optional Branch Diff plugin.
  - [claude](./claude/README.md): reading what `claude` writes (transcripts, live registry, Desktop metadata, skills)
    and `ClaudeHomeWatcher`.
  - [dashboard](./dashboard/README.md): the Sessions dashboard tab, its git facts and Haiku analyses.
  - [editor](./editor/README.md): session tabs, `SessionTerminals`, `ClaudeSessionOpener`, close and exit confirmations.
  - [folders](./folders/README.md): `SessionFolders`, plans, `structure.json` store and history, agent change lists.
  - [mcp](./mcp/README.md): the `my_air_*` MCP tools (`FoldersMcpToolset`, `BriefMcpToolset`, `ReviewMcpToolset`).
  - [planview](./planview/README.md): the plan editor tab and its manager session.
  - [prompt](./prompt/README.md): the new-session composer (`PromptPanel`, `PromptEmptyState`).
  - [review](./review/README.md): review folders (`ReviewService`, review operations, reviewer runs, prompts, the plan
    mirror).
  - [reviewview](./reviewview/README.md): the review tab and New Review.
  - [schedule](./schedule/README.md): scheduled tasks (store, slots, run records, `ScheduledRuns`, the registry tab).
  - [search](./search/README.md): the conversation index `SessionIndex` (`~/.my-air/search.db`).
  - [service](./service/README.md): `ClaudeSessionsService`, `ActiveSession`, tab re-targeting, the taskbar board.
  - [store](./store/README.md): files under `~/.my-air` and `writeAtomically`.
  - [tabs](./tabs/README.md): finding tabs and windows, cross-IDE sync (`SessionHosts`, `TabsSync`).
  - [toolwindow](./toolwindow/README.md): the Claude Sessions and Session Context tool windows.

## Invariants

- `worktreesDir` throws (`checkNotNull`) when unset; the page refuses a blank directory on apply.
- `fileSessions` is true only while `briefs` is on; the page disables its checkbox while briefs are off.
- `jiraTools` splits the stored string on whitespace and drops empty parts.
- Presets are edited only when `presets.json` parsed at reset (`saved != null`); a broken file is named in red and left
  to be fixed by hand, never overwritten.

## Side Effects

- Settings persist in the IDE's `myAir.xml`.
- The Claude Profiles group acts at once, not on Apply: Add copies the ticked items from the default profile
  (`copyFromDefault`), creates the profile's directories (`createProfileDirs`),
  writes `profiles.json`, calls `ClaudeSessionsService.refresh()`, opens a session tab of the profile
  (`ClaudeSessionOpener.startNew`) and shows the `claude mcp add` command; Remove writes `profiles.json` and refreshes.
- Apply writes `presets.json` through `PresetStore.write` whenever it parsed at reset, changed or not, calls
  `ClaudeSessionsService.refresh()`, and revalidates every open `SessionPaneHost` pane so a new tab width takes effect.

## Verification

- Run: `./gradlew test buildPlugin` (no tests live directly in `dev.myair`)
