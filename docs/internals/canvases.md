# Canvas folders

A canvas folder is a folder whose tab renders what agents show there: OpenUI Lang documents, called frames, drawn by a JCEF webview next to the terminal of the canvas's manager session. The design, with its milestones, is `docs/designs/openui-canvas.md`. This file describes what is built: the store, the service, the placement rules, the MCP tools and the render check (M3, M4), the tab with its manager, clicks on a frame (M5, M6), and the session tree's New Canvas..., Open Canvas and row icon (M7).

## The file

Each canvas lives in `~/.my-air/canvases/<folderId>/canvas.json` (`store/Canvases.kt`, `CanvasFiles`). The folder id is the only link, as for reviews, so `structure.json` does not change and needs no new version. A folder gone from the structure leaves its file on disk, ignored, so restoring the folder from history finds its canvas again.

The file has `format` 1, the `manager` (session, cwd, project and, when not `default`, profile; or null), `next` (the number of the next frame) and `frames`, oldest first. Each frame has `id` (`C<n>`), `title` (one line, or null), `openui` (the document), `created`, `author` (a session id, null until learned) and `state` (the form and `$variable` state the page reported, or null).

Reading is strict. The format is checked first, so a file written by a newer My Air says so instead of failing on a key it does not know. Then every object must have exactly its keys. A file that cannot be read shows as a failure, it is not skipped.

Writes go through `writeAtomically`. A canvas keeps the newest 50 frames; older ones are dropped on write. `next` never goes down, not even when the canvas is cleared, so a frame id is never given twice.

## The service

`CanvasService` (`canvas/`) is an application service shaped like `ReviewService`, created by `CanvasServiceStartup` with the first project:

