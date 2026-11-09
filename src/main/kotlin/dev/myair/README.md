# dev.myair

## Overview

The root package of the My Air plugin. It holds one file, `MyAirSettings.kt`: the IDE-wide settings and their page in
Settings › Tools › My Air. Everything else lives in the feature packages below.

## Interface

- `MyAirSettings` (`@Service`, `@State(name = "MyAirSettings", storages = [Storage("myAir.xml")])`): `worktreesDir`,
  `briefs`, `fileSessions`, `terminalColumns`, `keepDetached`, `jiraTools`, `MyAirSettings.getInstance()`.
- `MyAirSettings.Options` (`BaseState`): defaults `worktreesDir = ".claude/worktrees"`, `briefs = true`,
  `fileSessions = true`, `terminalColumns = 0`, `keepDetached = true`,
  `jiraTools = "mcp__atlassian__getJiraIssue mcp__atlassian__getAccessibleAtlassianResources"`.
- `MyAirConfigurable` (`BoundConfigurable("My Air")`), registered in `plugin.xml` as `applicationConfigurable`
  `dev.myair.settings` under `tools`; it embeds `actions.PresetsEditor` for `~/.my-air/presets.json`.
- Feature packages:
  - [actions](./actions/README.md): menu and toolbar actions that start, find and switch sessions; `Git`, worktrees,
    diffs against a base, presets, branch naming.
  - [branchdiff](./branchdiff/README.md): `SessionDiffTargetProvider` for the optional Branch Diff plugin.
  - [claude](./claude/README.md): reading what `claude` writes (transcripts, live registry, Desktop metadata, skills)
    and `ClaudeHomeWatcher`.
  - [editor](./editor/README.md): session tabs, `SessionTerminals`, `ClaudeSessionOpener`, close and exit confirmations.
  - [folders](./folders/README.md): `SessionFolders`, plans, `structure.json` store and history, agent change lists.
  - [mcp](./mcp/README.md): the `my_air_*` MCP tools (`FoldersMcpToolset`, `BriefMcpToolset`).
  - [planview](./planview/README.md): the plan editor tab and its manager session.
  - [prompt](./prompt/README.md): the new-session composer (`PromptPanel`, `PromptEmptyState`).
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
- Apply writes `presets.json` through `PresetStore.write` when presets changed, calls
  `ClaudeSessionsService.refresh()`, and revalidates every open `SessionPaneHost` pane so a new tab width takes effect.

## Verification

- Run: `./gradlew test buildPlugin` (no tests live directly in `dev.myair`)
