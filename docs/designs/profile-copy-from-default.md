# Copy settings from the default profile when adding a Claude profile

*   **Author:** mkosmala
*   **Status:** Draft
*   **Date:** 2026-11-21

--------------------------------------------------------------------------------

## Overview

My Air is an IntelliJ plugin (Kotlin, Windows) that lists Claude Code sessions and runs them in editor tabs. It lets the user add separate Claude Code profiles. A profile is a Claude Code config directory (`CLAUDE_CONFIG_DIR`) listed in `~/.my-air/profiles.json`. The default profile is `~/.claude` (`ProfileStore.defaultDir`).

Today the "New Claude Profile" dialog (Settings > Tools > My Air) creates the new directory with two empty subdirectories, `projects/` and `sessions/`, and registers it. The new profile starts with no status line, no theme, no instructions and no skills.

This design adds an optional, one-time "copy from the default profile" section to that dialog. The section has one checkbox per item, shown under four group headings. When the user confirms, My Air copies the ticked items into the new profile directory before it registers the profile. The first version copies:

*   five `settings.json` keys: `statusLine`, `theme` (together with the `themes/` file a `custom:` theme needs), `model`, `effortLevel` and `language`;
*   `CLAUDE.md`;
*   the `skills/`, `agents/` and `commands/` directories.

My Air does not copy credentials. After creating a profile, My Air already opens a session tab: a terminal tab that runs `claude` with the new profile's `CLAUDE_CONFIG_DIR`. The user signs in there. The dialog advises signing in with the same Claude account as the default profile; nothing enforces it.

## The Problem

A user who adds a profile wants it to differ from the default in a few things, mainly plugins, skills and `CLAUDE.md`, and to share a few defaults such as the status line and the theme. Today the new profile is empty. To get the status line, the theme, the model setting or a set of skills, the user must find the files in `~/.claude` and copy them by hand. The user must also know which keys of `settings.json` to move and that a `custom:` theme needs its file in `themes/`. A mistake here shows up later as a blank status line or a theme that does not apply.

The proposed solution lets the user choose items from the default profile in the dialog. The items are grouped by how they change Claude Code's behaviour, and each item has a one-line effect, so the user can tell what each tick does. The copy has three limits:

*   It copies only items from a fixed list. It does not copy the whole directory and then delete exclusions.
*   It runs only into a target directory that does not exist or is empty. It never writes next to existing content.
*   It finishes before the profile is registered. If the copy or the registration fails, everything the copy wrote is removed and no profile is registered.

## Goals

*   The "New Claude Profile" dialog has a "copy from the default profile" section with one checkbox per item under four headings: look (`statusLine`, `theme` with `themes/`), behaviour (`model`, `effortLevel`, `language`), instructions (`CLAUDE.md`) and skills, agents and commands.
*   Default ticks: the look and behaviour items are ticked. `CLAUDE.md` and the three directories are not ticked.
*   The copy is one-time. Later changes in the default profile do not reach the new one.
*   When any item is ticked, the target directory must not exist or must be empty. Otherwise the profile is not created and the user sees an error that names the directory.
*   If any step fails (the copy, creating `projects/` and `sessions/`, or writing `profiles.json`), the target directory is returned to its earlier state (absent or empty) and the profile is not registered. The user sees which item or step failed and why.
*   An item whose source is missing or broken is shown as unavailable, with the reason, and cannot be ticked. Only the items that depend on the broken input are affected:
    *   no `settings.json`, or a `settings.json` that is not valid JSON or not a JSON object: the five settings items;
    *   a `custom:` theme whose `themes/<name>.json` is missing, or a theme name with a path separator or `..`: the theme item only.
*   Links (symbolic links, junctions) and other entries that are neither a plain file nor a directory, found while copying the three directories, fail the copy. They are not followed and not skipped without notice.
*   The dialog has one note: sign in through the tab that opens, with the same account, and `statusLine` commands are copied as they are, so the scripts they name stay in the default profile.

