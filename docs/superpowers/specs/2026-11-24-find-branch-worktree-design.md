# Find Branch / Worktree action: design

## TL;DR

- Goal: an action that takes pasted text, finds matching branches of the project's repository and the worktree each one is checked out in, and starts a session in that worktree, in a new one, or in a recreated one.
- Approach: a dialog (text field prefilled from the clipboard, list of matches, buttons per row). Matching is a pure function; the three actions reuse `createWorktree` / `startInWorktree` machinery.
- Decisions needing input: none open. Settled with the user: dedicated dialog (not the composer); `Recreate` refuses a worktree with uncommitted changes.
- Risks: `Recreate` deletes a directory; it only does so for a clean, unlocked, not-in-use linked worktree and otherwise throws, never falls back to another branch (unlike `Git.addWorktree`).
- Out of scope: other repositories than the IDE project's, PR URLs, sending the pasted text as the session's first message, resuming an old session of the branch.

**Spec only; not committed.**

## Intent

Work on a ticket usually lives in a branch that may or may not have a worktree. Pasting a Jira key, a branch name or a task line should land the user in the right directory with one click, or give them a fresh worktree of that branch.

## Goals / non-goals / anti-goals

- Goal: paste, see branches and worktrees, start a session where the work is.
- Goal: replace a stale worktree of a branch with a fresh one.
- Non-goal: searching other repositories, Bitbucket PRs, session history.
- Non-goal: the pasted text as first message (the composer does that).
- Anti-goal: losing uncommitted work, or silently starting in a different branch than the one shown.

## Approaches considered

| | Trade-off |
|---|---|
| **A. Dedicated dialog** (chosen) | one new class, search logic testable alone, no coupling to the composer |
| B. Detect in `PromptPanel` | little code, but the pasted text would be both message and query |
| C. Popup straight from the clipboard | fewest clicks, but a wrong match means copying again |

## Design

**Search** (`actions/BranchSearch.kt`, no IDE dependencies):
`findBranches(text, branches, worktrees): List<BranchMatch>`.
- Query terms: every Jira-style key in the text (`ticketKey`'s pattern, all occurrences); without a key, the words of the text (letters and digits, 3+ characters).
- A branch matches when, case-insensitively, it contains any key; without keys, when it contains every word. A pasted `origin/<name>` is compared as `<name>`.
- Local and remote branches of one name are one match (`BranchMatch(branch, remote: Boolean, worktree: Worktree?)`; `remote` true when no local branch exists). Matches are ordered: those with a worktree first, then by name.
- `worktree` is the entry of `Git.worktrees` whose `branch` equals the local name (main checkout included, flagged by being first in the list).

**Dialog** (`actions/FindBranchAction.kt`, in Tools and next to the new-worktree action): text field, list below, refreshed on each change; git is read off the EDT (`background`). A row shows branch, worktree path and state (clean/dirty, locked, running sessions), and offers:
- *Start here*: worktree exists (main checkout too): `ClaudeSessionOpener.startNew` in its directory.
- *New worktree*: no worktree: `createWorktree(repo, branch, null, id, running)` then start. A remote-only branch gets its tracking local branch, as `Git.addWorktree` already does.
- *Recreate*: linked worktree exists: `Git.removeWorktree` then `createWorktree`, then start.

**Removal** (`Git.removeWorktree(mainRoot, tree, inUse)`): throws `GitFailure`/`IllegalStateException` naming the reason when the tree is the main checkout, locked, has a running session in it, or is not clean (`isClean`, untracked files included); otherwise `git worktree remove`. No force, no fallback. A refusal shows the existing error dialog and leaves everything as it was. Recreate checks before removing and creates only after a successful removal.

## Requirements

- **R1** A query holding a Jira-style key matches every branch containing that key (case-insensitive) and no other.
- **R2** A query without a key matches only branches containing all of its words of 3+ characters.
- **R3** A local and a remote branch of the same name give one match; one with only a remote is marked remote.
- **R4** Each match carries the worktree that has its branch checked out, or none.
- **R5** *Start here* starts the session with that worktree as its directory, including for the main checkout.
- **R6** *New worktree* makes a worktree on the branch (a tracking local one for a remote-only branch) and starts the session there.
- **R7** `Git.removeWorktree` refuses the main checkout, a locked tree, a tree with a running session and a tree with modified, staged or untracked files, and removes nothing in those cases.
- **R8** *Recreate* of a clean tree ends with a new worktree of the same branch and a session in it; of a refused tree it changes nothing and shows the reason.
- **R9** Git is read off the EDT; the dialog lists results when they land.

## Tests

- `BranchSearchTest` (pure): R1 to R4.
- `GitTest` (real `git` in a temp dir): R7, and the remove-then-add result of R8.
- R5, R6 and R9 `checked:` in the sandbox IDE (`runIde`): no automated test can start a session tab.

## Docs to update with the code

`actions/README.md` (new classes), `docs/internals/new-sessions.md` (the action and the removal rule), `plugin.xml` (action registration).

## Left to the builder

Dialog layout details, ordering ties, wording of refusals, whether the list shows remote-only matches below local ones.
