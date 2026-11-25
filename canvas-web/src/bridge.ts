// The page's side of the protocol (protocol.ts): window.myAir, the queue until connect, validation (rendering out of sight only to measure boards),
// and the trimming and debouncing of what the renderer reports. No React here, so tests drive it directly.
import { createParser, type ActionEvent, type OpenUIError, type ParseResult } from "@openuidev/react-lang";
import { canvasLibrary } from "./library";
import { checkBoards } from "./board/spec";
import type { BoardReport } from "./board/layout";
import type { PageMessage, ShowCall, ValidateCall, WireAction, WireError } from "./protocol";

declare global {
  interface Window {
    /** Called by the plugin (executeJavaScript); see protocol.ts. */
    myAir: { show(json: string): void; validate(json: string): void; connect(): void };
    /** Injected by the plugin (JBCefJSQuery) before it calls connect(); absent in a plain browser. */
    myAirPost?: (json: string) => void;
  }
}

/** How long the form state must rest before it is posted: onStateUpdate fires twice on every keystroke. */
export const STATE_DEBOUNCE_MS = 300;

/** A parser's prop validation error (lang-core's ValidationError, which react-lang does not re-export). */
type ValidationError = ParseResult["meta"]["errors"][number];

// Made on first use, not at module load: vitest's module runner, importing this module and the library at once, can
// hand this module the library's exports before they are set.
let parser: ReturnType<typeof createParser> | undefined;
const parserOf = () => (parser ??= createParser(canvasLibrary.toJSONSchema(), canvasLibrary.root ?? "Stack"));

/** The parser's or the renderer's errors as the plugin reads them; fields the plugin does not use are dropped. */
export function wireErrors(errors: ReadonlyArray<OpenUIError | ValidationError>): WireError[] {
  return errors.map((e) => {
    const out: WireError = { code: e.code, message: e.message };
    if (e.statementId) out.statementId = e.statementId;
    if (e.component) out.component = e.component;
    if (e.path) out.path = e.path;
    if ("hint" in e && e.hint) out.hint = e.hint;
    if (e.severity) out.severity = e.severity;
    return out;
  });
}

/** The `parsed` fields of a parse result; `ok` when it has a root. */
export function parseSummary(result: ParseResult | null) {
  return {
    ok: result?.root != null,
    orphaned: result?.meta.orphaned ?? [],
    unresolved: result?.meta.unresolved ?? [],
    errors: wireErrors(result?.meta.errors ?? []),
  };
}

type Validated = Extract<PageMessage, { type: "validated" }>;

/** A document's check from the parse alone: the `parsed` fields and every Board's report, nothing measured. */
type Checked = { summary: ReturnType<typeof parseSummary>; boards: BoardReport[] };

function checkDocument(openui: string): Checked {
  const result = parserOf().parse(openui);
  return { summary: parseSummary(result), boards: checkBoards(result.root) };
}

/** The `validated` answer: [ok] when there is a root, no error and no board problem that is an error. */
function answer(id: string, { summary, boards }: Checked): Validated {
  const clean = summary.errors.every((e) => e.severity === "warning") && boards.every((b) => b.problems.every((p) => p.severity === "warning"));
  return { type: "validated", id, ...summary, ok: summary.ok && clean, boards };
}

const failed = (id: string, e: unknown): Validated =>
  ({ type: "validated", id, ok: false, errors: [{ code: "parse-exception", message: String(e) }], unresolved: [], orphaned: [], boards: [] });

/** Parses [openui] against canvasLibrary without rendering it: the `validated` answer to `validate` [id]. */
export function validateDocument(id: string, openui: string): Validated {
  try {
    return answer(id, checkDocument(openui));
  } catch (e) {
    return failed(id, e);
  }
}

/**
 * Renders a document out of sight and resolves with every Board's report once the measured heights settle, or null
 * when nothing could be measured in time (main.tsx `measureBoards`).
 */
export type BoardMeasurer = (openui: string) => Promise<BoardReport[] | null>;

/**
 * The `validated` answer with the heights the page measured: a board [measure] reported replaces its parse-only
 * report (estimated heights and the problems that follow from them), the others stay as parsed. Synchronous when
 * there is nothing to measure.
 */
