# Initial Prompt in Configurations, Project Choice in the Composer — Implementation Plan

## TL;DR

- Goal: a configuration carries a first message (`prompt`) and may open the composer with it typed in (`askMore`); the composer gets a project chip left of the branch chip.
- Approach: two `Preset` fields; pure rules in `PromptChoices.kt` (message on switch, project list, project in the choices); `PromptPanel` takes an opening configuration; `PresetAction` either starts or opens the composer.
- Decisions needing your input: none (the project list was confirmed: open projects, then recent ones).
- Risk: the draft rule (R6) depends on setting the message before the document listener; checked by hand in the sandbox.
- Out of scope: `{input}` templates, custom icons, a prompt in New Session with Same Settings.

| Task | Status | Commit |
|---|---|---|
| 1 | done | `bcc5413` |
| 2 | done | `337bc9c` |
| 3 | done | `e8dbfd8` |
| 4 | done | `8e976c6` |
| 5 | blocked: the sandbox walk (step 2) needs clicks in the IDE | |

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configurations start sessions with a fixed beginning of the first message, optionally completed in the composer, and the composer can start in another IDE project.

**Architecture:** `Preset` gains `prompt` and `askMore`, persisted in `presets.json`. The composer's pure state (`Choice`, `LastChoice`) gains the project directory; the rules for the message and the project list are pure functions in `prompt/PromptChoices.kt`, tested; `PromptPanel` and `PresetAction` only wire them.

**Tech Stack:** Kotlin, IntelliJ Platform 263 (Swing, Kotlin UI DSL, `JBPopupFactory`), Gson, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-13-preset-prompt-design.md`

## Global Constraints

- Code, comments and strings in English.
- Unknown keys in `presets.json` still throw; missing new keys read as not set (`prompt` null, `askMore` false).
- A blank `prompt` is refused (`require`), never silently dropped.
- No fallbacks: a recent project path that does not exist is left out of the list (a rule, R7), everything else that is wrong throws.
- Run tests with `./gradlew test --tests <class>`; the full suite with `./gradlew test buildPlugin`.

## Review Focus

- A configuration with `askMore` and no `prompt`: the composer opens with that configuration and an empty message (test in Task 2: `opening message of a configuration`).
- Switching configurations after the user appended text to the previous prompt (`/code-review 7506`): the text stays (test in Task 2: `switch keeps edited text`).
- A configuration whose `project` equals an open project: it appears once in the project list, not twice (test in Task 2: `project list has no repeats`).
- The last start's project no longer exists: the panel opens on it and the start reports the missing directory as today (`allExist`), it is not replaced by another (checked by hand in Task 5).
- A multi-line prompt in `presets.json` survives a round trip (test in Task 1).

---

### Task 1: `prompt` and `askMore` in `Preset` and `presets.json`

**Files:**
- Modify: `src/main/kotlin/dev/myair/store/Presets.kt` (class `Preset`, `parsePresets`, `PRESET_KEYS`)
- Modify: `src/main/kotlin/dev/myair/actions/PresetActions.kt` (`Preset.summary`)
- Test: `src/test/kotlin/dev/myair/store/PresetsTest.kt`

**Interfaces:**
- Produces: `Preset(..., pinned: Boolean = false, prompt: String? = null, askMore: Boolean = false)` — new parameters last, so existing positional calls stay valid.

- [x] **Step 1: Write the failing tests** in `PresetsTest`:
  - extend `survives a round trip through json, also without optional values` with `Preset("Review", prompt = "/code-review\nthen wait", askMore = true)` in the list.
  - extend `reads the file as written by hand, absent keys meaning not set` with `{"name": "Rv", "prompt": "/code-review", "askMore": true}` expecting `Preset("Rv", prompt = "/code-review", askMore = true)`.
  - extend `rejects unknown keys, ...` with `{"name": "a", "prompt": " "}` and `{"name": "a", "askMore": "yes"}`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.store.PresetsTest` — expect compile failure (no `prompt` parameter).
- [x] **Step 3: Implement**: the two constructor parameters; `prompt` joins the blank check in `init`; `parsePresets` reads `preset.string("prompt")` and `preset.boolean("askMore")`; both keys in `PRESET_KEYS`. In `summary()` add, after the directories, `prompt?.let { "starts with \"${it.lineSequence().first().take(40)}\"" }` and `"opens the composer".takeIf { askMore }`.
- [x] **Step 4: Run** the same command — PASS.
- [x] **Step 5: Commit** `git commit -m "Session configurations carry a first message and may open the composer"` with trailers `Plan-Task: 1/5` and `Plan: docs/superpowers/plans/2026-11-13-preset-prompt.md`.

