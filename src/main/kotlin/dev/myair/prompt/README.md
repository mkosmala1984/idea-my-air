# prompt

## Overview

The new-session composer, styled after JetBrains Air's. Its card holds the first message and chips for the
configuration, model, effort and Claude profile, a Plan toggle and a send button. The bar under the card sets the
workspace (current checkout, a new worktree, a new worktree on a new branch, or an existing worktree picked in a chip of
its own), the project and the branch, with a New branch name row and a Plan name row shown only when they apply. The panel fills the empty editor area
(`PromptEmptyState`) and also opens as a popup (`NewSessionWithPromptAction`). `NewBranchPanel.kt` reuses the same
look to name a new worktree branch. The start itself happens in `dev.myair.actions.startPreset`.

## Interface

- `PromptPanel(project, started, from: Preset? = null)`: the composer, opened on configuration `from` with its prompt typed in when given. Its public `prompt: JBTextArea` takes the focus.
- `showPromptPopup(project, from)`: the composer in a popup; `NewSessionWithPromptAction` and a configuration with `askMore` open it.
- `PromptEmptyState : EditorEmptyStateComponentProvider`, registered in `plugin.xml` as `editorEmptyStateComponentProvider`.
- `NewSessionWithPromptAction`, registered in `plugin.xml` as `dev.myair.NewSessionWithPrompt`.
- `showNewBranchPopup(project, dir, base, chosen: (branch, prompt?) -> Unit)`: called by `actions.startInWorktree`'s branch popup.
- `profileLabel(name)`: `Default` for `default`, else the name, as the profile chip shows it.
- `MessageDraft(area, kept, save)` (`MessageDraft.kt`): ties the message to the stored draft; `set(text)` puts in text that is no user edit, `flush()` saves.
- `suggestInBackground(component, dir, text, done)`: runs `actions.suggestBranchName` on a pooled thread and hands the result to `done` on the EDT, in the component's modality.
- `PromptChoices.kt` (pure, covered by tests):
  - `DEFAULT_MODEL = "opus"`, `DEFAULT_EFFORT = "medium"`, `DEFAULT_PRESET = Preset("Default")`
  - `Choice(preset, model, effort, worktree, newBranch, project, existing, plan, profile)` with `toPreset(base, existingDir = null)`; `project` replaces the configuration's directory, null keeping it, `existingDir` replaces both; `profile` becomes the preset's, left out for `default`
  - `choiceOf(preset)`, `LastChoice` (the same choices, each nullable, the configuration by name), `Draft(text, choice, base)`, `Choice.toLast()`
  - `opening(presets, last, draft)`, `restore(presets, last)`, `branchTag(...)`, `modelLabel(model)`
  - `openingMessage(preset)`, `switchedMessage(text, from, to)`, `projectChoices(own, open, recent, exists)`, `LastChoice.present(exists)`, `dirName(dir)`
- `PastedImages.kt`: `PASTED_IMAGES` (`~/.my-air/images`), `IMAGES_KEPT` (30 days), `pruneImages(dir, cutoff)`, `pastedMentions(content, dir)`, `mention(path)`.
- `Composer.kt` (internal): the colours `CARD`, `BAR`, `SEND`, and `row(...)`, `Chip`, `Rounded`, `SendButton`, `choose(chip, items, selected, text, tag, title, search, chosen)` (a chip's list popup).

## Invariants

- `Choice` requires `effort in EFFORTS`, `newBranch` only with `worktree`, and not both `worktree` and `existing`.
  `toPreset` requires `existingDir` exactly when `existing`. `Draft` requires a non-blank `text`.
- `restore` keeps the last configuration while one of that name exists, else falls back to the first preset, with
  `Default` always first. Each last choice that is set overrides that configuration's own, `plan` and `profile`
  included. `newBranch` holds only when `worktree` does, `existing` only when it does not.
- `Choice.toPreset(base)` keeps a base only for a worktree. A null base means it is picked in the popup at start.
- The base stays while it is among the branches read for the configuration's directory. Otherwise it becomes the
  one `baseChoices` preselects. A repository read overtaken by a newer `LatestRead.request` never lands.
- A start whose directories are gone is refused in the error label, the message and the stored choices kept.
- A start goes in this order: save the last choices, compute the popup point from the screen, clear the message
  (which removes the stored draft) and the plan name, call `started`, call `startPreset(project, start, where, text,
  branch, plan)`. A new branch requires a non-blank name, Plan a non-blank plan name, and Existing worktree a worktree
  read and picked.
- The prompt goes to `claude` after `--` (through `Preset.claudeArgs`, called by `startPreset`).
- Paste is registered on the text area with the IDE's paste shortcut. Copied files and images become `@path`
  mentions, quoted when the path holds whitespace. Anything else falls through to the plain text paste.
- `savePng` checks that the image has a positive size and that a PNG writer exists.
- With an empty message, `update` does not overwrite another panel's draft. A panel opened from a configuration stores no draft until its message changes.
- Choosing a configuration replaces the message only while it is empty or the previous configuration's untouched `openingMessage`.

## Side Effects

- Application properties (`PropertiesComponent`): `dev.myair.prompt.{preset,model,effort,worktree,newBranch,project,existing,plan,profile}` hold the last start, and `dev.myair.prompt.draft.*` (`text`, `base` and the choice keys) hold the unsent draft, rewritten on every change while a message exists and removed when it is sent or emptied.
- Files: pasted images are written as `~/.my-air/images/<uuid>.png`. Every panel that opens deletes PNGs there older than 30 days, on a pooled thread.
- Reads `PresetStore` (`presets.json`) once per panel. A broken file is shown in the panel's error label.
- Reads the repository (`Git.repo`, `Git.currentBranch`, and its linked worktrees whose folders exist, newest first, each with `Git.headBranch`, through `gitWorktrees`) off the EDT on every configuration or project change.
- Reads `ProfileStore.all()` when the profile chip opens.
- Reads the open projects and `RecentProjectsManagerBase.getRecentPaths()` when the project chip opens; which of them exist is checked on a pooled thread. Stored choices naming a project gone lose it on load.
- Processes: Suggest runs `claude -p --model haiku` through `suggestBranchName`, on a pooled thread.
- Starts a session (and maybe a worktree) through `actions.startPreset`.

## Verification

- Run: `./gradlew test --tests 'dev.myair.prompt.*'` (`PromptChoicesTest`, `PastedImagesTest`, `MessageDraftTest`)
- Whole project: `./gradlew test buildPlugin`