- `canvases` maps folder ids to the canvas, for folders in the structure only. `onDiskIds` lists every `canvas.json` on disk.
- It reads every canvas off the EDT, on each `ClaudeSessionsService` change and on file events under `canvases/` (a `ClaudeHomeWatcher` on that directory and each folder's subdirectory, so writes from another IDE arrive too). Only the latest read lands.
- It is the only writer. `update(folderId, op)` reads the file as it is on disk, applies `op` and writes when something changed. `create` refuses a folder that has a `canvas.json` already. `moveManagers` follows `/clear` and `/resume`, called by `ClaudeSessionsService` after the review managers move.
- Listeners (`subscribe`) are called on the EDT after every change.

## Where a canvas folder may lie

The kind rules live in `folders/KindPlacement.kt` and run on every structure write (`ClaudeSessionsService.updateFolders` calls `refuseKindPlacement` with the review and canvas ids on disk) and on every folder drop in the tree (`dropChange` asks `placementProblem`):

- a canvas folder holds no plan, review or canvas folder (ordinary folders are fine);
- a canvas folder lies in no review or canvas folder;
- a canvas folder may lie in a plan folder, so a plan task can have a canvas;
- the review rules stay as they were: a review folder holds no plan and lies in no review, plan or canvas folder.

Only placements new in a write count, so an old structure does not block unrelated writes.

The tree's menus follow the same rules where they can tell in advance: New Plan..., Make Folder a Plan and Turn into Plan... are disabled in a review or canvas folder and on one (`inReviewOrCanvas`), and a drop that would break a rule is refused (`dropChange`). New Review... and New Canvas... are never disabled for the place: they go beside the review or canvas folder they were started in (see below).

## In the session tree

- **New Canvas...** (`NewCanvasAction`, `canvasview/NewCanvas.kt`) is on the tree's menu in Folders mode, below New Review..., enabled while `structure.json` can be read. It asks for a name and, when profiles besides `default` are listed, for the manager's profile (`chooseProfile`, `default` preselected), then `startCanvas`:
  - The parent is the folder of the selected row (`targetFolder`, the top level with none), or, when that is or lies in a review or canvas folder, the parent of the outermost of them (`canvasParent`); a plan folder or a plain folder holds a canvas.
  - The manager is a new session in the parent's start directory (`canvasStartDir`), else the IDE project's directory, that directory also its project, in the chosen profile.
  - `canvas.json` is written first with that manager stored (`CanvasService.create`), then, in one structure write, the folder `Canvas: <name>` (a `/` as `-`, ` (2)`, ` (3)`... when taken among its siblings: `canvasFolderName`) with the manager filed in it; the write checks that the parent is still where `canvasParent` put it. A failed write leaves an ignored `canvas.json`, as for a review.
  - The tab opens (`CanvasOpener.open`); it starts the stored manager with `--session-id` and `CANVAS_MANAGER_FIRST_MESSAGE`, since it has no transcript yet.
- **Open Canvas** on a canvas folder's row (a folder of `CanvasService.canvases`, readable or not) opens its tab, or focuses it where its manager shows in another IDE. A double-click or Enter toggles the row, as on a review folder.
- A canvas folder's row has `CANVAS_ICON` (after a plan's and a review's icon), repainted on every `CanvasService` change. It shows no frame count.
- `my_air_folders_get` (and the tree `my_air_folders_apply` and `my_air_history_restore` return) marks a canvas folder with `kind: canvas` and a review folder with `kind: review`, so an agent finds them whatever the user renamed them to. Every session's system prompt says in one sentence that canvas folders exist and that `my_air_canvas_language` explains how to show a frame there; ordinary sessions get no OpenUI reference.
- New Review... started in a canvas folder goes beside it too (`reviewParent` takes the canvas ids), linked to the plan holding the canvas when one does.

## The MCP tools

`CanvasMcpToolset` (`mcp/`, registered in `my-air-mcp.xml`) has five tools. Each but the last names the canvas by its folder id or its path, as the review tools do; a folder that is not a canvas, or a canvas that cannot be read, fails with a message naming it. The tools do not know which session calls them, so any session may write to any canvas.

- `my_air_canvas_show(folder, openui, title?)` stores a new frame, then has a canvas page check it and returns `{folder, path, frame, rev, stored, validated, ok, errors, unresolved, orphaned, boards, note}`. Each error has `code`, `message`, `statementId`, `component`, `path`, `hint` and `severity` (`error` or `warning`).
- `my_air_canvas_patch(folder, frame, patch, expect)` changes a frame's document in place by statements, as lang-core's `mergeStatements` does (`canvas/OpenUiMerge.kt`, a port so it works without a page): a statement of an existing name replaces it, a new one is appended, `name = null` deletes it, and statements `root` no longer reaches are dropped. `expect` is the frame's `rev` (a 12-hex SHA-256 prefix of its document, `openUiRev`), which `show`, `get` and `patch` return; any other rev is refused with the current rev and document. The frame keeps its id, title and state; the tab re-pushes it when it is the one shown, since its text changed. The patched document is checked like a shown one, and the answer is `show`'s plus `set` and `removed`.
- `my_air_canvas_get(folder)` returns the manager, the frames (id, rev, title, created, author) and the newest frame whole (`rev`, `openui` and `state`).
- `my_air_canvas_apply(folder, changes)` applies `remove_frame {id}`, `set_title {id, title}` and `clear {}`, all or none. An unknown op or field fails naming it.
- `my_air_canvas_language()` returns My Air's canvas rules followed by the OpenUI reference of the canvas library (`resources/canvas/system-prompt.txt`, about 9k tokens). The show tool's description tells a session that is not the manager to read it first.

### The render check

The MCP server runs in the IDE, but it has no page of its own. Every canvas tab's page registers with `CanvasRenderers` (`canvasview/`) for its folder. `my_air_canvas_show` picks a page with `chooseValidator`: a loaded page of the same canvas, else any loaded page, else any page (it answers once loaded). Every page has the same library, so any page can check any canvas's frame.

The page parses the document without showing it (`window.myAir.validate`) and answers `validated`; a document with a Board is first rendered out of sight to measure it (see Board). The tool waits up to 5 s off the EDT. It then reports:

- `validated: true`, with `ok` true when the document has a root, no error and no board problem that is an error (warnings allowed), and the errors, unresolved references, orphaned statements and `boards`: each Board's geometry from the document alone (see Board below);
- `validated: false`, `ok: null`, and a `note`, when no canvas page is open in this IDE, when the page did not answer within 5 s, or when its tab closed first.

The frame is stored before the check and stays stored whatever the check says, also when the document is invalid. The user then sees what came (the renderer shows what it can), and the note asks the agent to fix the errors and show the frame again. Storing only valid frames would hide a failure from the user, and the check is not always available anyway.

## Frame authors

A frame's `author` is learned from transcripts. The scanner keeps `my_air_canvas_show` calls of the main conversation (matched by suffix, as the brief tool is) and reads `folder` and `frame` from each successful result's JSON into `ClaudeSession.shownFrames`. A result that is an error, or not that JSON, is skipped. After each change of the sessions, `CanvasService` gives every frame without an author the session whose transcript shows it (`learnAuthors`); an author once set is kept. A call made by a subagent is not in the main conversation, so its frame keeps a null author.

## Prompts

`canvas/CanvasPrompts.kt` holds `canvasRules()` (what the canvas is for, how to read the check, that the OpenUI reference applies to the `openui` argument only and replies stay ordinary text, no `Query()`/`Mutation()`, no network), `canvasManagerPrompt(folderId, canvas)` (the canvas it manages by id, how clicks arrive as `[canvas <folder> · <frame>]` messages with their JSON, then the rules and the OpenUI reference) and `CANVAS_MANAGER_FIRST_MESSAGE`. The manager prompt is long and spans lines, so it must go through `--append-system-prompt-file`, as plan prompts do. `SessionTerminals` writes it to a prompt file for a canvas's manager (see the tab below).

## The tab

A canvas folder's tab is `myair-canvas://<folderId>` (`canvasview/`: `CanvasVirtualFile` under `CanvasFileSystem`, `CanvasTabs`, `CanvasEditor`, `CanvasOpener`), built like the review tab on `FolderTabs` and `ManagerTabEditor`: the canvas page on the left (`CanvasPanel`), the manager's terminal on the right, the split kept as `dev.myair.canvasView.split`. The tab is titled with the folder's name and has `CANVAS_ICON`; it is saved with the open tabs and restored after a restart. It waits while the folders load; a `canvas.json` that cannot be read before the canvas first showed shows why and starts no manager; once it showed, the toolbar names the error and the last frame stays. A folder gone (or its `canvas.json` gone) leaves a notice and ends the manager.

Shared code knows the tab: `ClaudeSessionOpener.open` opens a canvas manager's tab (`managedCanvasFolder`, after plan and review managers), `sessionFileOf` and `tabFileOf` give the manager of a canvas tab while its folder exists and the tab of a manager, deleting a folder asks before ending a working canvas manager (`managersConfirmed`), and a canvas manager can become no plan's manager (`managerRefusal`, `my_air_plan_set_manager`, Turn into Plan disabled).

### The manager

`CanvasTabs.startManager` keeps the manager's file while its process runs. Otherwise it reads `manager` from `canvas.json` (`canvasManagerStart`, on `managerStart`): none stored gives a new session in `canvasStartDir` (the nearest working directory or bound directory of the folder and its ancestors, as for a session started from the folder), else in the IDE project's directory, that directory also its project, in `default`'s profile, stored in `canvas.json` (`CanvasService.update`) and filed in the canvas folder; a stored one is resumed where and in the profile it was stored. While its session has no transcript, it gets `CANVAS_MANAGER_FIRST_MESSAGE` after `--`. A profile other than `default` is checked before a manager with no process starts (`profileToCheck`). Every start of its process writes `canvasManagerPrompt` to a prompt file passed with `--append-system-prompt-file` (`SessionTerminals` asks `ReviewTabs.managerPrompt`, then `CanvasTabs.managerPrompt`): the prompt holds the OpenUI reference and is too long for a command line.

### Frames on the page

The toolbar shows the frame's title (its id without one; the tooltip names the id, author and time), the problems the page reported, `n / m` with Previous and Next Frame, and Copy OpenUI Source. The tab shows the newest frame when it first shows and whenever a frame newer than the newest it knew arrives (from any session or IDE, through the service's file events); otherwise the frame the user paged to stays while it is kept, else the newest (`selectedFrame`; removing the newest frame keeps the choice). A frame is pushed to the page only when its id, text or the theme changed: the page keeps form state itself. The page posts a frame's state (debounced, only when changed), and the tab stores it in that frame (`setState`), so a restored tab shows a half-filled form again. With no frame it pushes a short placeholder document (`EMPTY_FRAME_ID`), since the page's own default is the spike's sample. Where JCEF is not supported, the left side says so and shows the frame's OpenUI source, read-only; the terminal works as usual.