### Task 2: Pure composer rules: message, project list, project in the choices

**Files:**
- Modify: `src/main/kotlin/dev/myair/prompt/PromptChoices.kt`
- Test: `src/test/kotlin/dev/myair/prompt/PromptChoicesTest.kt`

**Interfaces:**
- Consumes: `Preset.prompt` (Task 1).
- Produces:
  - `Choice(..., newBranch: Boolean = false, project: String? = null)`; null means the window's project. `choiceOf(preset)` sets `project = preset.project`. `toPreset(base)` also sets `project = project`.
  - `LastChoice(..., newBranch: Boolean? = null, project: String? = null)`; `Choice.toLast()` carries it; `restore` takes `last.project ?: own.project`.
  - `fun openingMessage(preset: Preset): String` — `prompt + " "`, or `""` without a prompt.
  - `fun switchedMessage(text: String, from: Preset, to: Preset): String` — `openingMessage(to)` when `text` is blank or `text == openingMessage(from)`, else `text`.
  - `fun projectChoices(open: List<String>, recent: List<String>, exists: (String) -> Boolean): List<String>` — open first, then recent, first occurrence kept, recent ones that do not exist left out; paths compared as `Path.of(it).normalize()` strings so `E:/a` and `E:\a` are one.

- [x] **Step 1: Write the failing tests**:
  - `opening message of a configuration`: `openingMessage(Preset("R", prompt = "/code-review"))` is `"/code-review "`; `openingMessage(Preset("P"))` is `""`.
  - `switch replaces an empty or untouched message`: from `Preset("A", prompt = "/a")` to `Preset("B", prompt = "/b")`: `""` → `"/b "`, `"/a "` → `"/b "`; to `Preset("C")` from `"/a "` → `""`.
  - `switch keeps edited text`: `"/a 7506"` stays `"/a 7506"`; `"own words"` stays.
  - `project list has no repeats`: `projectChoices(listOf("E:/a", "E:/b"), listOf("E:\\a", "E:/c", "E:/gone"), { it != "E:/gone" })` is `listOf("E:/a", "E:/b", "E:/c")`.
  - `the project is a choice like the model`: `restore(listOf(Preset("D"), Preset("P", project = "E:/p")), LastChoice("P", null, null, null, project = "E:/q")).project` is `"E:/q"`; with `project = null` it is `"E:/p"`; `choiceOf(Preset("P", project = "E:/p")).copy(project = "E:/x").toPreset(null).project` is `"E:/x"`.
- [x] **Step 2: Run** `./gradlew test --tests dev.myair.prompt.PromptChoicesTest` — compile failure.
- [x] **Step 3: Implement** the signatures above.
- [x] **Step 4: Run** the same command — PASS.
- [x] **Step 5: Commit** `"Composer rules for a configuration's message and the project choice"`, trailers `Plan-Task: 2/5`, `Plan: ...`.

### Task 3: Fields in the configuration dialog

**Files:**
- Modify: `src/main/kotlin/dev/myair/actions/PresetSettings.kt` (`PresetDialog`)

**Interfaces:**
- Consumes: `Preset.prompt`, `Preset.askMore` (Task 1).

- [x] **Step 1: Implement**: fields `private var prompt = preset?.prompt.orEmpty()` and `private var askMore = preset?.askMore ?: false`; after "Other directories:" a row `"Initial prompt:"` with `textArea().bindText(::prompt).rows(3).align(AlignX.FILL).comment("The session's first message, e.g. /code-review.")`, then `row { checkBox("Open the composer to add to it before the start").bindSelected(::askMore) }`; `preset()` passes `prompt.trim().ifEmpty { null }` and `askMore` by name.
- [x] **Step 2: Run** `./gradlew test buildPlugin` — BUILD SUCCESSFUL.
- [x] **Step 3: Commit** `"Edit a configuration's first message in its dialog"`, trailers `Plan-Task: 3/5`, `Plan: ...`.

### Task 4: Composer opened from a configuration, project chip

**Files:**
- Modify: `src/main/kotlin/dev/myair/prompt/PromptPanel.kt`
- Modify: `src/main/kotlin/dev/myair/actions/PresetActions.kt` (`PresetAction`)