export function validateMeasured(id: string, openui: string, measure: BoardMeasurer | undefined): Validated | Promise<Validated> {
  let checked: Checked;
  try {
    checked = checkDocument(openui);
  } catch (e) {
    return failed(id, e);
  }
  if (!measure || !checked.summary.ok || checked.boards.length === 0) return answer(id, checked);
  return measure(openui).catch(() => null).then((measured) =>
    answer(id, measured ? { ...checked, boards: checked.boards.map((b) => measured.find((m) => m.board === b.board) ?? b) } : checked));
}

/**
 * A click as the plugin gets it. The renderer attaches the page's whole form state to every action; it is kept only
 * when the click came from a form (formName set), so an @OpenUrl or a FileRef does not send unrelated input along.
 */
export function wireAction(event: ActionEvent): WireAction {
  const out: WireAction = { type: event.type, params: event.params ?? {}, humanFriendlyMessage: event.humanFriendlyMessage ?? "" };
  if (event.formName) {
    out.formName = event.formName;
    if (event.formState) out.formState = event.formState;
  }
  return out;
}

type Timers = { set(fn: () => void, ms: number): unknown; clear(handle: unknown): void };
const realTimers: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) };

/**
 * Posts a frame's form state once it has rested [delayMs], and only when its JSON differs from the last posted (or
 * shown) for that frame.
 */
export class StatePoster {
  private readonly last = new Map<string, string>();
  private readonly pending = new Map<string, { handle: unknown; json: string }>();

  constructor(private readonly post: (message: PageMessage) => void, private readonly delayMs = STATE_DEBOUNCE_MS, private readonly timers = realTimers) {}

  /** The state [frame] was shown with: an identical update is not posted back. */
  shown(frame: string, state: Record<string, unknown> | null | undefined) {
    if (state) this.last.set(frame, JSON.stringify(state));
  }

  update(frame: string, state: Record<string, unknown>) {
    const json = JSON.stringify(state);
    const before = this.pending.get(frame);
    if (before) this.timers.clear(before.handle);
    if (json === this.last.get(frame)) {
      this.pending.delete(frame);
      return;
    }
    const handle = this.timers.set(() => {
      this.pending.delete(frame);
      this.last.set(frame, json);
      this.post({ type: "state", frame, state: JSON.parse(json) });
    }, this.delayMs);
    this.pending.set(frame, { handle, json });
  }
}

export type Bridge = {
  post(message: PageMessage): void;
  /** Receives every `show`; one that came before the handler was set is handed over at once. */
  onShow(handler: (call: ShowCall) => void): void;
  readonly states: StatePoster;
  /** Has `validate` measure the boards of a document by rendering it ([validateMeasured]); without it, nothing is measured. */
  setMeasurer(measure: BoardMeasurer): void;
};

/** Installs window.myAir on [win]; messages wait in a queue until the plugin calls connect(). */
export function installBridge(win: Window, timers: Timers = realTimers): Bridge {
  const queued: string[] = [];
  let connected = false;
  const post = (message: PageMessage) => {
    const json = JSON.stringify(message);
    if (connected && win.myAirPost) win.myAirPost(json);
    else queued.push(json);
  };
  let showHandler: ((call: ShowCall) => void) | undefined;
  let early: ShowCall | undefined;
  const states = new StatePoster(post, STATE_DEBOUNCE_MS, timers);
  let measurer: BoardMeasurer | undefined;
  win.myAir = {
    show(json) {
      const call = JSON.parse(json) as ShowCall;
      states.shown(call.frame, call.state);
      if (showHandler) showHandler(call);
      else early = call;
    },
    validate(json) {
      const call = JSON.parse(json) as ValidateCall;
      const validated = validateMeasured(call.id, call.openui, measurer);
      if (validated instanceof Promise) void validated.then(post);
      else post(validated);
    },
    connect() {
      connected = true;
      for (const json of queued.splice(0)) win.myAirPost!(json);
    },
  };
  win.addEventListener("error", (e) => post({ type: "js-error", message: String(e.message) }));
  win.addEventListener("unhandledrejection", (e) => post({ type: "js-error", message: String(e.reason) }));
  return {
    post,
    states,
    setMeasurer(measure) {
      measurer = measure;
    },
    onShow(handler) {
      showHandler = handler;
      if (early) {
        handler(early);
        early = undefined;
      }
    },
  };
}
