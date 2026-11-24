import { describe, expect, it } from "vitest";
import { BuiltinActionType } from "@openuidev/react-lang";
import contract from "./protocol.json";
import { installBridge, StatePoster, validateDocument, wireAction } from "./bridge";
import { canvasLibrary } from "./library";
import { MESSAGE_TYPES, PAGE_CALLS, type PageMessage } from "./protocol";

/** Timers run by hand: [run] fires every pending one. */
function manualTimers() {
  const pending = new Map<number, () => void>();
  let next = 0;
  return {
    set: (fn: () => void) => (pending.set(++next, fn), next),
    clear: (h: unknown) => void pending.delete(h as number),
    run: () => [...pending.entries()].forEach(([h, fn]) => (pending.delete(h), fn())),
    get size() { return pending.size; },
  };
}

/** A window of its own: a stub myAirPost collects what the page posts. */
function page() {
  const win = new EventTarget() as unknown as Window;
  const posted: PageMessage[] = [];
  const timers = manualTimers();
  const bridge = installBridge(win, timers);
  win.myAirPost = (json) => posted.push(JSON.parse(json));
  return { win, posted, timers, bridge };
}

describe("protocol.json, shared with the plugin's CanvasProtocolTest", () => {
  it("lists window.myAir's functions", () => {
    expect(contract.pageCalls).toEqual([...PAGE_CALLS]);
    expect(Object.keys(page().win.myAir).sort()).toEqual([...contract.pageCalls].sort());
  });

  it("lists every message type the page posts", () => {
    expect(contract.messages).toEqual([...MESSAGE_TYPES]);
  });

  it("lists the library's custom actions and the renderer's built-in ones", () => {
    expect(Object.keys(canvasLibrary.actions ?? {}).sort()).toEqual([...contract.customActions].sort());
    expect(Object.values(BuiltinActionType).sort()).toEqual([...contract.builtinActions].sort());
  });
});

describe("validate", () => {
  it("answers an unknown component with ok false and the parser's error, without rendering", () => {
    const { win, posted } = page();
    win.myAir.connect();
    win.myAir.validate(JSON.stringify({ id: "v1", openui: 'root = Stack([w])\nw = Widget("x")\nlost = TextContent("never shown")' }));
    expect(posted).toHaveLength(1);
    const answer = posted[0] as Extract<PageMessage, { type: "validated" }>;
    expect(answer.type).toBe("validated");
    expect(answer.id).toBe("v1");
    expect(answer.ok).toBe(false);
    expect(answer.errors.map((e) => [e.code, e.component, e.statementId])).toEqual([["unknown-component", "Widget", "w"]]);
    expect(answer.errors[0].message).toContain("Widget");
    expect(answer.orphaned).toEqual(["lost"]);
    expect(answer.unresolved).toEqual([]);
  });

  it("names an undefined reference", () => {
    expect(validateDocument("v2", "root = Stack([a, missing])\na = TextContent(\"hi\")")).toMatchObject({ id: "v2", unresolved: ["missing"] });
  });

  it("answers a good document with ok true and nothing else", () => {
    expect(validateDocument("v3", 'root = Stack([FileRef("a.kt", 3)])')).toEqual({ type: "validated", id: "v3", ok: true, errors: [], unresolved: [], orphaned: [] });
  });
});

describe("the queue", () => {
  it("holds messages until connect, then posts them in order", () => {
    const { win, posted, bridge } = page();
    bridge.post({ type: "ready", ms: 1 });
    win.myAir.validate(JSON.stringify({ id: "v", openui: "root = Stack([])" }));
    expect(posted).toEqual([]);
    win.myAir.connect();
    expect(posted.map((m) => m.type)).toEqual(["ready", "validated"]);
    bridge.post({ type: "js-error", message: "x" });
    expect(posted.map((m) => m.type)).toEqual(["ready", "validated", "js-error"]);
  });

  it("hands a show that came before the renderer to it once it subscribes", () => {
    const { win, bridge } = page();
    win.myAir.show(JSON.stringify({ frame: "C1", openui: "root = Stack([])", dark: true }));
    const shown: string[] = [];
    bridge.onShow((call) => shown.push(call.frame));
    win.myAir.show(JSON.stringify({ frame: "C2", openui: "root = Stack([])", dark: false }));
    expect(shown).toEqual(["C1", "C2"]);
  });
});

describe("actions", () => {
  const form = { fix: { test: { value: "watcher" } }, other: { q: { value: "x" } } };

  it("keep the form state of the form they came from", () => {
    expect(wireAction({ type: "continue_conversation", params: { context: "c" }, humanFriendlyMessage: "Fix", formName: "fix", formState: { fix: form.fix } }))
      .toEqual({ type: "continue_conversation", params: { context: "c" }, humanFriendlyMessage: "Fix", formName: "fix", formState: { fix: form.fix } });
  });

  it("drop the page's whole form state when they came from no form", () => {
    expect(wireAction({ type: "open_url", params: { url: "https://x.dev" }, humanFriendlyMessage: "", formState: form }))
      .toEqual({ type: "open_url", params: { url: "https://x.dev" }, humanFriendlyMessage: "" });
    expect(wireAction({ type: "OpenFile", params: { path: "a.kt" }, humanFriendlyMessage: "", formState: form }))
      .toEqual({ type: "OpenFile", params: { path: "a.kt" }, humanFriendlyMessage: "" });
  });
});

describe("form state", () => {
  function poster() {
    const posted: PageMessage[] = [];
    const timers = manualTimers();
    return { posted, timers, states: new StatePoster((m) => posted.push(m), 300, timers) };
  }

  it("is posted once per burst of updates, with the last state", () => {
    const { posted, timers, states } = poster();
    states.update("C1", { f: { a: { value: "w" } } });
    states.update("C1", { f: { a: { value: "w" } } });
    states.update("C1", { f: { a: { value: "wa" } } });
    expect(timers.size).toBe(1);
    timers.run();
    expect(posted).toEqual([{ type: "state", frame: "C1", state: { f: { a: { value: "wa" } } } }]);
  });

  it("is not posted when it equals the last posted, or the state the frame was shown with", () => {
    const { posted, timers, states } = poster();
    states.shown("C1", { f: { a: { value: "x" } } });
    states.update("C1", { f: { a: { value: "x" } } });
    timers.run();
    expect(posted).toEqual([]);
    states.update("C1", { f: { a: { value: "y" } } });
    timers.run();
    states.update("C1", { f: { a: { value: "y" } } });
    timers.run();
    expect(posted).toHaveLength(1);
  });

  it("is kept apart per frame", () => {
    const { posted, timers, states } = poster();
    states.update("C1", { a: 1 });
    states.update("C2", { a: 1 });
    timers.run();
    expect(posted.map((m) => (m as { frame: string }).frame).sort()).toEqual(["C1", "C2"]);
  });

  it("a change back to the last posted value cancels the pending post", () => {
    const { posted, timers, states } = poster();
    states.update("C1", { a: 1 });
    timers.run();
    states.update("C1", { a: 2 });
    states.update("C1", { a: 1 });
    timers.run();
    expect(posted).toHaveLength(1);
  });
});
