# Model and Effort Pairs in Configurations — Implementation Plan

## TL;DR

- Goal: a configuration may list several model–effort pairs; its toolbar button (and its dropdown entry) then shows them in a popup and starts the session with the one picked. With no pairs it works as today.
- Approach: `Preset.variants: List<Variant(model, effort)>`, empty or at least 2, and then `model`/`effort` null; picking one gives `preset.withVariant(v)`, a plain preset with that pair, so `startPreset`, the composer and worktrees stay unchanged.
- Decisions needing your input:
  1. Editing in `PresetDialog`: a text area, one pair per line (`opus high`, `sonnet`, `- low`), as Other directories is edited — recommended, least code; or a table with combo boxes.
  2. Where no pick is made (the composer's configuration chip, MCP `my_air_session_start` without `model`/`effort`): the first pair — recommended; or refuse in MCP naming the pairs.
- Risk: a nested popup from the dropdown; avoided by showing the pairs there as a submenu.
- Out of scope: pairs for review profiles and scheduled tasks (a task with pairs is refused), other per-pair settings (prompt, worktree).

| Task | Status | Commit |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One configuration button covers several model–effort pairs, chosen at the click.

**Architecture:** the pairs are data of `Preset` (`store/Presets.kt`), persisted in `presets.json`, parsed and validated there and in a pure `parseVariants` for the dialog. Every consumer receives an ordinary preset: `PresetAction` resolves the pair before calling `startPreset` or `showPromptPopup`; `choiceOf` and `sessionStartPreset` take the first pair.

**Tech Stack:** Kotlin, IntelliJ Platform 263 (`JBPopupFactory`, Kotlin UI DSL), Gson, JUnit 5.

## Requirements

- **R1** `Preset` has `variants: List<Variant>` (`Variant(model: String?, effort: String?)`, at least one of them set, effort in `EFFORTS`, model not blank); `init` requires `variants` empty or of size ≥ 2, no pair repeated, and `model == null && effort == null` when not empty.
- **R2** `presets.json` reads and writes `variants` as `[{"model": "opus", "effort": "high"}, {"model": "sonnet"}]`; a file without the key reads as before; an unknown key in a pair throws.
- **R3** A click on the button of a configuration with pairs opens a popup under the button listing the pairs as `Claude Opus · High` (`modelLabel`, effort capitalised; a missing part reads `claude's own setting`); picking one starts the session as a configuration without pairs having that model and effort would (`askMore` included: the composer opens with that pair chosen).
- **R4** In the configurations dropdown a configuration with pairs is a submenu of the same entries.
- **R5** A configuration without pairs behaves exactly as before (the click starts at once or opens the composer).
- **R6** `choiceOf(preset)` and `sessionStartPreset` with no `model`/`effort` given use the first pair; a given `model` or `effort` replaces the first pair's part.
- **R7** `ScheduledTask` refuses a preset with pairs.
- **R8** `PresetDialog` edits the pairs; a line that does not parse, a single pair, or a repeated pair is a validation error on the field; the summary (tooltip, Settings list) names the pairs.

## Requirements evidence

| R | Task | Evidence |
|---|---|---|
| R1 | 1 | |
| R2 | 1 | |
| R3 | 3 | |
| R4 | 3 | |
| R5 | 3 | |
| R6 | 2 | |
| R7 | 2 | |
| R8 | 4 | |

## Global Constraints

- English in code, comments and strings. No fallbacks: a malformed pair throws when read from the file.
- `Preset` keeps positional compatibility: `variants` is the last constructor parameter, default `emptyList()`.
- Existing tests in `PresetsTest`, `PromptChoicesTest`, `SessionStartTest` (if present) keep passing unchanged.

### Task 1: `Variant` and `Preset.variants` in `store/Presets.kt`

**Files:** `src/main/kotlin/dev/myair/store/Presets.kt`, `src/test/kotlin/dev/myair/store/PresetsTest.kt`

- Produces: `data class Variant(val model: String?, val effort: String?)`, `Preset.variants`, `fun Preset.withVariant(v: Variant): Preset = copy(model = v.model, effort = v.effort, variants = emptyList())` (requires `v in variants`), `val Preset.defaultPair: Variant?` (first pair, null without pairs).

- [ ] **Step 1: Tests first** in `PresetsTest`:
  - round trip of `Preset("Pick", variants = listOf(Variant("opus", "high"), Variant("sonnet", null)))`;
  - reading `{"name": "P", "variants": [{"model": "opus", "effort": "high"}, {"effort": "low"}]}`;
  - rejections: one pair; a repeated pair; pairs with `model` set on the preset; `{"model": "opus", "x": 1}`; `{}` (neither part); effort `"huge"`.
  - `withVariant` returns a preset without pairs and with that model and effort.
- [ ] **Step 2: Run** `./gradlew test --tests dev.myair.store.PresetsTest`, expect the new ones to fail.
- [ ] **Step 3: Implement**: `Variant.init` (`model != null || effort != null`, model not blank, effort in `EFFORTS`); `Preset.init` checks of R1; `parsePresets` reads `variants` (keys `model`, `effort` only); `"variants"` in `PRESET_KEYS`. Gson writes the list as is (null fields left out by default).
- [ ] **Step 4: Run** the class again, all green; commit `Configurations: model and effort pairs in presets.json`.

### Task 2: the first pair where nothing is picked

**Files:** `src/main/kotlin/dev/myair/prompt/PromptChoices.kt`, `src/main/kotlin/dev/myair/mcp/SessionStart.kt`, `src/main/kotlin/dev/myair/schedule/ScheduledTasks.kt`, their tests.

- [ ] **Step 1: Tests**: `choiceOf` of a preset with pairs takes the first pair's model and effort (a missing part: `DEFAULT_MODEL`/`DEFAULT_EFFORT`); `sessionStartPreset` with `preset` naming one and `effort = "max"` gives the first pair's model with `max` and no pairs; `ScheduledTask` with such a preset throws.
- [ ] **Step 2: Implement**: `choiceOf(preset)` reads `preset.defaultPair?.model ?: preset.model`; in `sessionStartPreset` resolve `from = from.defaultPair?.let(from::withVariant) ?: from` before applying the given values; `ScheduledTask.init` adds `preset.variants.isEmpty()` to its `require`.
- [ ] **Step 3: Run** `./gradlew test`; commit.

### Task 3: the popup and the submenu (`actions/PresetActions.kt`)

- Consumes: `withVariant`, `modelLabel`, `popupPoint`.

- [ ] **Step 1: Implement**:
  - `private fun Preset.start(project, where)` holding today's body of `actionPerformed` (`askMore` → `showPromptPopup`, else `startPreset(..., prompt)`).
  - `class VariantAction(preset, variant)`: text `variantLabel(variant)`, `actionPerformed` = `preset.withVariant(variant).start(...)`.
  - `PresetAction.actionPerformed`: without pairs `preset.start(...)`; with pairs `JBPopupFactory.getInstance().createActionGroupPopup(preset.name, DefaultActionGroup(variantActions(preset)), e.dataContext, SPEEDSEARCH, false).show(popupPoint(e))`.
  - `PresetsGroup.build`: in the dropdown a preset with pairs is `DefaultActionGroup(preset.name, true)` with its icon and `variantActions(preset)`; the pinned toolbar buttons stay `PresetAction`.
  - `variantLabel` pure next to `summary()`: `"${model?.let(::modelLabel) ?: OWN_SETTING} · ${effort?.replaceFirstChar(Char::uppercase) ?: OWN_SETTING}"`; unit test in `PresetActionsTest` (new, pure).
- [ ] **Step 2: Check in the sandbox** (`env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`): a pinned configuration with two pairs shows the popup under its button, a pick starts a tab whose `claude` command line has that `--model`/`--effort` (Session Context chips after the first reply); with `askMore` the composer opens with that pair's chips; the dropdown shows the submenu; a configuration without pairs starts at once. Record as `checked:` evidence.
- [ ] **Step 3:** commit.

### Task 4: editing the pairs (`actions/PresetSettings.kt`)

- [ ] **Step 1: Test** a pure `parseVariants(text: String): List<Variant>` (in `Presets.kt`): one pair per non-blank line, tokens split on whitespace, a token in `EFFORTS` is the effort, `-` an empty part, any other the model; more than one model or effort token on a line throws with the line; and `variantsText(list)` as its inverse (round trip).
- [ ] **Step 2: Implement** in `PresetDialog`: a row "Model and effort pairs:" `textArea().rows(3)` with comment `One pair per line, e.g. "opus high"; two or more show a list at the click.`, `validationOnApply` running `parseVariants` and the R1 checks (`Preset(...)` construction caught into `error(message)`); while the area holds pairs, the Model and Effort combo boxes are disabled (`enabledIf` on a predicate of the area's text) and `preset()` passes them as null. `summary()` adds `pairs ${variants.joinToString(", ") { variantLabel(it) }}` instead of model and effort.
- [ ] **Step 3: Run** `./gradlew test buildPlugin`; edit a configuration in the sandbox, save, check `~/.my-air/presets.json`; commit. Update `actions/README.md` and CLAUDE.md's configuration sentence (`variants`).