**Interfaces:**
- Consumes: Task 2's functions and fields.
- Produces: `class PromptPanel(project: Project, started: () -> Unit, from: Preset? = null)`; `fun showPromptPopup(project: Project, from: Preset? = null)` in `PromptPanel.kt`, the body of today's `NewSessionWithPromptAction.actionPerformed`, which now calls it.

- [x] **Step 1: Implement in `PromptPanel`**:
  - Opening: with `from`, `choice = choiceOf(from)`, the message `openingMessage(from)`, caret at its end, and the draft neither loaded nor stored; set the text before `addDocumentListener`, so the stored draft is replaced only once the user changes the message (R6). Without `from`, as today.
  - `presetChip`'s callback: `prompt.text = switchedMessage(prompt.text, choice.preset, it)` before `selectPreset(choiceOf(it))`.
  - `loadChoice`/`saveChoice` read and write `"$key.project"`.
  - A `projectChip` (`Chip`) added to the bar's EAST row before `branchChip`: icon from `RecentProjectIconHelper().getProjectIcon(dir, ...)` as `SessionIcons.kt` uses it, text the directory's file name; its popup is `choose(chip, projectChoices(open, recent, { Path.of(it).isDirectory() }), dir, { Path.of(it).fileName.toString() }, { it }, search = true)` with `open = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }` and `recent = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()`; choosing calls `selectProject(path)`, which sets `choice = choice.copy(project = path)` and re-reads branches like `selectPreset`.
  - Every `choice.preset.dir(projectBase(project))` becomes the directory of `choice.project ?: projectBase(project)`.
- [x] **Step 2: Implement in `PresetAction.actionPerformed`**: `if (preset.askMore) showPromptPopup(project, preset) else startPreset(project, preset, popupPoint(e), preset.prompt)`.
- [x] **Step 3: Run** `./gradlew test buildPlugin` — BUILD SUCCESSFUL.
- [x] **Step 4: Commit** `"Open the composer from a configuration, choose the project in it"`, trailers `Plan-Task: 4/5`, `Plan: ...`.

### Task 5: Docs and sandbox check

**Files:**
- Modify: `CLAUDE.md` (the `Preset` and `PromptPanel` paragraphs)
- Modify: `src/main/kotlin/dev/myair/prompt/README.md`

- [x] **Step 1: Update docs**: `Preset` gains the first message and the composer option; `PromptPanel` gains the project chip (open, then recent projects) and the opening configuration with the message rules and the draft rule.
- [ ] **Step 2: Sandbox walk** (not done: needs the user) (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`): create "Review" (`/code-review`, askMore, own button) and "Quick" (`/help`, no askMore); click Quick → session starts with `/help`; click Review → composer with `/code-review `, type `7506`, Enter → session's first message `/code-review 7506`; with a draft stored, click Review and close without typing → the draft is back in the next composer; in the composer switch Review → Default with `/code-review ` untouched → empty; pick another project → branches change, session starts there; set the last project to a deleted folder → the start reports it missing.
- [x] **Step 3: Run** `./gradlew test buildPlugin` (full, not filtered) — BUILD SUCCESSFUL, then `my_air_spec_check` with this plan's absolute path if available.
- [x] **Step 4: Commit** `"Document configuration prompts and the composer's project chip"`, trailers `Plan-Task: 5/5`, `Plan: ...`.

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 1 | test `PresetsTest` › `survives a round trip through json, also without optional values`; test `PresetsTest` › `reads the file as written by hand, absent keys meaning not set`; test `PresetsTest` › `rejects unknown keys, wrong types, unknown efforts, a base without a worktree and repeated names`; commit `bcc5413` |
| R2 | 3 | commit `e8dbfd8`; not done: dialog not opened in the sandbox |
| R3 | 4 | commit `8e976c6`; not done: sandbox walk |
| R4 | 4, 5 | test `PromptChoicesTest` › `opening message of a configuration`; commit `8e976c6`; not done: sandbox walk |
| R5 | 2, 4 | test `PromptChoicesTest` › `switch replaces an empty or untouched message`; test `PromptChoicesTest` › `switch keeps edited text`; commit `8e976c6` |
| R6 | 4, 5 | commit `8e976c6`; not done: sandbox walk |
| R7 | 2, 4 | test `PromptChoicesTest` › `project list has no repeats`; commit `8e976c6` |
| R8 | 4, 5 | commit `8e976c6`; not done: sandbox walk |
| R9 | 2, 4 | test `PromptChoicesTest` › `the project is a choice like the model`; commit `8e976c6` |
| R10 | 1 | commit `bcc5413`; not done: tooltip not seen in the sandbox |