## Non-Goals

*   Copying credentials (`.credentials.json`). The user signs in again in the tab that opens.
*   Copying the `env` key of `settings.json`: it can hold secrets (see Alternative B).
*   Copying `hooks`, `permissions`, `modelSettings` or `attribution` in the first version. `hooks` run code from scripts in the default profile, and `permissions` carry trust into a profile meant to be separate. `modelSettings` and `attribution` are left for a later version, after their effect in a second profile has been looked at; no known risk keeps them out.
*   Copying plugins in the first version (see Alternative C).
*   Copying `.claude.json`, per-project memory (`projects/*/memory`) and all state data: `sessions/`, `projects/`, `history.jsonl`, `file-history/`, `backups/`, `daemon/`, `session-env/`, `shell-snapshots/`, `state/`, `plans/` and `jobs/`.
*   Syncing the new profile with the default one after creation.
*   Copying into a directory that already has content, which includes a profile that already exists, or merging keys into an existing `settings.json` (see Alternative D).
*   Copying the scripts that `statusLine` refers to, or rewriting paths inside commands.
*   Cancelling a copy that has started (see Risks and Limits).

## Open Questions

None that block the design. How the JDK reports a junction is checked by a unit test before the copy is written (see Risks and Limits).

## Technical Plan (Architecture)

### Flow

Today `ProfilesEditor.add()` in `actions/ProfileSettings.kt` does this on the EDT:

1.  opens the dialog;
2.  calls `createProfileDirs` (creates `projects/` and `sessions/`);
3.  calls `ProfileStore.write` (registers the profile in `profiles.json`);
4.  reloads the list and refreshes `ClaudeSessionsService` (the application service that holds the session list);
5.  opens the first-login session tab (`ClaudeSessionOpener.startNew`);
6.  shows the MCP command dialog, which gives the `claude mcp add` command that connects the new profile to the IDE's MCP server.

The new flow moves steps 2 and 3 into a background task and puts the copy in front of them:

```text
NewProfileDialog (EDT)
  item checkboxes; availability read once off the EDT when the dialog opens
        |  profile + ticked items
        v
ProfilesEditor.add() (EDT)
  starts a modal progress task (not cancellable)
        |
        v
background thread: copyFromDefault(defaultDir, profile.dir, selection, register)
  1. refuse unless the target is absent or empty
  2. check the source of every ticked item again
  3. copy the ticked items
  4. register(): createProfileDirs(profile), ProfileStore.write(...)
        |                         any failure in 3-4: delete what was written, rethrow
        v
EDT
  reload list, refresh service, open first-login tab, show MCP command dialog
  (on failure: error dialog, nothing registered, no tab)
```

If no item is ticked, the background task runs only `register()`, with no emptiness check and no rollback, and the result is the same as today.

### The copy logic: `store/ProfileCopy.kt`

This is plain Kotlin with no IDE-service types, in the `store` package next to `createProfileDirs`, so JUnit 5 can test it on temporary directories. Gson parses and writes JSON, as other code in the `store` package does.

**Item table.** One enum, `CopyItem`, lists every item. Each entry has its group label, its label, a one-line effect for the dialog, and what it copies. It is the only place that knows the key names.

| Group | Item | Effect shown in the dialog | What is copied |
|---|---|---|---|
| Look | status line | the same status line at the bottom of the session | `statusLine` key of `settings.json` |
| Look | theme | the same colours | `theme` key; for a `custom:<name>` theme also `themes/<name>.json` |
| Behaviour | model | the same default model | `model` key |
| Behaviour | effort level | the same default effort | `effortLevel` key |
| Behaviour | language | answers in the same language | `language` key |
| Instructions | CLAUDE.md | the same global instructions | the file |
| Skills, agents and commands | skills | the same skills | the `skills/` directory |
| Skills, agents and commands | agents | the same subagents | the `agents/` directory |
| Skills, agents and commands | commands | the same slash commands | the `commands/` directory |

