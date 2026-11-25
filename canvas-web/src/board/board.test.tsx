import { describe, expect, it } from "vitest";
import { createParser, type ElementNode } from "@openuidev/react-lang";
import { canvasLibrary } from "../library";
import { report, solve } from "./layout";
import { boardSpec } from "./spec";
import { selectionContext, selectionMessage } from "./selection";
import sample from "./board-sample.oui?raw";

const parse = (text: string) => createParser(canvasLibrary.toJSONSchema(), "Stack").parse(text);

/** The first Board element node in a parsed tree. */
function findBoard(node: unknown): ElementNode | undefined {
  if (Array.isArray(node)) return node.map(findBoard).find(Boolean);
  const n = node as ElementNode | null;
  if (n?.type !== "element") return undefined;
  if (n.typeName === "Board") return n;
  return Object.values(n.props).map(findBoard).find(Boolean);
}

describe("the board sample", () => {
  const result = parse(sample);

  it("parses with no errors, unresolved or orphaned statements", () => {
    expect(result.meta.errors).toEqual([]);
    expect(result.meta.unresolved).toEqual([]);
    expect(result.meta.orphaned).toEqual([]);
  });

  it("lays out with no problems from the parse alone, as the agent's check would", () => {
    const spec = boardSpec(findBoard(result.root)!.props.items as unknown[]);
    expect(spec.problems).toEqual([]);
    const geometry = report("board", spec.elements, spec.edges, solve(spec.elements, spec.edges), 24, spec.problems);
    expect(geometry.problems).toEqual([]);
    const at = (id: string) => geometry.elements.find((e) => e.id === id);
    // Migrator: a row of three 6x3 shapes with gap 2, padding 1, a header row: 24x6, centred on the 5-high source.
    expect(at("pipeline")).toMatchObject({ x: 10, y: 1, w: 24, h: 6 });
    expect(at("map")).toMatchObject({ parent: "pipeline", x: 19, y: 3 });
    expect(at("target")).toMatchObject({ x: 38, y: 1 });
    expect(geometry.edges.map((e) => e.id)).toEqual(["m1", "m2", "e1", "e2", "e3", "e4"]);
  });
});

describe("boardSpec", () => {
  const itemsOf = (doc: string) => findBoard(parse(doc).root)!.props.items as unknown[];

  it("names elements by their statements and warns about inline ones", () => {
    const spec = boardSpec(itemsOf(`root = Stack([b])
b = Board([n, Note("inline", {x: 9, y: 0})])
n = Note("named", {x: 0, y: 0})`));
    expect(spec.elements.map((e) => e.id)).toEqual(["n", "note#1"]);
    expect(spec.problems.map((p) => p.code)).toEqual(["inline-element"]);
  });

  it("collects edges and elements from shapes' children", () => {
    const spec = boardSpec(itemsOf(`root = Stack([b])
b = Board([g])
g = Shape("group", "G", {x: 0, y: 0}, [a, f, e])
a = Note("a", {x: 0, y: 0})
f = Frame("F", {rightOf: "a"}, [TextContent("hi")])
e = Edge("a", "f", "uses")`));
    expect(spec.elements[0].children.map((c) => [c.id, c.kind])).toEqual([["a", "note"], ["f", "frame"]]);
    expect(spec.edges).toEqual([{ id: "e", from: "a", to: "f" }]);
  });
});

describe("selectionContext", () => {
  it("sends the selected elements' rectangles and text, and the selected edges with their labels", () => {
    const result = parse(sample);
    const spec = boardSpec(findBoard(result.root)!.props.items as unknown[]);
    const geometry = report("board", spec.elements, spec.edges, solve(spec.elements, spec.edges), 24, spec.problems);
    const ctx = selectionContext(geometry, spec.nodes, (id) => (id === "e1" ? "REST export" : undefined), new Set(["risk", "map", "ghost"]), new Set(["e1"]));
    expect(ctx.board).toBe("board");
    expect(ctx.elements.map((e) => [e.id, e.parent, e.text])).toEqual([
      ["map", "pipeline", "Map"],
      ["risk", null, "Loading executions needs the step mapping first: TR-11753."],
    ]);
    expect(ctx.elements[0]).toMatchObject({ kind: "shape", x: 19, y: 3, w: 6, h: 3 });
    expect(ctx.edges).toEqual([{ id: "e1", from: "source", to: "pipeline", label: "REST export" }]);
    expect(selectionMessage(ctx, "  ")).toBe("Selected on board board: map, risk, e1");
    expect(selectionMessage(ctx, " why pink? ")).toBe("why pink?");
  });
});
