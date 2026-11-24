// Writes merge-cases.json: patch cases with the merged text that lang-core's own mergeStatements gives. The Kotlin
// port (dev.myair.canvas.mergeOpenUi, OpenUiMergeTest) must give the same text, and merge.test.ts checks that this
// file still matches lang-core. Run after changing a case: node src/merge/cases.mjs
import { writeFileSync } from "node:fs";
import { mergeStatements } from "@openuidev/lang-core";

const lines = (...l) => l.join("\n");

const CASES = [
  {
    name: "replaces a statement in place and appends a new one",
    existing: lines('root = Stack([a, b])', 'a = TextContent("A")', 'b = TextContent("B")'),
    patch: lines('b = TextContent("B2")', 'c = TextContent("C")', 'root = Stack([a, b, c])'),
  },
  {
    name: "drops statements root no longer reaches, transitively",
    existing: lines('root = Stack([card])', 'card = Card([h, t])', 'h = CardHeader("H")', 't = Table([Col("x", xs)])', 'xs = [1, 2]'),
    patch: "card = Card([h])",
  },
  {
    name: "deletes with null",
    existing: lines('root = Stack([a, b])', 'a = TextContent("A")', 'b = TextContent("B")'),
    patch: lines("root = Stack([a])", "b = null"),
  },
  {
    name: "keeps state variables though nothing references them",
    existing: lines('$tab = "one"', 'root = Stack([a])', 'a = TextContent("A")'),
    patch: 'a = TextContent("A2")',
  },
  {
    name: "object keys, members, calls and actions are no references",
    existing: lines(
      'root = Stack([b])',
      'b = Board([n, m])',
      'n = Note("n", {x: 0, y: 0})',
      'm = Note("m", {rightOf: "n", gap: 2})',
      'x = TextContent("never")',
      'rows = [1]',
      'y = Button("go", @ToAssistant("hi"))',
    ),
    patch: 'm = Note("m", {below: "n", gap: rows.length})',
  },
  {
    name: "strings with brackets, quotes and comments do not split statements",
    existing: lines(
      String.raw`root = Stack([a, b]) // the root`,
      String.raw`a = TextContent("a ( [ { \" b = x")`,
      "# a comment",
      String.raw`b = CodeBlock('js', 'it\'s ] }')`,
    ),
    patch: String.raw`a = TextContent("A, \n with ] inside")`,
  },
  {
    name: "a string runs on across a real line break",
    existing: lines('root = Stack([a])', 'a = TextContent("A")'),
    patch: lines('a = TextContent("one', 'b = two")'),
  },
  {
    name: "a statement spans lines inside brackets",
    existing: lines("root = Stack([", "  a,", "  b", "])", 'a = TextContent("A")', 'b = TextContent("B")'),
    patch: 'b = TextContent("B2")',
  },
  {
    name: "a ternary continued on a line starting with ?",
    existing: lines('root = Stack([v])', 'v = $tab == "one"', "  ? one", "  : two", 'one = TextContent("1")', 'two = TextContent("2")', '$tab = "one"'),
    patch: 'two = TextContent("II")',
  },
  {
    name: "references inside object values and ternaries count",
    existing: lines('root = Stack([b])', 'b = Board([n])', 'n = Note("n", {x: 0, y: k})', "k = 3"),
    patch: lines('n = Note("n", {x: flag ? k : 1, y: 0})', "flag = true"),
  },
  {
    name: "a patch in a code fence",
    existing: lines('root = Stack([a])', 'a = TextContent("A")'),
    patch: lines("```", 'a = TextContent("fenced")', "```"),
  },
];

const out = CASES.map((c) => ({ ...c, merged: mergeStatements(c.existing, c.patch) }));
writeFileSync(new URL("./merge-cases.json", import.meta.url), JSON.stringify(out, null, 2) + "\n");
console.log(`${out.length} cases`);
