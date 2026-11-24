import { describe, expect, it } from "vitest";
import { renderToString } from "react-dom/server";
import { createParser, Renderer } from "@openuidev/react-lang";
import { canvasLibrary } from "./library";
import { SAMPLE } from "./sample";

describe("the spike's sample", () => {
  it("parses against canvasLibrary with nothing dropped", () => {
    const result = createParser(canvasLibrary.toJSONSchema(), "Stack").parse(SAMPLE);
    expect(result.meta.errors).toEqual([]);
    expect(result.meta.unresolved).toEqual([]);
    expect(result.meta.orphaned).toEqual([]);
    expect(result.root).not.toBeNull();
  });

  it("renders its header on the server", () => {
    const errors: unknown[] = [];
    const html = renderToString(<Renderer response={SAMPLE} library={canvasLibrary} isStreaming={false} onError={(e) => errors.push(...e)} />);
    expect(html).toContain("Flaky tests, last 7 days");
    expect(errors).toEqual([]);
  });
});