**`copyAvailability(sourceDir)`** returns, for each item, `Available` or `Unavailable(reason, error)`. It reads only the default profile and writes nothing. `error` is true when the source is broken rather than absent, so the dialog can show the reason in red. Reasons:

*   the source file, key or directory is absent (a missing `settings.json` makes the five settings items unavailable);
*   the source `settings.json` is not valid JSON or not a JSON object: the five settings items, `error`, the message names the file;
*   a `custom:` theme has no `themes/<name>.json`, or the theme name holds a path separator or `..`: the theme item only, `error`, the message names the file or the name.

Marking only the dependent items, instead of throwing, keeps the error visible while `CLAUDE.md` and the directories stay usable. `copyFromDefault` still throws if a ticked item turns out to be unavailable.

**`copyFromDefault(sourceDir, targetDir, selection, register)`** does all the work, in this order:

1.  If `selection` is empty, it calls `register()` and returns.
2.  If `targetDir` exists and is not an empty directory, it throws with a message that names the directory. Nothing has been written.
3.  It runs `copyAvailability` again and throws if a selected item is unavailable, naming the item and the reason. Nothing has been written.
4.  It records whether `targetDir` existed, then copies:
    *   one new `settings.json` that holds only the selected keys, in their source order, written with `CREATE_NEW`. HTML escaping is off, so `&`, `<`, `>`, `=` and `'` in commands are written as they are, not as `&` and the like;
    *   single files (`CLAUDE.md`, a theme file) with `Files.copy` without `REPLACE_EXISTING`;
    *   each directory with Kotlin's `Path.copyToRecursively(target, followLinks = false, copyAction)`. The `copyAction` throws, naming the path, on a symbolic link or on an entry that is neither a plain file nor a directory.
5.  It calls `register()`.

If step 4 or step 5 throws, it deletes everything under `targetDir`, and `targetDir` too when it did not exist before. Then it rethrows, with the failing item or "registration" in the message. A deletion that fails is added to the message with the paths that remain. Everything under `targetDir` was written by this call, because step 2 found it absent or empty.

Nothing reads the new directory before it is registered, so `writeAtomically` is not used for `settings.json`.

**Default directory.** The source is `ProfileStore.defaultDir`, which is `~/.claude` (`Homes.claude`), the same directory the rest of My Air treats as `default`. The item table has no entry that could name `.claude.json`, `.credentials.json` or `plugins/`.

### The dialog: `NewProfileDialog` in `actions/ProfileSettings.kt`

The dialog gets a section "Copy once from the default profile (later changes are not synced)". It shows the nine items as checkboxes under the four group headings, each with its effect as a comment.

*   When the dialog opens, `copyAvailability(defaultDir)` runs once on a pooled thread. The checkboxes are disabled until the answer lands on the EDT. Then an unavailable item stays disabled and shows its reason, in red when `error` is true. The default profile does not change while the dialog is open, so the answer is not refreshed. Nothing reads files on the EDT.
*   The Directory field's existing `validationOnApply` gets one more rule, a path comparison with no file access: the target must not lie inside the default profile directory, so a walk of `skills/` can never reach its own target. Whether the target is empty is checked in the background task, not in the dialog.
*   The note from the Goals sits below the section.
*   The dialog returns the profile and the set of items that are ticked and available. It does no copying.

### `ProfilesEditor.add()`

After the dialog, it runs a modal `Task.Modal` that cannot be cancelled. Its background part is `copyFromDefault` with `register = { createProfileDirs(profile); store.write(...) }`. On success, `add()` continues on the EDT as it does today. On an exception, it shows an error dialog with the message, and it does not reload the list or open the tab. It writes one line to `idea.log` through the logger: the profile name and the copied item names, never file contents.

The existing `background` helper in `NewClaudeSessionInWorktreeAction.kt` is not used. It is not modal, and the Settings dialog must stay blocked until the profile is registered.

### Risks and Limits

