# dev.myair.canvas

## Overview

Canvas folders: the application service that reads and writes `~/.my-air/canvases/<folderId>/canvas.json`, the frame operations the MCP tools and (later) the tab apply, how frames learn their author from transcripts, and the canvas prompts. The model and the file (`Canvas`, `Frame`, `CanvasFiles`) live in `store/Canvases.kt`; the webview and its protocol in `canvasview/`; the tools in `mcp/CanvasMcpToolset.kt`. Behaviour across packages: `docs/internals/canvases.md`.

## Interface

- `CanvasService.kt`:
  - `@Service(APP) class CanvasService`, with `canvases`, `onDiskIds`, `canvas(folderId)`, `file(folderId)`, `subscribe(parent, listener)`, `update(folderId, op)`, `create(folderId, canvas)`, `moveManagers(switched)` and `refresh()`.
  - `class CanvasServiceStartup` (a `postStartupActivity` in `plugin.xml`).
  - Pure: `canvasesInStructure(all, structure)`, `shownFrames(sessions)`, `authorsDue(canvases, shows)`.
- `CanvasChanges.kt`: `managedCanvasFolder(canvases, sessionId)`, `addFrame(canvas, title, openui, now)`, `setState(canvas, frame, state)`, `setAuthor(canvas, frame, session)`, `learnAuthors(canvas, folderId, shows)`, and `object CanvasChanges` with `apply(canvas, changes)` (ops `remove_frame`, `set_title`, `clear`).
- `CanvasPrompts.kt`: `openUiPrompt` (resource `/canvas/system-prompt.txt`), `canvasRules()`, `canvasManagerPrompt(folderId, canvas)`, `canvasLanguage()` (what `my_air_canvas_language` returns) and `CANVAS_MANAGER_FIRST_MESSAGE`.

## Invariants

- `CanvasService` state (`canvases`, the listeners) is touched on the EDT only; reads run on one background thread and only the latest lands. It is the only writer of `canvas.json`. `update` reads the file from disk first (another IDE may have added a frame), as `ReviewService.update` does, and writes only when the canvas changed.
- `canvases` holds only folders of the structure; a `canvas.json` that cannot be read is a failed `Result`, never skipped. `onDiskIds` holds every `canvas.json` on disk, readable or not: what the placement rules (`folders/KindPlacement.kt`) know as canvas folders.
- Frame ids are `C<next>` and `next` is never lowered, also by `clear`, so a click on an old frame never reaches a new one. `addFrame` and `CanvasFiles.write` keep the newest 50 frames.
- `CanvasChanges.apply` is all or none; an unknown op or field fails naming the change (1-based) and the key.
- An author once set is kept (`setAuthor`); authors are learned only for frames without one, from `ClaudeSession.shownFrames` of the same folder.
- The manager prompt goes in a file (`--append-system-prompt-file`): it spans lines and holds the 36k-character OpenUI prompt. `CanvasPromptsTest` checks that it names every `CanvasMcpToolset` tool.

## Side Effects

- Creates `~/.my-air/canvases/` and writes `canvas.json` files; watches the directory (`ClaudeHomeWatcher`) until disposed.

## Verification

- `./gradlew test --tests 'dev.myair.canvas.*'`