The problems shown are the frame's parse errors, the renderer's errors and the page's script errors since the last push (`canvasProblems`): errors, and a document without root, count as errors; warnings, unresolved references and orphaned statements as warnings. The toolbar shows `2 errors, 1 warning` with the list in its tooltip, nothing when there are none.

### Clicks

A click on a frame goes through `commandOf` with the frame's author:

- `@ToAssistant` is sent to the manager's terminal (`SessionPane.send`) as `[canvas <folder> · <frame>] <message>` with its JSON block, only while the manager's process runs in this IDE and has started; otherwise a balloon says the click was not sent, and nothing is queued.
- `@OpenUrl` opens the browser (`BrowserUtil`), http and https only.
- `@OpenFile(path, line)` and `FileRef` open a file at its 1-based line, only under the folder's start directory or the manager's cwd (`canvasFileTarget`: a relative path resolves against the first, `..` is resolved lexically, links are not followed, and an absolute path must lie under one of them). The file's existence is checked off the EDT; a path outside, a missing file or no directory at all gives a balloon.
- `@CopyToClipboard(text)` copies the text.
- Any other action gives a balloon naming it.

## Diff

`canvas-web/src/diff/` adds `Diff(patch, view?, collapseAfter?)`: the patch is unified diff text as `git diff` or `git show` prints it. `parse.ts` (`parseDiff`, pure and lenient) reads git headers or plain `---`/`+++` pairs, new, deleted, renamed and binary files, and numbers the lines itself from each `@@` header, so a hand-written diff with wrong counts still reads; `splitRows` pairs a run of deletions with the additions after it for the side-by-side view, and `openLine` maps a deleted line to the next line of the new file. `DiffView.tsx` shows each file in a `<details>` (files after `collapseAfter` start closed) with a Unified/Split toggle. A path or line number sends `OpenFile`, and the `+` beside a line opens a comment box whose text goes out as `@ToAssistant`: the message is `path:line comment`, the context `{file, line, side, kind, code, comment}` (`commentAction`). It joins `Card`, `TabItem` and `AccordionItem` content. OpenUI's `Tabs` never settle under jsdom, so `diff.test.tsx` renders the Diff in a Stack and only parses the tab case.