*   **A crash during the copy** leaves a non-empty directory and no registered profile. The next attempt with that directory is refused because it is not empty, and the user removes it by hand. Accepted for the first version.
*   **No cancel.** A started copy runs to the end or to its first failure. The three directories hold small text files.
*   **The emptiness check and the writes are not atomic.** Another program could write into the target between step 2 and step 4. Then `CREATE_NEW` or `Files.copy` fails and the rollback deletes that program's file too. Accepted: this is a single-user tool and the directory is not yet registered.
*   **Entries that are not plain files or directories fail the copy.** This includes OneDrive and other cloud placeholders, which are reparse points. The error names the path. Junctions are expected to be reported as "other" when links are not followed, which this rule catches. A unit test with a real `mklink /J` junction confirms this before the copy is written. If the JDK reports a junction as a plain directory, the design must change, and the result is recorded in `docs/internals/`.
*   **`statusLine` scripts stay shared** with the default profile, because commands are copied as they are. A command that uses `$CLAUDE_CONFIG_DIR` or a relative path resolves against the new profile instead. The dialog note says the scripts stay in the default profile.
*   **Claude Code's first-run onboarding may ask for a theme** in the login tab and override the copied `theme` key. The manual check in the Verification Plan covers this. The key names are taken from the default profile's `settings.json` as Claude Code writes it.

## Alternatives Considered

### Alternative A: Copy `.credentials.json` so the new profile is signed in at once

*   **Pros**: no second sign-in; the profile works on first start.
*   **Cons**: both profiles would hold one OAuth refresh token. If Claude rotates refresh tokens on use, a refresh in one profile can invalidate the other and log the default profile out hours later, with no visible link to profile creation. A second copy of a secret also sits on disk. The rotation behaviour was not tested.
*   **Reason for Rejection**: the risk of a silent logout in the default profile outweighs one sign-in in the tab that already opens.

### Alternative B: Copy the `env` key, in full or per key

*   **Pros**: the new profile keeps settings such as the shell path or feature switches that live in `env`.
*   **Cons**: `env` can hold secrets. On the author's machine it holds an OTEL bearer token and an e-mail address. A copy puts such a token in a second plaintext file and attributes the new profile's telemetry to the same identity. A per-key checklist would need a rule for which names look secret, and such a rule can miss one.
*   **Reason for Rejection**: leaving `env` out costs the user a few keys added by hand; copying it can leak a secret that no rule reliably detects.

### Alternative C: Copy plugins as `settings.json` entries, or copy the `plugins/` directory

*   **Pros**: the new profile would start with the plugins the user already uses.
*   **Cons**:
    *   copying the `enabledPlugins`, `pluginConfigs` and `extraKnownMarketplaces` entries works only if `claude` installs missing plugins on first start, which has not been tested. If it does not, the new profile lists plugins as enabled that are not installed, with no error;
    *   copying `installed_plugins.json` and `known_marketplaces.json` unchanged is worse: they hold absolute paths into the default profile's cache, so the new profile would read and change that cache;
    *   copying the `plugins/` directory is not practical: on the author's machine its cache is 12 GB, and marketplaces added from a local directory are live folders that both profiles would share.
*   **Reason for Rejection**: deferred until the first-start install behaviour has been tested. Until then, the plugin entries could produce a profile that silently lacks its plugins. The group may return as a per-plugin picker or as install commands shown to the user. Copying `plugins/` itself is not planned.

### Alternative D: Copy into a directory that already has content, refusing or merging per item

*   **Pros**: the copy would also work for a folder the user prepared, or for an existing profile.
*   **Cons**: each item needs its own conflict check. Rollback must track every created path and never delete what was there before. A copy left half-done by a crash would be taken over on the next attempt, because only the conflicting items would be refused. Overwriting loses the user's keys, and a merge needs a rule for each conflicting key, including maps.
*   **Reason for Rejection**: the default target `~/.my-air/claude-profiles/<name>` does not exist yet, and existing profiles are a Non-Goal. The absent-or-empty rule gives a rollback that deletes everything under the target, and it refuses a leftover partial copy.

