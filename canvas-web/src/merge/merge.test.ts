import { describe, expect, it } from "vitest";
import { mergeStatements } from "@openuidev/lang-core";
import cases from "./merge-cases.json";

// merge-cases.json pins what lang-core's mergeStatements does; the plugin's Kotlin port (OpenUiMergeTest) must match it.
describe("merge-cases.json", () => {
  it.each(cases.map((c) => [c.name, c] as const))("%s: still lang-core's answer (node src/merge/cases.mjs rewrites it)", (_, c) => {
    expect(mergeStatements(c.existing, c.patch)).toBe(c.merged);
  });
});
