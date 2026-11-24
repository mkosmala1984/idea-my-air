# Canvas folders: an agent's rich answers rendered with OpenUI

*   **Author:** mkosmala
*   **Status:** M1 to M7 built and unit-tested (2026-11-23); the sandbox walk-through is pending (see State and next steps); open questions decided 2026-11-23
*   **Date:** 2026-11-23

--------------------------------------------------------------------------------

## Overview

My Air has two folder kinds that open as a tab of their own: a plan folder (`PlanEditor`) and a review folder (`ReviewEditor`). Both are a `ManagerTabEditor`: the folder's content on the left, the terminal of its manager session on the right (`SessionPane`).

This design adds a third kind, the **canvas folder**. Its tab has the manager's terminal on the right, as the other two do. On the left it has a JCEF webview that renders what the agent shows there. The agent writes **OpenUI Lang**, OpenUI's compact, line-based language for generated UI (<https://www.openui.com/docs>). The webview renders it with OpenUI's React renderer and a component library: cards, tables, charts, forms, buttons, and a few components of My Air's own. The terminal stays the place for the conversation. The canvas holds results the user wants to look at, compare or click: summaries, tables of findings, charts of numbers, choices to pick from.

What the user clicks goes back to the agent. A button's `@ToAssistant(...)` action becomes a message sent to the manager's terminal, with the form state attached. `@OpenUrl` opens the browser. My Air's own actions, such as opening a file at a line, run in the IDE.

## Facts this design relies on

Checked on 2026-11-23:

*   OpenUI packages on npm, all MIT-licensed:
    *   `@openuidev/react-lang` 1.1.1: `Renderer`, `defineComponent`, `createLibrary`, `defineAction`.
    *   `@openuidev/react-ui` 0.18.12: the prebuilt `openuiLibrary`, whose root is `Stack`, and `openuiPromptOptions`. It needs React 18.3 or 19, zod and zustand. It pulls in Radix, d3, react-markdown, KaTeX and a syntax highlighter.
    *   `@openuidev/lang-core` 1.1.1: `generateSystemPrompt`, with no React dependency.
    *   `@openuidev/cli`: `generate <library.ts> --out system-prompt.txt` writes the system prompt and its `.spec.json`.
*   `Renderer` props: `response` (the OpenUI Lang text), `library`, `isStreaming`, `onAction`, `onStateUpdate`, `initialState`, `onParseResult`, `onError`. Error codes include `unknown-component`, `missing-required`, `type-mismatch`, `parse-failed` and `render-error`.
*   Actions: `@ToAssistant(message, context?)` emits the event `continue_conversation` with `humanFriendlyMessage`, `params.context`, `formState` and `formName`. `@OpenUrl(url)` emits `open_url`. A custom action defined with `defineAction` emits `{ type: <name>, params }`. `@Set`, `@Reset` and `@Run` are handled inside the renderer.
*   The docs show `Renderer` only inside a React app. They do not say that it needs a server. Gateway and Autofix are hosted services, and this design does not use them.
*   In the plugin: `SessionPane.send(text)` already submits a message to `claude`'s prompt, and moves a long text into a file of `~/.my-air/messages`. `FolderTabs` and `ManagerTabEditor` already carry everything a manager tab needs except the left side.

## Goals

*   A canvas folder in the session tree, with its own icon, that opens a tab `myair-canvas://<folderId>`: the webview on the left and the manager's terminal on the right. The split is kept as `dev.myair.canvasView.split`.
*   The manager shows content through an MCP tool. Each call adds a **frame**, one OpenUI Lang document. The tab shows the newest frame and lets the user page back through earlier frames.
*   The tool's answer tells the agent how its frame rendered: the parse errors and unknown components the webview reported, or that no tab is open and nothing was rendered. The agent can fix its output in the same turn.
*   Clicks reach the agent: `@ToAssistant` goes through `SessionPane.send`, `@OpenUrl` opens the browser for http and https only, and My Air's custom actions run in the IDE.
*   The webview is self-contained. The bundle is a resource of the plugin, it makes no network request, and a link opens only through `@OpenUrl`.
*   It follows the IDE: light or dark theme, the editor font, and a re-render on a look-and-feel change, as `CardPage` does.
*   Canvas folders follow the existing rules: one writer, files written with `writeAtomically`, nothing read on the EDT, unknown fields and versions throw, and the internals and package READMEs are updated in the same commit.

## Non-goals (v1)

*   Token-by-token streaming. An MCP call is atomic, so a frame shows whole, with `isStreaming = false`. See "Later" for the draft-file variant.
*   Rendering the rich answers of ordinary sessions, by parsing OpenUI out of transcripts. Sessions write to a canvas only through its MCP tools.
*   OpenUI Gateway or Autofix, or any API key.
*   `Query()` and `Mutation()` tool calls from the canvas, `toolProvider`. They could come later as a bridge to the plugin's MCP tools.

## Design

### 1. Model and store (`store/Canvases.kt`)

The store is modelled on `review.json`: one file per folder, and the folder id is the only link. That leaves `structure.json` alone, so no version bump is needed.

`~/.my-air/canvases/<folderId>/canvas.json`, format 1:

```json
{
  "format": 1,
  "manager": { "session": "…", "cwd": "…", "project": "…", "profile": "default" },
  "next": 4,
  "frames": [
    { "id": "C3", "title": "Flaky tests by module", "openui": "root = Stack([…])\n…", "created": "2026-11-23T10:15:00Z",
      "state": { "…form state from onStateUpdate…": "" } }
  ]
}
```

*   `CanvasFiles` reads and writes the file, atomically. A missing field or a newer `format` throws.
*   It keeps at most N frames (default 50). The oldest go first.
*   `state` is the latest `onStateUpdate` of a frame, written with a debounce. A restored tab gets it back as `initialState`, so a half-filled form survives a restart.

### 2. Service (`canvas/CanvasService.kt`)

An application service with the same shape as `ReviewService`:

*   `canvases: Map<folderId, Result<Canvas>>`, kept on the EDT.
*   Reads on one background thread, where only the latest read lands, on every `ClaudeSessionsService` change and on file events under `canvases/`.
*   It is the only writer: `create`, `update(folderId, op)` and `moveManagers` after a `/clear`.
*   `subscribe(parent, listener)`.
*   Pure helpers for tests: `managedCanvasFolder(canvases, sessionId)`, `addFrame(canvas, title, openui, now)`, `prune`.

Placement: generalize `refuseReviewPlacement` into `refuseKindPlacement`. A canvas folder holds no plan, review or canvas folder, and it does not lie inside a review folder. It may lie inside a plan folder (decided), so a plan task can have a canvas. `ClaudeSessionsService.updateFolders` passes the ids of both kinds read from disk.

### 3. MCP tools (`mcp/CanvasMcpToolset.kt`, registered in `my-air-mcp.xml`)

Each tool names the canvas folder by its id or its path, as `withReview` does.

*   `my_air_canvas_show(folder, openui, title?)` adds a frame and selects it in every open tab of that canvas, across IDEs through the store's file event. It then has a canvas page check the document (`window.myAir.validate`), waiting up to 5 s, and returns `{folder, path, frame, stored: true, validated, ok, errors:[{code, message, statementId, component, path, hint, severity}], unresolved, orphaned, note}`. When no page is open, it returns `validated: false` with the reason in `note`, and the frame stays stored (see M4 results).
*   `my_air_canvas_get(folder)` returns the frames (id, title, created; the OpenUI text of the newest frame only) and the state of the newest frame.
*   `my_air_canvas_apply(folder, changes)` applies a JSON list of `remove_frame`, `set_title` and `clear`, all or none. An unknown op fails and names it.

*   `my_air_canvas_language()` returns the OpenUI system prompt of the library (`system-prompt.txt`) plus My Air's canvas rules. The description of `my_air_canvas_show` tells the agent to read it first when the agent is not the canvas's manager.

**Any session may write to any canvas (decided).** The MCP server does not know which session calls it, so the tools do not check the caller, and a session finds a canvas through `my_air_folders_get`. Ordinary sessions get no OpenUI prompt: it is long, and `my_air_canvas_language` gives it on demand. Each frame records `author`: the session id once it is learned, else null. My Air learns the author by finding the `my_air_canvas_show` call in the transcripts (the scanner already reads tool calls for briefs), matched by frame id in the tool's result. In v1, a click on a frame goes to the canvas's manager, and the message names the frame and its author, so the manager can read the frame (`my_air_canvas_get`) or pass the click on. Routing a click to the author's own tab when it runs is a later step.

These tools are not in `FoldersMcpToolset`, so `SessionSystemPromptTest` does not need to change. The canvas manager's prompt names them; other sessions see them in the tool list.

### 4. The manager's prompt (`canvas/CanvasPrompts.kt`)

*   `canvasManagerPrompt(folderId, canvas)` is My Air's rules followed by the **OpenUI system prompt** of the library.
*   My Air's rules: the terminal is for conversation. Show results meant to be looked at with `my_air_canvas_show`. Read the errors the tool returns and fix the frame. A message that starts with `[canvas …]` is a click from the canvas, and its form state comes as JSON.
*   The OpenUI prompt is generated at build time by `@openuidev/cli generate` from the same `library.ts` the bundle uses, and is shipped as `resources/canvas/system-prompt.txt`. The prompt and the components therefore cannot drift apart: the web build's test regenerates the prompt and fails on a diff.
*   It is wired in like the review manager's prompt. `SessionTerminals` asks `ReviewTabs.managerPrompt(file) ?: CanvasTabs.managerPrompt(file) ?: …`. A new manager gets `CANVAS_MANAGER_FIRST_MESSAGE` after `--`, as `reviewManagerStart` does.

### 5. Tab (`canvasview/`)

The tab follows `reviewview/` file for file:

*   `CanvasFiles.kt`:
    *   `CanvasVirtualFile(folderId) : FolderTabFile`.
    *   `CanvasFileSystem : FolderTabFileSystem("myair-canvas", …)`.
    *   `@Service CanvasTabs`, with `tabs = FolderTabs(::CanvasVirtualFile)`, `startManager`, `profileToCheck`, `canvasOf(file)` and `managerPrompt(file)`.
*   `CanvasEditor : ManagerTabEditor(project, file, "Canvas", "dev.myair.canvasView.split")`:
    *   `nameNow` returns the folder's name while `canvas.json` exists.
    *   `content()` returns a `CanvasPanel`.
    *   `render()` pushes the current frame.
*   `CanvasPanel` has a thin Swing toolbar (frame title, ◀ ▶ through the frames, Copy OpenUI source) above a `JBCefBrowser`.
    *   When `JBCefApp.isSupported()` is false, it shows a notice and the OpenUI source as text. The terminal still works.
    *   It loads the bundle once: `loadHTML(resource("canvas/index.html"), "https://myair.canvas/")`, a single HTML file built with `vite-plugin-singlefile`.
    *   Kotlin → JS: after `onLoadEnd`, `executeJavaScript("window.myAir.show(<json>)")`, where the JSON carries `{frame, openui, state, dark}`; `window.myAir.validate` parses a document without rendering it. Calls made before the load ends are queued. The full protocol is `canvas-web/src/protocol.ts`.
    *   JS → Kotlin: one `JBCefJSQuery`, injected as `window.myAirPost(json)`. The messages are `parsed` (errors, for the waiting `my_air_canvas_show`), `state` (debounced, stored in the frame) and `action`.
    *   A request handler (`CefRequestHandler.onBeforeBrowse` / `onBeforeResourceLoad`) blocks every navigation and every request that is not the bundle's. The bundle sets a CSP of `default-src 'none'`, with inline scripts and styles allowed.
    *   The context menu offers DevTools in internal mode only.
*   `CanvasActions.kt` (pure and tested) maps a renderer event to a `CanvasCommand`:
    *   `continue_conversation` → `Send("[canvas <folder> · <frame>] <humanFriendlyMessage>\n```json\n{context, form}\n```")`, sent through `pane.send`. It is refused, with a balloon, while no process runs.
    *   `open_url` → `Browse(url)`, for http and https only.
    *   `OpenFile(path, line)` → open the file in the editor, but only under the folder's or the manager's cwd.
    *   `CopyToClipboard(text)`.
    *   Any other event → a notice naming it.
*   `CanvasTabsProviders.kt`: the tab title and `CANVAS_ICON`.
*   `plugin.xml`: `virtualFileSystem key="myair-canvas"`, `CanvasEditorProvider`, `CanvasFileIconProvider` (`order="first"`) and `CanvasTabTitleProvider`.

Shared code that must learn about canvases:

*   `ClaudeSessionOpener.open`: a canvas manager opens its canvas tab, as plan and review managers do.
*   `ShownSessions.tabFileOf` / `sessionFileOf`.
*   `managerRefusal`: a canvas manager cannot become a plan manager.
*   `SessionsPanel`: Open Canvas on a canvas folder, and the icon of its row.

### 6. Starting a canvas

*   **New Canvas...** in the tree's menu (`NewCanvasAction`). It asks for a name, the directory (the folder's `startDir`, else the project's) and the profile (`chooseProfile`, only when other profiles are listed). It writes `canvas.json` first, then the folder `Canvas: <name>` (the same order as `startReview`), and opens the tab.
*   Later: "Turn into Canvas" for a running session (an `adopt`, as for plans), and a "Canvas" choice in the composer (`PromptPanel`).

### 7. Web bundle (`canvas-web/`, a sibling of `taskbar/`)

*   Built with Vite, React 19 and TypeScript, using `@openuidev/react-lang`, `@openuidev/react-ui`, `zod` and `zustand`, all pinned in `package-lock.json`.
*   `src/library.ts`: `openuiLibrary.extend` with My Air's components and actions:
    *   `FileRef(path, line?, label?)`, which uses the `OpenFile` action;
    *   `Diff(path, before, after)`, perhaps in v2;
    *   `SessionLink(id)`, perhaps in v2;
    *   the actions `OpenFile` and `CopyToClipboard`.
    *   The library stays small, as the OpenUI docs advise.
*   `src/main.tsx`: `window.myAir.show` keeps `{response, state}` in React state and renders `<Renderer library response isStreaming={false} initialState onAction onStateUpdate onParseResult onError/>`. It forwards everything through `window.myAirPost` (`src/bridge.ts`).
*   Theme: the IDE's colours arrive in `show` (`MarkdownColors.current()` plus a few `JBColor`s) and are set as CSS variables that the react-ui theme reads.
*   `npm run build` writes `src/main/resources/canvas/index.html` and `system-prompt.txt`, and both are committed. `./gradlew buildPlugin` therefore does not need Node. Node 20 or newer is needed only to change the canvas, as .NET is only for `taskbar/`.
*   `npm test` (vitest):
    *   renders fixture documents with `renderToString` and checks that they produce no errors;
    *   checks that every custom action maps to a `CanvasCommand` name the Kotlin side knows, from a shared JSON literal (as `TaskbarBoardTest` and `BoardTest.cs` share one);
    *   checks that the committed prompt equals a freshly generated one.

### 8. Tests (Kotlin, plain JUnit)

*   `CanvasFilesTest`: a round trip, a missing field throws, a newer format throws, pruning.
*   `CanvasChangesTest`: `addFrame`, ids, the `apply` ops, an unknown op.
*   `CanvasActionsTest`: event JSON → command, URL schemes, `OpenFile` outside the root refused, message format.
*   `PlacementTest`: canvas placement under the generalized refusal.
*   `CanvasPromptsTest`: the prompt names every `CanvasMcpToolset` tool and contains the OpenUI prompt.

## Plan (milestones)

| # | Milestone | Done when |
|---|-----------|-----------|
| M1 | **Spike, a gate:** a JCEF panel in a scratch tab renders a hard-coded OpenUI Lang document with the prebuilt `openuiLibrary` (decided) from the single-file bundle, offline, in both themes; `onAction` reaches Kotlin | Bundle size, start time and the prompt's length are measured, and it is confirmed that react-ui works under CSP with no network (KaTeX fonts and the like inlined) |
| M2 | `canvas-web/`: library, bridge, build to resources, generated system prompt, vitest | `npm test` is green, and `index.html` plus `system-prompt.txt` are committed |
| M3 | Store, `CanvasService`, placement rules, Kotlin tests | `./gradlew test` is green (done) |
| M4 | `CanvasMcpToolset` (show/get/apply/language), with the render-feedback wait; frame authors learned from transcripts | A manager and an ordinary session in the sandbox each show a frame and get its errors back (built and unit-tested; the sandbox check waits for M5's tab, or the spike tab, to be open) |
| M5 | Tab: file system, `CanvasEditor`, `CanvasPanel`, manager start and prompt, `plugin.xml` | A canvas opens, restores after a restart, and the manager resumes (built and unit-tested; the sandbox check waits for M7's Open Canvas) |
| M6 | Actions back: `@ToAssistant` → terminal, `@OpenUrl`, `OpenFile`, form state kept | A form submitted in the canvas arrives as a message, with its state (built and unit-tested; sandbox check with M7) |
| M7 | Tree: New Canvas..., Open Canvas, icon, opener and `ShownSessions`; `docs/internals/canvases.md` and the package READMEs; the CLAUDE.md table row | Sandbox walk-through, with docs in the same commit (built, unit-tested and documented; the walk-through waits for the user) |

Later: authors of frames shown by subagents (the scanner reads only the main conversation); moving `CanvasService.update`'s file read off the EDT; draft-file streaming, in which the agent writes `canvases/<id>/draft.oui` with its Write tool and the watcher pushes chunks with `isStreaming = true`; Turn into Canvas; a composer choice; `Query()` / `Mutation()` through the plugin's MCP tools; routing a click to the frame's author when its tab runs; rendering OpenUI fences from ordinary sessions' transcripts; a frame count on a canvas folder's row; asking for the manager's directory in New Canvas....

## Spike M1 results (2026-11-23)

What exists: `canvas-web/` (Vite 7, React 19.2, `@openuidev/react-lang` 1.1.1, `@openuidev/react-ui` 0.18.12, zod 4, `vite-plugin-singlefile`), `dev.myair.canvasview` (`CanvasBrowser`, `CanvasBridge`, the spike tab under Tools › Open Canvas Spike) and `CanvasBridgeTest`.

*   **Bundle:** one self-contained `index.html` of 4.0 MB (975 kB gzipped). It adds about 1 MB to the plugin's jar.
*   **System prompt:** `openuiLibrary.prompt(openuiPromptOptions)` is 36,113 characters, about 9k tokens. That is acceptable for a canvas manager, and it is why other sessions get it only on demand (`my_air_canvas_language`). It is generated in Node from the library itself (`scripts/prompt.mjs`). The CLI is not needed.
*   **Offline:** the sample renders in headless Chrome from `file://` under the page's CSP `default-src 'none'`, in both themes, with no request. The library's only URLs are Google favicons (citation sources) and picsum images in prompt examples, and the CSP blocks both. KaTeX fonts are not loaded: math in `MarkDownRenderer` is untested.
*   **Theme:** `ThemeProvider mode` gives dark and light. The page needs its own `background: var(--openui-background)`. The KPI `Card`s render flat, without the elevated look.
*   **Validation without a browser:** `createParser(openuiLibrary.toJSONSchema(), "Stack").parse(text)` gives `meta.errors`, `unresolved` and `orphaned` in Node, and `renderToString` works under vitest. `my_air_canvas_show` could therefore validate a frame before any tab shows it, by running the parser in the tab's browser or in a Node helper.
*   **JCEF:** in IU 2026.3, JCEF is the bundled plugin "Web Browser (JCEF)" (`com.intellij.modules.jcef`, built for Windows x86-64). The plugin depends on its module `intellij.platform.ui.jcef` (Gradle `bundledModule("intellij.platform.ui.jcef")` and `bundledModule("intellij.libraries.jcef")`). The build compiles and the tests pass.
*   **In the sandbox (JCEF 150):**
    *   `loadHTML` of the 4 MB page works.
    *   `ready` arrives 847 ms after the browser is created. That includes JCEF's first start in the IDE; the page script itself takes 47 ms. `parsed ok=true` arrives with no errors.
    *   `JBCefJSQuery` carries clicks to Kotlin. `@ToAssistant` comes with `humanFriendlyMessage`, `params.context`, `formName` and `formState`. `@OpenUrl` comes with `params.url`, and `BrowserUtil` opens it.
*   **Findings for the design:**
    *   `formState` is keyed by form name (`{"fix": {"test": {"value": "watcher"}, ...}}`).
    *   `onStateUpdate` fires on every keystroke, twice each time, so the stored state needs a debounce and a check that it changed.
    *   `@OpenUrl` comes with an empty `humanFriendlyMessage` and the page's whole `formState`, so the message sent to the agent must not include the form unless the click came from that form.

## M2 results (2026-11-23)

*   **Library** (`canvas-web/src/library.ts`): `canvasLibrary = openuiLibrary.extend(...)` adds `FileRef(path, line?, label?)` (also in `Card.children`) and the actions `@OpenFile(path, line?)` and `@CopyToClipboard(text)`; `extend` takes actions through `actions.add`. A component fires a library action only as an ActionPlan step (`{steps: [{type: "custom_action", name, params}]}`); `triggerAction` ignores a bare `{type, params}` of a library action.
*   **Protocol** (`protocol.ts`, logic in `bridge.ts`): plugin → page `show`, `validate`, `connect`; page → plugin `ready`, `parsed`, `validated`, `errors`, `state`, `action`, `js-error`. `validate` parses with `createParser` and renders nothing, so M4 can check a frame in any loaded page. `protocol.json` holds the names for both test suites; the Kotlin test reads it from the project directory.
*   **Prompt:** `scripts/prompt.mjs` loads `library.ts` through Vite's SSR loader and writes `system-prompt.txt` (36,773 characters); `npm test` fails when the committed prompt differs.
*   **Kotlin:** `CanvasBridge.kt` speaks the whole protocol, and `commandOf` (`CanvasActions.kt`) maps a click to a `CanvasCommand`.

## M3 and M4 results (2026-11-23)

What exists: `store/Canvases.kt` (`Canvas`, `Frame`, `CanvasFiles`), `canvas/` (`CanvasService`, `CanvasChanges.kt`, `CanvasPrompts.kt`), `folders/KindPlacement.kt`, `mcp/CanvasMcpToolset.kt` and `mcp/CanvasJson.kt`, `canvasview/CanvasRenderers.kt` with `CanvasBrowser.validate`, and `ClaudeSession.shownFrames` from the scanner. Behaviour: `docs/internals/canvases.md`.

*   **Placement** moved out of `review/` into `folders/KindPlacement.kt`: `refuseReviewPlacement` is now `refuseKindPlacement(before, after, reviews, canvases)`, `placementProblem` takes the canvas ids too, and `dropChange` takes them as its last argument. Review messages are unchanged. A review folder may not lie in a canvas folder either (a canvas holds no review).
*   **Frame model:** `title` is nullable (the tool's `title` is optional); `Canvas` holds no `format` field, the file's `format` is the constant `CANVAS_FORMAT`. A newer format is reported before unknown keys.
*   **Show order:** the frame is stored first, then checked. An invalid document (`ok: false`: no root, or an error that is not a warning) **is stored anyway**, and the `note` says so and asks for a fixed frame. The user sees what came, and the check is not always available.
*   **The check needs any page, not the canvas's own:** `CanvasRenderers.validator` prefers a loaded page of the same canvas, then any loaded page, then any page. The spike tab registers too (folder null), so the check works before M5. Without a page, after 5 s, or when the page's tab closes first, `validated` is false with a note (`no canvas tab is open in this IDE, the frame was stored unchecked`).
*   **Authors are learned from transcripts:** the scanner keeps `my_air_canvas_show` calls as it keeps brief calls and reads `folder` (the id) and `frame` from the result's JSON into `ClaudeSession.shownFrames`; `CanvasService` sets `author` on frames without one after each sessions change. This made the result's `folder` the folder id, with the path in `path`. Subagent calls are not read, so their frames keep a null author.
*   **`update` reads `canvas.json` on the EDT**, as `ReviewService.update` reads `review.json`, so a frame another IDE added is not lost. A canvas file is at most 50 frames; if that proves slow, the next step is a write queue off the EDT.
*   **Manager prompt:** `canvasManagerPrompt` spans lines and holds the 36,773-character OpenUI prompt, so M5 must pass it with `--append-system-prompt-file` (a command line is limited to 32,767 characters on Windows).

What later milestones use:

*   M5 tab: `CanvasService.getInstance()` (`canvases`, `canvas(id)`, `subscribe`, `update(id) { setState(it, frame, state) }` for the debounced `State` message, `create`), `CanvasFiles.file` via `CanvasService.file(id)`; `CanvasBrowser(parent, onMessage)` plus `CanvasRenderers.getInstance().register(browser, folderId, editor)`; `CanvasFrame(frame.id, frame.openui, CanvasBrowser.dark(), frame.state)` for `show`.
*   M5 manager: `managedCanvasFolder(canvases, sessionId)`, `canvasManagerPrompt(folderId, canvas)` (to be returned by `CanvasTabs.managerPrompt(file)` and written to a prompt file), `CANVAS_MANAGER_FIRST_MESSAGE` after `--` while the session has no transcript; `Canvas.manager` is a `PlanManager`.
*   M6 actions: `commandOf(action, folderId, frame.author)`.
*   M7 tree: `CanvasService.onDiskIds` for `dropChange(..., canvases = ...)` (already wired in `FolderDrop`), `CanvasService.create` before the folder write (as `startReview`), `placementProblem` for disabling menu items.

## M5 and M6 results (2026-11-23)

What exists: `canvasview/` `CanvasFiles.kt` (`CanvasVirtualFile`, `CanvasFileSystem`, `CanvasTabs`, `canvasStartDir`, `canvasManagerStart`), `CanvasEditor.kt`, `CanvasPanel.kt`, `CanvasView.kt` (pure: `selectedFrame`, `canvasFileTarget`, `canvasProblems`), `CanvasOpener.kt`, `CanvasTabsProviders.kt`, `CANVAS_ICON` in `editor/SessionIcons.kt`, and the `plugin.xml` registrations. The spike (`CanvasSpike.kt`, Tools › Open Canvas Spike) is gone, and `CanvasRenderers` takes a folder id always; `spike-sample.oui` stays, as the page's default document and `canvas-web`'s sample test use it. Behaviour: `docs/internals/canvases.md`.

*   **Where a new manager runs:** in the folder's start directory (`canvasStartDir`: the nearest working or bound directory of the folder and its ancestors, as `PlanFiles.startManager` picks it), else in the IDE project's directory; that directory is its project too, its profile `default`. M7's New Canvas... stores the manager at creation, with the directory and profile it asks for, so this rule covers a canvas made without one (an MCP-made or old `canvas.json`).
*   **Manager wiring:** `SessionTerminals` asks `ReviewTabs.managerPrompt(file) ?: CanvasTabs.managerPrompt(file)` before the plan prompt; `ClaudeSessionOpener.open` opens the canvas tab of a canvas manager; `sessionFileOf`/`tabFileOf` know canvas tabs; `managerRefusal` takes the canvas path as a new argument after the review's (`my_air_plan_set_manager` passes it); Turn into Plan is disabled for a canvas manager; Delete Folder asks before ending a working canvas manager.
*   **Selection:** a frame counts as new when its number is above the newest one known (`C10` after `C9`), so removing the newest frame does not move the user, and a frame after `clear` is selected.
*   **Push rule:** a frame is pushed only when its id, text or theme changes, never for a state change, which the page made itself. `CanvasBrowser` re-shows on a theme change with the state it was last given, so a form's unsaved edits since that push may reset on a theme change (small; left).
*   **Empty canvas:** the page's own default document is the spike's sample, so the tab pushes a one-line placeholder (`TextContent`) under the id `none` while a canvas has no frame. The bundle was not rebuilt.
*   **Errors banner:** `canvasProblems` counts parse and render errors (deduplicated), a missing root and script errors (those since the last push) as errors; warnings, unresolved references and orphaned statements as warnings. The toolbar shows `2 errors, 1 warning` with the list as its tooltip.
*   **Send refused:** `@ToAssistant` is sent only while the manager's process runs here and is not starting (`SessionTerminals.runs` and `starting`); otherwise a balloon says so and nothing is queued. A process that ended inside its tab still counts as running there (as for the review tab's buttons).
*   **OpenFile:** `canvasFileTarget` is lexical (`normalize`, no link resolution); a relative path resolves against the folder's start directory when it has one, else the manager's cwd; an absolute path must lie under one of them. A drive-less `/x` on Windows lands on the root's drive and is refused unless it lies under a root.
*   **Icon:** `CANVAS_ICON` is `AllIcons.Toolwindows.WebToolWindow` (a plan is `AllIcons.Nodes.Target`, a review `review.svg`, and `AllIcons.Actions.Preview` is Open Review's and Session Context's).

What M7 uses:

*   `CanvasOpener.open(project, folderId)` for Open Canvas (on a folder `in CanvasService.getInstance().canvases`) and after New Canvas...; `CANVAS_ICON` for the folder's row (`SessionRenderers`, as `REVIEW_ICON`).
*   New Canvas...: `CanvasService.create(folderId, Canvas(PlanManager(newId, dir, dir, profile), 1, emptyList()))` before the folder write (as `startReview`), `dir` from `canvasStartDir(structure, parent)` or the project; a manager stored there starts with `--session-id` and `CANVAS_MANAGER_FIRST_MESSAGE` (`canvasManagerStart` sees no transcript), so the session id need not be filed first (`FolderTabs.managerFile` files only a new id; file it in the folder with the folder write).
*   Menus disabled where placement refuses: `placementProblem(...)` with `CanvasService.onDiskIds` (as `FolderDrop` does); Turn into Plan and Add Plan inside a canvas folder are still refused only by the write (`inReview(structure, canvasIds, id)` works for canvas ids too).

## M7 results (2026-11-23)

What exists: `canvasview/NewCanvas.kt` (`canvasParent`, `canvasFolderName`, `startCanvas`, `NewCanvasAction`) and `NewCanvasTest`; Open Canvas, the row's icon and the menu checks in `toolwindow/`; `inReviewOrCanvas` in `folders/KindPlacement.kt`; `kind` in the folder tree JSON. Behaviour: `docs/internals/canvases.md` ("In the session tree").

*   **New Canvas... places, it does not refuse.** Started in a review or canvas folder (or below one), the canvas goes beside the outermost of them (`canvasParent`), as New Review... goes beside a review. So New Canvas... is never disabled for its place; it is shown in Folders mode on every row, as New Folder is, and takes the folder of the selected row (`targetFolder`), else the top level.
*   **No directory dialog.** The manager runs in the parent's start directory (`canvasStartDir`), else the IDE project's directory, as the design's §6 names it; the user changes it by giving the parent a working directory first. The dialogs are the name (non-blank; a `/` becomes `-`) and, only with other profiles listed, the profile (`chooseProfile`, now public in `reviewview` with a title and message).
*   **Order and filing:** `canvas.json` with the manager first, then one folder write that creates `Canvas: <name>`, files the manager there (`FolderTabs.managerFile` files only a new id) and checks the place did not change. The manager starts when the tab opens, with `--session-id` and `CANVAS_MANAGER_FIRST_MESSAGE`.
*   **New Review... knows canvases:** `reviewParent` takes the canvas ids as its last argument, so a review started in a canvas folder goes beside it (linked to the plan holding the canvas, if one does) instead of being refused by the write.
*   **Menus:** `inReviewOrCanvas` (pure, replaces `review.inReview`) disables New Plan..., Make Folder a Plan and Turn into Plan... in and on review and canvas folders. Drops already used `placementProblem` with the canvas ids (M3).
*   **Tree:** Open Canvas on a canvas folder (readable or not; an unreadable one opens a tab that says why); `CANVAS_ICON` on its row, repainted on every `CanvasService` change. A double-click or Enter toggles the row, as on a review folder (neither opens its tab). No frame count on the row.
*   **Agents find canvases:** `my_air_folders_get` (and the tree `my_air_folders_apply` and `my_air_history_restore` return) puts `kind` (`review` or `canvas`) right after the path of those folders, and leaves it out elsewhere. Every session's system prompt gains one sentence: canvas folders exist (kind canvas), any session may show a frame with `my_air_canvas_show`, and `my_air_canvas_language` explains how. Review folders were not marked before; they are now too, as the same field costs nothing.
*   `ClaudeSessionOpener` and `ShownSessions` learned canvas tabs in M5.

## State and next steps

Everything is built and unit-tested (`./gradlew test buildPlugin`, `npm test` in `canvas-web/`), but nothing of M4 to M7 has run in the sandbox since the spike. A walk-through for the user, in a sandbox IDE started as CLAUDE.md says:

1.  **New Canvas...:** in Folders mode, on a plain folder, a plan folder, a review folder and nothing selected; the folder `Canvas: <name>` lands where `canvasParent` says, with the canvas icon, and a second one with the same name gets ` (2)`. With a second profile listed, the profile dialog shows, `default` preselected.
2.  **The manager starts:** the tab opens with the placeholder frame, and the manager starts in the parent's start directory with its prompt file (`--append-system-prompt-file`, the 37k-character prompt) and `CANVAS_MANAGER_FIRST_MESSAGE`; it is filed in the canvas folder in the tree.
3.  **Show and validation:** asked to show something, the manager calls `my_air_canvas_show`; the frame renders, and the tool's answer has `validated: true`. A frame with a deliberate error (an unknown component) comes back with `ok: false` and the error, and the toolbar shows it. An ordinary session, told the canvas's path, finds it in `my_air_folders_get` (kind canvas), reads `my_air_canvas_language` and shows a frame; its author is learned once its transcript is scanned.
4.  **Form submit:** a form with a `@ToAssistant` button, filled and clicked, arrives in the terminal as `[canvas <folder> · <frame>] ...` with the form state as JSON; with the manager stopped, a balloon says it was not sent.
5.  **OpenFile:** a `FileRef` under the start directory opens the file at its line; one outside it, or a missing file, gives a balloon. `@OpenUrl` opens the browser for https only.
6.  **Restore:** after an IDE restart the canvas tab comes back with its frames, the frame paged to, a half-filled form's state, and the manager resumed (no first message again).
7.  **Theme switch:** light to dark and back re-renders the frame in the new theme.
8.  **Menus:** in a canvas folder, New Plan..., Make Folder a Plan and Turn into Plan... are disabled; dropping a plan or review folder into it is refused; Open Canvas opens it; New Review... from it goes beside it.

Later items are listed after the milestone table.

## Decisions

1.  The node kind is called **Canvas**: the folder `Canvas: <name>`, the tab `myair-canvas://`, the packages `canvas` and `canvasview`.
2.  The bundle uses the prebuilt `openuiLibrary` from `@openuidev/react-ui`, extended with My Air's components. Our own small library is not pursued.
3.  A canvas folder may lie inside a plan folder.
4.  Any session may write to any canvas. Clicks go to the canvas's manager in v1, naming the frame's author.
5.  An invalid frame is stored anyway; `my_air_canvas_show` says it is invalid and asks for a fixed one (M4).
6.  Any canvas page of the IDE checks any canvas's frame (M4).
7.  A new canvas manager runs in the folder's start directory, else the IDE project's directory (M5).
8.  A click is sent only while the manager runs in this tab; otherwise it is dropped with a balloon, never queued (M6).
9.  `@OpenFile` opens only files under the folder's start directory or the manager's cwd, checked lexically (M6).
10. New Canvas... and New Review... started in a review or canvas folder go beside the outermost of them rather than being refused (M7).
11. `my_air_folders_get` marks review and canvas folders with `kind`; ordinary sessions learn of canvases from one sentence of their system prompt, not the OpenUI reference (M7).