### Alternative E: Build in a temporary sibling directory and rename it into place

*   **Pros**: the target never holds a partial copy; a crash leaves only the temporary directory.
*   **Cons**: Windows can refuse to rename a directory that an indexer or antivirus holds, which needs bounded retries. The temporary directory needs a name rule and clean-up.
*   **Reason for Rejection**: with an absent or empty target, deleting everything under it gives the same rollback without the rename and its retries.

### Alternative F: Link `skills/`, `agents/` and `commands/` with junctions or symbolic links

*   **Pros**: no copy time and no extra disk use.
*   **Cons**: the profile would share live files with the default one, so an edit or a delete in either place changes both. Symbolic links can also need extra rights on Windows.
*   **Reason for Rejection**: shared live files contradict a one-time copy and the wish for profiles that differ in skills.

### Alternative G: Copy the scripts that `statusLine` and `hooks` name, and rewrite their paths

*   **Pros**: the new profile would not depend on scripts in the default profile.
*   **Cons**: it needs parsing of command lines, which are free text with quoting. Hook scripts may also read data under the default profile on purpose.
*   **Reason for Rejection**: parsing free-text commands is error-prone, and only one copied item (`statusLine`) holds a command. A fixed note covers it.

### Alternative H: Scan copied commands for the default-profile path and report a hit

*   **Pros**: the user sees exactly which copied item depends on the default profile.
*   **Cons**: a path in a command can be written with forward or back slashes, with `~`, or through an environment variable, so a plain search would miss some forms.
*   **Reason for Rejection**: only one copied item (`statusLine`) holds a command, so the fixed note says the same thing without a search that can miss forms.

### Alternative I: Write a marker file in the new profile that records what was copied

*   **Pros**: a later reader can tell a copied profile from a hand-made one and see what was copied.
*   **Cons**: one more file that `claude` does not know.
*   **Reason for Rejection**: nothing in My Air would read it. The `idea.log` line records what was copied.

### Alternative J: A first version without the three directories

*   **Pros**: no tree walk, no link handling and no junction test.
*   **Cons**: skills are one of the main ways the user wants profiles to differ (see The Problem), and copying them by hand is the error-prone part.
*   **Reason for Rejection**: with the absent-or-empty rule, the directories cost one `copyToRecursively` call and two link tests.

### Alternative K: Show a summary of the copy after creation

*   **Pros**: the user sees what was copied.
*   **Cons**: it is a second dialog in front of the MCP command dialog, or it needs a notification group in `plugin.xml`.
*   **Reason for Rejection**: the dialog already shows which items are unavailable. A success means every ticked item was copied, and a failure shows an error dialog.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

All paths are relative to the repository root. Packages are under `src/main/kotlin/dev/myair/`. No file is deleted. `plugin.xml` and `my-air-mcp.xml` are not changed: no new class is named in either file and no notification group is registered. `ProfileChecks.kt` is not changed: `createProfileDirs` keeps its meaning and is called from `register()`.

### Target Files to Create/Modify

#### 1. `src/main/kotlin/dev/myair/store/ProfileCopy.kt` (Create)

*   **Type / Scope**: Kotlin file in the `store` package. Plain JDK, `kotlin.io.path` and Gson types only, so tests need no platform services. `copyToRecursively` needs `@OptIn(ExperimentalPathApi::class)`.
*   **Proposed Changes**: The whole copy logic. Public surface:
    *   `enum CopyItem` with nine entries (status line, theme, model, effort level, language, CLAUDE.md, skills, agents, commands). Each has its group label, its label, its effect text, and what it copies: a `settings.json` key, a file, or a directory. This is the item table of the Technical Plan.
    *   `sealed interface Availability` with `Available` and `Unavailable(reason: String, error: Boolean)`.
    *   `copyAvailability(sourceDir): Map<CopyItem, Availability>`. Never writes. Applies the reasons of the Technical Plan. JSON is parsed with `JsonParser.parseString(...)`, as in `Profiles.kt`.
    *   `copyFromDefault(sourceDir, targetDir, selection: Set<CopyItem>, register: () -> Unit)`. Steps 1-5 and the rollback of the Technical Plan. `settings.json` is built as a Gson `JsonObject` from the selected keys in source order, printed with pretty printing and HTML escaping off, and written with `Files.writeString(..., CREATE_NEW)`.

