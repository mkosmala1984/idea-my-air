// The messages between the canvas page and the plugin (dev.myair.canvasview.CanvasBridge on the Kotlin side).
// protocol.json lists the names of both directions and the custom actions; protocol.test.ts and the plugin's
// CanvasProtocolTest check their own side against it.
//
// Plugin -> page, through executeJavaScript, each with one JSON string argument (or none):
//   window.myAir.show(ShowCall)         renders a frame, replacing the one shown
//   window.myAir.validate(ValidateCall) parses a document WITHOUT showing it and posts `validated`, with the
//                                       geometry of every Board in it (board/layout.ts); a document with boards is
//                                       rendered out of sight first so their heights are measured (main.tsx)
//   window.myAir.connect()              the plugin has injected window.myAirPost; queued messages go out now
//
// Page -> plugin, through window.myAirPost(json) (a JBCefJSQuery), one PageMessage each. Messages posted before
// connect() wait in a queue.

import type { BoardReport } from "./board/layout";

/** `window.myAir.show`: frame [frame]'s OpenUI Lang text, its saved form state, in the IDE's theme. */
export type ShowCall = { frame: string; openui: string; state?: Record<string, unknown> | null; dark: boolean };

/** `window.myAir.validate`: check [openui] against the canvas library; the answer is `validated` with the same [id]. */
export type ValidateCall = { id: string; openui: string };

/** An error of the parser or the renderer, as the plugin reads it (OpenUIError / ValidationError, trimmed). */
export type WireError = {
  code: string;
  message: string;
  statementId?: string;
  component?: string;
  path?: string;
  hint?: string;
  /** "warning" when the document still renders; absent means an error. */
  severity?: "warning";
};

/** A click: `continue_conversation` (`@ToAssistant`), `open_url` (`@OpenUrl`) or a custom action's name. */
export type WireAction = {
  type: string;
  params: Record<string, unknown>;
  humanFriendlyMessage: string;
  /** Present only when the click came from a form: that form's state, keyed by its name. */
  formName?: string;
  formState?: Record<string, unknown>;
};

export type PageMessage =
  /** The page rendered for the first time, [ms] after its script started. */
  | { type: "ready"; ms: number }
  /** [frame] parsed: [ok] when it has a root; [orphaned] statements are unreachable, [unresolved] references undefined. */
  | { type: "parsed"; frame: string; ms: number; ok: boolean; orphaned: string[]; unresolved: string[]; errors: WireError[] }
  /** The answer to `validate` [id]; [ok] when it has a root, no error and no board problem that is an error (warnings allowed). */
  | { type: "validated"; id: string; ok: boolean; errors: WireError[]; unresolved: string[]; orphaned: string[]; boards: WireBoard[] }
  /** The renderer's errors on [frame] (Renderer onError); [] once they are resolved. */
  | { type: "errors"; frame: string; errors: WireError[] }
  /** The form and `$variable` state of [frame] changed (debounced, and only when it differs from the last posted). */
  | { type: "state"; frame: string; state: Record<string, unknown> }
  /** A click on [frame]. */
  | { type: "action"; frame: string; event: WireAction }
  /**
   * The user dragged board element [element] of [frame] to [x], [y]: grid units in its container's content area. [w]
   * and [h] are its box's own sizes, kept (null when it had none). The plugin writes it into the document, pinned.
   */
  | { type: "move"; frame: string; element: string; x: number; y: number; w: number | null; h: number | null }
  /** An uncaught script error in the page. */
  | { type: "js-error"; message: string };

/**
 * A Board's geometry as the solver computed it (board/layout.ts `BoardReport`): elements in grid units with their
 * parent and whether the height is `estimated`, the edges, and the problems (code, ids, message), errors and warnings.
 */
export type WireBoard = BoardReport;

export type MessageType = PageMessage["type"];

/** The names of window.myAir's functions; must equal protocol.json's `pageCalls`. */
export const PAGE_CALLS = ["show", "validate", "connect"] as const;

/** Every PageMessage type; must equal protocol.json's `messages`. */
export const MESSAGE_TYPES: readonly MessageType[] = ["ready", "parsed", "validated", "errors", "state", "action", "move", "js-error"];
