# Initial prompt in session configurations, project choice in the composer

## TL;DR

- **Goal:** a configuration that starts sessions with a fixed beginning of the first message (`/code-review`, `/superpowers:brainstorming`) and lets the user add the rest at start (a PR number, a Jira key, a topic).
- **Approach (chosen: A):** a configuration gets `prompt` and `askMore`. With `askMore`, its toolbar button opens the existing composer (`PromptPanel`) with the configuration chosen and its prompt typed in, caret at the end; without it the session starts at once with the prompt.
- **Also:** the composer's bar gets a project chip left of the branch chip; the branch list follows the chosen project.
- **Needs your input:** the projects the chip lists. Proposed: the IDE's open projects, then its recent projects (the Welcome screen's list), searchable.
- **Risk:** opening the composer from a button must not destroy an unsent draft before the user types.
- **Out of scope:** placeholders inside the prompt (`{input}`), custom icons, a prompt in "New Session with Same Settings".

## Requirements

- **R1** `Preset` has `prompt` (null or non-blank, multi-line allowed) and `askMore` (default false); `presets.json` reads and writes both, an old file without them reads as before, and an unknown key still throws.
- **R2** The configuration dialog has an "Initial prompt" text area and a checkbox "Open the composer to add to it before the start".
- **R3** A configuration's toolbar button or dropdown entry without `askMore` starts the session with `prompt` as its first message (none when null), as today otherwise.
- **R4** With `askMore`, the button opens the composer popup with that configuration chosen (its model, effort, workspace, base and project), the message field holding `prompt` followed by one space (empty when no prompt), caret at the end; Enter starts it.
- **R5** Choosing a configuration in the composer's configuration chip puts its prompt into the message field when the field is empty or still holds the previous configuration's prompt unedited; otherwise the text stays.
- **R6** A composer opened from a configuration does not overwrite the stored draft until the user changes the message.
- **R7** The composer's bar shows a project chip left of the branch chip: the chosen directory's name with its project icon; its popup lists the open projects, then the recent projects, without repeats, filtered by typing.
- **R8** Choosing a project re-reads the branches of that directory's repository and the session starts there (the configuration's `project` replaced for this start), its tab opening in the current window.
- **R9** The project chip opens on the configuration's project, else the window's project; choosing another configuration resets it to that configuration's; the last start's and the draft's project are restored like model and effort.
- **R10** The configuration's tooltip (`summary`) names its prompt (first line, shortened) and whether it opens the composer.

## Approaches

- **A. Composer with the prompt typed in (chosen).** Reuses `PromptPanel`: at start the model, effort, worktree, Suggest (a branch name from a Jira key) and image paste stay at hand, and the user sees and edits the whole message, so no joining rule is needed. Costs a bigger window than one field and the draft rule (R6).
- **B. A small popup for the addition alone.** Fastest for a PR number, but a second composer in the code with none of the above.
- **C. A template with `{input}`.** Lets the argument sit mid-prompt; no example needs it, and it is one more concept.

## Design

- `store/Presets.kt`: `Preset.prompt: String?`, `Preset.askMore: Boolean`, both in `PRESET_KEYS`, validated in `init` (prompt non-blank when set). `claudeArgs(prompt)` unchanged: the caller passes the message.
- `actions/PresetSettings.kt`: `PresetDialog` gains the text area and checkbox.
- `actions/PresetActions.kt`: `PresetAction.actionPerformed` either calls `startPreset(project, preset, where, preset.prompt)` or opens the composer popup for `preset`. The popup code of `NewSessionWithPromptAction` becomes one function taking an optional preset, used by both.
- `prompt/PromptPanel.kt`: a constructor parameter for the opening configuration; a project chip (`Chip` with the project icon from `RecentProjectIconHelper`, popup through `choose(..., search = true)`); the directory read by `repoReads` comes from the chosen project instead of `preset.dir(...)`; the message set before the document listener is attached, so R6 holds.
- `prompt/PromptChoices.kt`: `Choice` and `LastChoice` gain the project directory; `toPreset` puts it into `project`; `restore` and `opening` carry it.
- Open projects: `ProjectManager.getInstance().openProjects`; recent: `RecentProjectsManagerBase.getInstanceEx().getRecentPaths()`; a recent path that no longer exists is left out.

## Left to the builder

- Shortening of the prompt in the tooltip and the project chip's width.
- Whether the project popup tags entries ("open", "configuration").
