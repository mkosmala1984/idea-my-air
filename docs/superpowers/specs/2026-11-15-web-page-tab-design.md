# Page tab: a web page beside its own session

## TL;DR

- **Goal:** a new editor tab type: any web page (Confluence first) in an embedded browser on the left, one Claude session of its own on the right, the way a plan tab pairs the plan with its manager.
- **Approach:** a copy of the plan tab's plumbing (`planview/`) with `JBCefBrowser` in place of `PlanPanel`; one session per tab, stored in `~/.my-air/page-tabs.json`; opened by Tools › Open Page....
- **Decided by the user:** any URL, not only Confluence; the Open Page action; one session per tab.
- **Out of scope:** the session controlling or reading the page (DevTools port, My Air MCP page tools, reload after edits). The session is not told the page's URL.
- **Risks:** Google SSO refuses embedded browsers (`disallowed_useragent`); JCEF may be off (`ide.browser.jcef.enabled`), in which case the action is disabled.
- **Needs your input:** nothing open.

## Requirements

- **R1** Tools › Open Page... asks for a URL; a URL without `http://` or `https://` is refused in the dialog, not opened.
- **R2** An accepted URL opens one new page tab in the current window, the page on the left and a new Claude session's terminal on the right, split by an `OnePixelSplitter` whose proportion is kept in `dev.myair.pageView.split` (default 0.55).
- **R3** Each Open Page makes a new tab with a new session, even for a URL another page tab already shows.
- **R4** The session starts with `--session-id <new uuid>` in the IDE project's directory, with the same environment and appended system prompt as any other My Air session, and is told nothing about the page.
- **R5** The page tab is titled with the page's `<title>` as the browser reports it, the URL's host until one arrives, and has the web icon.
- **R6** Above the page a bar has Back, Forward, Reload, the current URL in a field (Enter navigates to what is typed there) and Open in Browser.
- **R7** Navigating in the page (links, Back, the URL field) keeps the same session and stores the main frame's new URL as the tab's URL.
- **R8** After an IDE restart a page tab comes back with its last stored URL and resumes its session (`--resume`, or `--session-id` while the session has no transcript), in the directories stored with it.
- **R9** A page tab counts as its session's tab wherever My Air reads tabs: opening that session from the tree or the pinned list focuses the page tab; `/clear` or `/resume` in it moves the tab to the new session id; Session Context and Branch Diff follow it while it is the selected tab; cross-IDE sync lists it.
- **R10** Closing a page tab by hand treats its session as closing a session tab does (`SessionTerminals.release`, Keep sessions running after their tab closes). The tab's entry in `page-tabs.json` is dropped once its process is gone too: at once when none runs, else when the process ends or retires. While the detached process runs, opening its session reopens the page tab at its stored URL, binding that process.
- **R14** Closing a project or exiting the IDE with page tabs open keeps their entries, so R8 holds; only a close by hand (R10) drops one.
- **R15** Opening a page tab's session when no tab in this IDE shows it focuses the IDE whose tab does (`TabsSync.focusElsewhere`), else opens the page tab in the current window at its stored URL, its session resumed in the directories stored with it (as `PlanOpener` does for a plan).
- **R11** Logins in the page (cookies) survive closing the tab and restarting the IDE, as JCEF keeps them.
- **R12** With JCEF unsupported (`JBCefApp.isSupported()` false) Open Page is disabled, and a page tab restored then shows "The embedded browser is not available." in place of the page, its session still on the right.
- **R13** `page-tabs.json` with an unknown key or a field of the wrong type throws when read.

## Approaches

1. **JCEF tab modelled on the plan tab (chosen).** `JBCefBrowser` is the platform's Chromium; Confluence refuses `<iframe>` embedding (`X-Frame-Options`), but JCEF loads it as the top-level page. The session side reuses `SessionPane` and the plan tab's way of counting as a session tab.
2. **Open the page in the system browser beside a session tab.** No new tab type, but the pair is not one tab, and nothing restores it.
3. **Render page content fetched through Atlassian MCP as HTML.** Works without a login in the IDE, but only for Confluence, without its UI, and not live.

Control of the page by the session (Chrome DevTools through `ide.browser.jcef.debug.port`, or My Air MCP tools running JavaScript in the tab's browser) was discussed and left for later at the user's request.

## Design

Package `dev.myair.pageview`, mirroring `planview`:

- `PageVirtualFile(id)`: a `LightVirtualFile`, `OptionallyIncluded` (never in Recent Files), path `<id>` under `PageFileSystem` (`myair-page://<id>`, a `DeprecatedVirtualFileSystem` + `NonPhysicalFileSystem` as `PlanFileSystem`). `id` is a UUID made by Open Page. One instance per id per IDE (`PageFiles.file`).
- `PageTabStore` in `store/` (`~/.my-air/page-tabs.json`, shared by every IDE, written with `writeAtomically`): tab id → `PageTab(url, session, cwd, project)`, `moveSession(from, to)` as `PlanManagers` has, strict parsing (R13).
- `PageFiles` (application service): the tab file per id and the session's `ClaudeSessionVirtualFile` per id, never opened as a tab itself; `startSession(id)` keeps the file while `SessionTerminals.runs` it, else builds it from the store entry (`--resume`/`--session-id` as `managerStart`). `pageOf(sessionFile)` answers which page tab shows a session file.
- `PageEditor` (`PageEditorProvider`, `HIDE_DEFAULT_EDITOR`): the bar (R6) and `JBCefBrowser` on the left, `SessionPane` on the right. A `CefLoadHandler`/`CefDisplayHandler` stores the main frame's URL on load end (R7) and the title for the tab (R5, `updateFilePresentation`). The browser is disposed with the editor.
- `PageTabTitleProvider`, `PageFileIconProvider` (`AllIcons.General.Web`).
- `OpenPageAction` (Tools menu): an input dialog validating the scheme (R1), a new id, a store entry with a new session id and the IDE project's directory, then `openFile` (R2, R3).
- Counting as a session tab (R9): `sessionFileOf` and `tabFileOf` (`planview/ShownSessions.kt`) also answer for `PageVirtualFile`; `ClaudeSessionsService` moves `page-tabs.json` entries on `/clear` and `/resume` as it does `plan-managers.json`; `ClaudeSessionOpener.open` sends a session that a page tab entry names to that tab before its other steps, as it does for a plan manager. These helpers may move out of `planview` into a shared place, since they no longer concern plans alone.
- Closing (R10, R14): a close by hand (`FileEditorManagerListener.fileClosed` while the project stays open, not `CLOSING_TO_REOPEN`) marks the entry closed; the entry is removed when it is closed and `SessionTerminals` runs no process for it, checked at the close and on each service refresh. Entries of a project never reopened stay; they are a few lines each (call made in review, not by the user).
- Opening (R15): `PageOpener`, as `PlanOpener`.
- Two entries naming one session (a `/resume` in a page tab into a session another page tab shows): allowed; `ClaudeSessionOpener` takes the entry whose tab is open in this window, else the first (call made in review).
- `plugin.xml`: `virtualFileSystem` `myair-page`, `fileEditorProvider`, `fileIconProvider`, `editorTabTitleProvider`, the action.

## Left to the builder

- Whether the shared helpers move out of `planview` (and where), and how the plan and page variants share code without duplicating `PlanFiles`'s session-file logic.
- The exact JCEF handler classes and threading (handlers run off the EDT; store writes and presentation updates go to the EDT).
- Tests: `PageTabStore` parsing and `moveSession`, URL validation, the start args, as plain JUnit 5 beside `PlanManagersTest`-style tests; the browser itself checked in `runIde`.