#### 2. `src/test/kotlin/dev/myair/store/ProfileCopyTest.kt` (Create)

*   **Type / Scope**: JUnit 5 test with `@TempDir`, in the style of `ProfileChecksTest.kt`. Test names are backtick sentences.
*   **Proposed Changes**: Cases:
    *   a missing source `settings.json` makes the five settings items unavailable without `error`, and the file and directory items stay available;
    *   a source `settings.json` that is malformed, or is an array, makes the five settings items unavailable with `error` and a reason that names the file, and leaves the other items available;
    *   a `custom:` theme with a missing theme file, or a theme name with `..` or a separator, makes only the theme item unavailable with `error`;
    *   `copyAvailability` writes nothing (the source listing is the same before and after);
    *   selected keys are copied in source order and no other key appears;
    *   `&`, `<`, `>`, `=`, `'` and a Windows path in a `statusLine` command are written unchanged;
    *   a `custom:` theme copies `themes/<name>.json` too; a non-custom theme copies the key only;
    *   a nested tree in `skills/` is copied with its structure;
    *   an empty selection calls `register` and writes nothing else, even when the target is not empty;
    *   a non-empty target throws before anything is written, and `register` is not called;
    *   an existing empty target is filled, and an absent target is created;
    *   a selected item that is unavailable throws before anything is written;
    *   a symbolic link in `skills/` fails the copy, naming the path (skipped by `assumeTrue` when the machine cannot create symbolic links), and the same for a junction made with `mklink /J` (skipped when it cannot be made). In both cases the target is back to absent or empty;
    *   a `register` that throws leaves an absent target absent and an empty target empty, and the exception says registration failed;
    *   a deletion that fails during rollback (a file held open in the target) is named in the exception's message.

#### 3. `src/main/kotlin/dev/myair/actions/ProfileSettings.kt` (Modify)

*   **Type / Scope**: Swing and platform code: `ProfilesEditor` and the private `NewProfileDialog`. Holds no copy logic.
*   **Proposed Changes**:
    *   `NewProfileDialog` gets the section "Copy once from the default profile (later changes are not synced)": one checkbox per `CopyItem` under its group heading, with the effect as a comment. The look and behaviour items start ticked and the rest unticked. Below the section is the note about the account and the shared `statusLine` scripts.
    *   Availability: when the dialog opens, `copyAvailability(defaultDir)` runs once on a pooled thread. The checkboxes stay disabled until the answer is applied on the EDT. An unavailable item stays disabled and shows its reason as a comment, in red when `error` is true.
    *   The Directory field's `validationOnApply` also rejects a directory that is the default profile directory or lies inside it. This is a comparison of normalised paths, with no file access.
    *   `showAndGetProfile()` returns the profile and the selected items (a small data class) instead of only the profile. The selection holds only items that are ticked and available.
    *   `ProfilesEditor.add()` replaces the sequence `createProfileDirs`, `store.write` with a `Task.Modal` that cannot be cancelled. Its background part is `copyFromDefault(store.defaultDir, profile.dir, selection) { createProfileDirs(profile); store.write(store.all().drop(1) + profile) }`. On success the existing steps follow on the EDT (reload, `ClaudeSessionsService.refresh()`, `ClaudeSessionOpener.startNew`, MCP command dialog). On an exception, an error dialog shows the message, and there is no reload and no tab.
    *   Logging: one `thisLogger` line with the profile name and the copied item names. No file contents.

