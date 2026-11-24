import { afterEach, describe, expect, it } from "vitest";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { createParser, Renderer, type ActionEvent } from "@openuidev/react-lang";
import { canvasLibrary, systemPrompt } from "./library";
import committed from "../../src/main/resources/canvas/system-prompt.txt?raw";

// Tells React that act() is used here, as a test renderer would.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// A frame using everything My Air adds to openuiLibrary.
const MY_AIR_DOC = `root = Stack([card, buttons])
card = Card([CardHeader("Findings"), ref1, ref2])
ref1 = FileRef("src/main/kotlin/dev/myair/store/TabStore.kt", 42)
ref2 = FileRef("README.md", null, "The readme")
buttons = Buttons([open, copy])
open = Button("Open the test", @OpenFile("src/test/kotlin/dev/myair/store/TabStoreTest.kt", 7), "secondary")
copy = Button("Copy command", @CopyToClipboard("./gradlew test"), "tertiary")`;

const parse = (text: string) => createParser(canvasLibrary.toJSONSchema(), "Stack").parse(text);

describe("canvasLibrary", () => {
  it("keeps openuiLibrary's root and adds FileRef and the two actions", () => {
    expect(canvasLibrary.root).toBe("Stack");
    expect(canvasLibrary.components.FileRef).toBeDefined();
    expect(Object.keys(canvasLibrary.actions ?? {}).sort()).toEqual(["CopyToClipboard", "OpenFile"]);
  });

  it("parses a document with FileRef, @OpenFile and @CopyToClipboard with no errors", () => {
    const result = parse(MY_AIR_DOC);
    expect(result.meta.errors).toEqual([]);
    expect(result.meta.unresolved).toEqual([]);
    expect(result.meta.orphaned).toEqual([]);
    expect(result.root).not.toBeNull();
  });

  it("renders FileRef as path:line, or as its label", () => {
    const errors: unknown[] = [];
    const html = renderToString(<Renderer response={MY_AIR_DOC} library={canvasLibrary} isStreaming={false} onError={(e) => errors.push(...e)} />);
    expect(html).toContain("src/main/kotlin/dev/myair/store/TabStore.kt:42");
    expect(html).toContain("The readme");
    expect(errors).toEqual([]);
  });
});

describe("clicks", () => {
  let root: Root | undefined;
  afterEach(() => act(() => root?.unmount()));

  async function click(text: string, label: string): Promise<ActionEvent[]> {
    const events: ActionEvent[] = [];
    const host = document.createElement("div");
    document.body.appendChild(host);
    root = createRoot(host);
    await act(async () => root!.render(<Renderer response={text} library={canvasLibrary} isStreaming={false} onAction={(e) => events.push(e)} />));
    const button = [...host.querySelectorAll("button")].find((b) => b.textContent === label);
    expect(button, `a button "${label}"`).toBeDefined();
    await act(async () => button!.click());
    return events;
  }

  it("a FileRef emits OpenFile with its path and line", async () => {
    const events = await click(MY_AIR_DOC, "src/main/kotlin/dev/myair/store/TabStore.kt:42");
    expect(events.map((e) => [e.type, e.params])).toEqual([["OpenFile", { path: "src/main/kotlin/dev/myair/store/TabStore.kt", line: 42 }]]);
  });

  it("a FileRef without a line emits OpenFile with the path only", async () => {
    const events = await click(MY_AIR_DOC, "The readme");
    expect(events.map((e) => [e.type, e.params])).toEqual([["OpenFile", { path: "README.md" }]]);
  });

  it("a button with @CopyToClipboard emits CopyToClipboard with its text", async () => {
    const events = await click(MY_AIR_DOC, "Copy command");
    expect(events.map((e) => [e.type, e.params])).toEqual([["CopyToClipboard", { text: "./gradlew test" }]]);
  });
});

describe("the system prompt", () => {
  const prompt = systemPrompt();

  it("teaches FileRef, @OpenFile and @CopyToClipboard", () => {
    expect(prompt).toContain("FileRef(path: string, line?: number, label?: string)");
    expect(prompt).toContain("@OpenFile(path: string, line?: number)");
    expect(prompt).toContain("@CopyToClipboard(text: string)");
  });

  it("is the committed system-prompt.txt (npm run build regenerates it)", () => {
    expect(committed).toBe(prompt);
  });
});
