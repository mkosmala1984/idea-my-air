import { afterEach, describe, expect, it } from "vitest";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { createParser, Renderer, type ActionEvent } from "@openuidev/react-lang";
import { canvasLibrary } from "../library";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// Rendered in a Stack: OpenUI's Tabs never settle in jsdom, so the tab case is only parsed.
const PATCH = `diff --git a/src/a.ts b/src/a.ts\n--- a/src/a.ts\n+++ b/src/a.ts\n@@ -10,2 +10,2 @@ f\n keep\n-old\n+new\n`;
const IN_TABS = `root = Stack([tabs])
tabs = Tabs([TabItem("d", "Diff", [diff])])
diff = Diff("${PATCH}")`;
const DOC = `root = Stack([diff])
diff = Diff("diff --git a/src/a.ts b/src/a.ts\n--- a/src/a.ts\n+++ b/src/a.ts\n@@ -10,2 +10,2 @@ f\n keep\n-old\n+new\n")`;

describe("Diff", () => {
  let root: Root | undefined;
  let host: HTMLDivElement;
  afterEach(() => act(() => root?.unmount()));

  async function render(events: ActionEvent[]) {
    host = document.createElement("div");
    document.body.appendChild(host);
    root = createRoot(host);
    await act(async () => root!.render(<Renderer response={DOC} library={canvasLibrary} isStreaming={false} onAction={(e) => events.push(e)} />));
  }
  const button = (text: string, nth = 0) => [...host.querySelectorAll("button")].filter((b) => b.textContent === text)[nth];

  it("parses inside a tab with no errors", () => {
    const result = createParser(canvasLibrary.toJSONSchema(), "Stack").parse(IN_TABS);
    expect(result.meta.errors).toEqual([]);
  });

  it("opens the file at a line number and sends a line comment with its context", async () => {
    const events: ActionEvent[] = [];
    await render(events);
    expect(host.textContent).toContain("src/a.ts");
    await act(async () => button("11", 1).click());
    expect(events.map((e) => [e.type, e.params])).toEqual([["OpenFile", { path: "src/a.ts", line: 11 }]]);

    // The + of the added line (rows: keep, old, new; two gutters each).
    const plus = [...host.querySelectorAll<HTMLButtonElement>(".myair-diff-comment-btn")][5];
    await act(async () => plus.click());
    const area = host.querySelector("textarea")!;
    await act(async () => {
      const set = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(area), "value")!.set!;
      set.call(area, "why not keep old?");
      area.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => button("Send to agent").click());
    const sent = events.at(-1)!;
    expect(sent.type).toBe("continue_conversation");
    expect(sent.humanFriendlyMessage).toBe("src/a.ts:11 why not keep old?");
    expect(sent.params.context).toEqual({ file: "src/a.ts", line: 11, side: "new", kind: "add", code: "new", comment: "why not keep old?" });
    expect(host.querySelector("textarea")).toBeNull();
  });

  it("switches to the split view", async () => {
    await render([]);
    await act(async () => button("Split").click());
    expect(host.querySelector(".myair-diff-split")).not.toBeNull();
  });
});