#### 4. `src/main/kotlin/dev/myair/store/README.md` (Modify)

*   **Type / Scope**: Package interface index.
*   **Proposed Changes**:
    *   Add `ProfileCopy.kt` to the interface list (line 31 area) with `CopyItem`, `Availability`, `copyAvailability` and `copyFromDefault`.
    *   Add to Invariants: the copy writes only into an absent or empty directory, never follows a link, and marks broken source items as unavailable instead of throwing.
    *   Add to Side Effects: `copyFromDefault` creates files in a profile directory and, on failure, deletes everything under it.

#### 5. `src/main/kotlin/dev/myair/actions/README.md` (Modify)

*   **Type / Scope**: Package interface index and behaviour notes.
*   **Proposed Changes**:
    *   Line 70: describe the copy section of `NewProfileDialog`.
    *   Line 120: add that Add with ticked items needs an absent or empty directory, and registers nothing when the copy or the registration fails.
    *   Line 132: Add now runs `copyFromDefault`, which copies, then creates the directories, then writes `profiles.json`.
    *   Line 134: add that the profile copy runs in a modal `Task.Modal` that cannot be cancelled, and that the availability check runs once on a pooled thread.

#### 6. `src/main/kotlin/dev/myair/README.md` (Modify)

*   **Type / Scope**: Root package README.
*   **Proposed Changes**: Line 51 (Side Effects of the Claude Profiles group): add the copy step before `createProfileDirs`.

#### 7. `docs/internals/new-sessions.md` (Modify)

*   **Type / Scope**: Feature internals, the paragraph that describes adding a profile.
*   **Proposed Changes**: Describe the copy:
    *   the groups and items;
    *   what is not copied and why (credentials, `env`, `hooks`, plugins);
    *   the absent-or-empty rule;
    *   the order: copy, then directories, then registration;
    *   the rollback and the manual clean-up after a crash;
    *   the account note and the shared `statusLine` scripts.

    Record the result of the junction test. State that plugins are not copied until the first-start install behaviour has been tested.

### Verification Plan

*   **Compilation/Build**: `./gradlew test buildPlugin`.
*   **Unit Tests**: first write the junction case of `ProfileCopyTest` and run it, to confirm how the JDK reports a junction. Then `./gradlew test --tests dev.myair.store.ProfileCopyTest`, then the full `./gradlew test`.
*   **Static Analysis & Linting**: the build configures no linter. Run the IDE inspections on `ProfileCopy.kt`, `ProfileCopyTest.kt` and `ProfileSettings.kt` and fix what they report.
*   **Test Coverage Requirement**: no percentage is configured. Each availability reason, each refusal before writing, each kind of copied item, the link failures, the rollback after a copy failure and after a registration failure, and a failed deletion during rollback each have a test in the list above.
*   **E2E/Integration**: manual, in the sandbox IDE (the IDE that `runIde` starts with the plugin built from this repository), started from Git Bash without the `CLAUDE*` variables: `env $(env | grep -o '^CLAUDE[A-Z_]*' | sed 's/^/-u /') ./gradlew --no-daemon runIde`.
    *   add a profile with the look and behaviour items and check that the new `settings.json` holds only those keys and that the default profile's files are unchanged;
    *   add a profile with all items and compare `skills/`, `agents/`, `commands/` and `CLAUDE.md` with the default profile;
    *   point Directory at a folder that holds a file, tick an item, and check that the error dialog names the folder, no profile is listed, and the folder is unchanged;
    *   create a temporary junction inside `~/.claude/skills/` (`mklink /J`), add a profile with the skills item, and check that the error dialog names the junction, no profile is listed, and the new directory is gone; then remove the junction;
    *   start `claude` in the new profile's tab, sign in with the same account, and check that the status line and theme apply (including after any onboarding question about the theme) and that the default profile stays signed in.

--------------------------------------------------------------------------------

*Drafted with EGM*