## Board (spike)

`canvas-web/src/board/` adds a whiteboard to the canvas library: `Board(items, unit?, height?)` with `Shape`, `Note`, `Frame` and `Edge` items, drawn with React Flow (`@xyflow/react`).
- Board reads its items as OpenUI element nodes, not rendered components: an element's statement name is its id, its `box` holds whole grid units (`unit` px each, default 24) as `{x, y}` or one sibling anchor (`rightOf`, `leftOf`, `below`, `above`, with `gap` and `align`), plus optional `w` and `h`. Only a Frame's `content` goes through OpenUI's `renderNode`.
- `layout.ts` (`solve`, pure) turns the specs into rectangles: anchors resolved in any order, containers (`Shape` with children) laid out `free`, `row`, `column` or `grid` with padding and a header row, sized to fit unless `w`/`h` are given, leaves without `h` sized from the height the page measured. An edge's path is `routeEdge`: from the middle of one facing side to the other's, `orthogonal` turning twice halfway, `straight`, or `curved` (a cubic curve as a 24-point polyline); the board draws exactly that path (`BoardEdge`, from the nodes' current rectangles, so it follows a drag) and `edge-crosses` checks its segments, naming only the outermost element of a container it passes through. It reports problems with codes: `no-position`, `anchor-unknown`, `anchor-not-sibling`, `anchor-cycle`, `anchor-conflict`, `position-ignored`, `overlap`, `overflow`, `outside-container`, `edge-unknown`, `edge-self`, `edge-crosses`; `spec.ts` adds `inline-element` and `not-a-board-element`.
- The render check computes every Board's report (`checkBoards`): `board` (its statement name), `unit`, `elements` (id, kind, parent, x, y, w, h in grid units, `estimated` when the height is a default since nothing was measured), `edges` and `problems` (severity, code, ids, message). It rides in `validated` as `boards` (`CanvasBoard` on the Kotlin side, parsed strictly) and `my_air_canvas_show` returns it; a board error makes `ok` false, a board warning gives the `WARNINGS` note. The canvas rules tell agents to compare the rectangles with what they meant and fix every problem. When the document has a Board, `validateMeasured` hands it to `measureBoards` (`main.tsx`): a second React root in a hidden `.canvas-measure` element renders it at the boards' own widths, collects each Board's report through `BoardReportContext` until they rest for 150 ms (at most 2 s; a page in a hidden tab may never lay out) and is removed; a measured report replaces the parse-only one and `ok` is judged again, and without one in time the parse-only reports stand. The shown frame is not touched.
- The shown board passes its latest report, with measured heights, to `BoardReportContext`, which by default keeps it in `window.myAirBoards` and shows it behind a "Geometry" button.
- A board reaches down to the bottom of the canvas view (`useFillHeight`, following the window's size); a Board's `height` is its least height (default 320 px).
- After every frame switch the page repaints itself whole (`repaintAll` in `main.tsx`, an opacity flick on the body, again after 400 ms) and `CanvasBrowser` repaints its component after 150 and 600 ms: the IDE's off-screen JCEF browser kept parts of the previous frame on screen otherwise.
- Elements and edges can be selected (click; Ctrl, Meta or Shift + click adds; Shift + drag draws a box). While something is selected a bar at the board's top right takes a question; Enter or Send to agent sends it as `@ToAssistant` with the context `{selection: {board, elements: [{id, kind, parent, x, y, w, h, text}], edges: [{id, from, to, label?}]}}` (`selection.ts`), the typed text as the message or else `Selected on board <board>: <ids>`. The selection stays until cleared (× or Esc).
- The minimap is off by default (in its corner it covers content); a map button under the zoom controls toggles it.
- A Frame drags by its title only; its body is `nodrag nowheel nopan`, so forms and buttons inside keep working. A child of a `row`, `column` or `grid` container cannot be dragged, since its container places it.
- A drop posts `move` (frame, element, x, y in grid units of the container's content area from `movedBox`, and the box's own w and h or null). The tab writes it into the frame's document in place (`moveInFrame`, `moveBoardElement` in `canvas/OpenUiMerge.kt`): the element's box argument becomes `{x, y, w?, h?, pinned: true}`, an anchor giving way, and statements only the old box used are dropped, as a patch would. No revision is checked, so the move lands on the document as it is on disk; it changes the frame's `rev`, so an agent's patch based on the text before the move is refused with the new text. An element that is no statement of its own, or a move the document cannot take, gives a balloon. The page keeps the dragged position until the next document arrives with a different layout, so the element does not jump back while the write goes round. `pinned` is ignored by the solver; the Board's description tells agents to leave pinned elements where they are unless asked.
- In a plain browser, `index.html#board` shows `board-sample.oui`.
