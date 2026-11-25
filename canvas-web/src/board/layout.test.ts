import { describe, expect, it } from "vitest";
import { midpoint, movedBox, resizedBox, routeEdge, segmentCrosses, solve, type Box, type ElementSpec } from "./layout";

const note = (id: string, box: Box): ElementSpec => ({ id, kind: "note", box, children: [] });
const shape = (id: string, box: Box, children: ElementSpec[] = [], extra: Partial<ElementSpec> = {}): ElementSpec => ({
  id, kind: "shape", box, children, ...extra,
});
const codes = (s: ReturnType<typeof solve>) => s.problems.map((p) => `${p.code}:${p.ids.join(",")}`);

describe("free placement", () => {
  it("puts an element at its x and y with its size", () => {
    const s = solve([note("a", { x: 2, y: 3, w: 5, h: 4 })], []);
    expect(s.absolute.get("a")).toEqual({ x: 2, y: 3, w: 5, h: 4 });
    expect(s.problems).toEqual([]);
  });

  it("places rightOf, leftOf, below and above a sibling with the gap", () => {
    const s = solve([
      note("a", { x: 10, y: 10, w: 4, h: 4 }),
      note("r", { rightOf: "a", gap: 2, w: 3, h: 2 }),
      note("l", { leftOf: "a", w: 3, h: 2 }),
      note("b", { below: "a", gap: 0, w: 4, h: 2 }),
      note("t", { above: "a", gap: 3, w: 4, h: 2 }),
    ], []);
    expect(s.absolute.get("r")).toEqual({ x: 16, y: 10, w: 3, h: 2 });
    expect(s.absolute.get("l")).toEqual({ x: 6, y: 10, w: 3, h: 2 });
    expect(s.absolute.get("b")).toEqual({ x: 10, y: 14, w: 4, h: 2 });
    expect(s.absolute.get("t")).toEqual({ x: 10, y: 5, w: 4, h: 2 });
  });

  it("aligns across the placing axis: center and end", () => {
    const s = solve([
      note("a", { x: 0, y: 0, w: 4, h: 6 }),
      note("c", { rightOf: "a", align: "center", w: 2, h: 2 }),
      note("e", { below: "a", align: "end", w: 2, h: 2 }),
    ], []);
    expect(s.absolute.get("c")).toMatchObject({ x: 5, y: 2 });
    expect(s.absolute.get("e")).toMatchObject({ x: 2, y: 7 });
  });

  it("resolves chains of anchors whatever their order", () => {
    const s = solve([note("c", { rightOf: "b", w: 1, h: 1 }), note("b", { rightOf: "a", w: 1, h: 1 }), note("a", { x: 0, y: 0, w: 1, h: 1 })], []);
    expect(s.absolute.get("c")).toMatchObject({ x: 4, y: 0 });
  });

  it("reports a cycle, an unknown anchor, two anchors and a missing position", () => {
    const s = solve([
      note("a", { rightOf: "b", w: 1, h: 1 }),
      note("b", { rightOf: "a", w: 1, h: 1 }),
      note("c", { below: "nope", w: 1, h: 1 }),
      note("d", { x: 20, y: 20, rightOf: "c", below: "c", w: 1, h: 1 }),
      note("e", { x: 40, w: 1, h: 1 }),
    ], []);
    expect(codes(s)).toEqual(expect.arrayContaining(["anchor-cycle:b,a", "anchor-unknown:c,nope", "anchor-conflict:d", "position-ignored:d", "no-position:e"]));
  });

  it("refuses an anchor in another container", () => {
    const s = solve([shape("g", { x: 0, y: 0 }, [note("inner", { x: 0, y: 0, w: 2, h: 2 })]), note("n", { rightOf: "inner", w: 1, h: 1 })], []);
    expect(codes(s)).toContain("anchor-not-sibling:n,inner");
  });

  it("warns about overlapping siblings, not about ones that only touch", () => {
    const s = solve([note("a", { x: 0, y: 0, w: 4, h: 4 }), note("b", { x: 3, y: 3, w: 4, h: 4 }), note("c", { x: 7, y: 0, w: 2, h: 2 })], []);
    expect(codes(s)).toEqual(["overlap:a,b"]);
  });
});

describe("sizes", () => {
  it("uses the kind's default size, and a measured height for a leaf without h", () => {
    const s = solve([note("a", { x: 0, y: 0 }), note("b", { x: 20, y: 0, w: 5 })], [], new Map([["b", 7]]));
    expect(s.absolute.get("a")).toEqual({ x: 0, y: 0, w: 8, h: 4 });
    expect(s.absolute.get("b")).toEqual({ x: 20, y: 0, w: 5, h: 7 });
  });

  it("warns when measured content is taller than a fixed h", () => {
    const s = solve([note("a", { x: 0, y: 0, h: 2 })], [], new Map([["a", 5]]));
    expect(codes(s)).toEqual(["overflow:a"]);
  });
});

describe("containers", () => {
  it("lays out a row with the gap, padding and a header for the label; grows to fit", () => {
    const s = solve([shape("g", { x: 10, y: 10 }, [note("a", { w: 4, h: 3 }), note("b", { w: 2, h: 5 })], { label: "G", layout: "row", gap: 2 })], []);
    expect(s.absolute.get("a")).toEqual({ x: 11, y: 12, w: 4, h: 3 });
    expect(s.absolute.get("b")).toEqual({ x: 17, y: 12, w: 2, h: 5 });
    expect(s.local.get("a")).toEqual({ x: 1, y: 2, w: 4, h: 3 });
    expect(s.absolute.get("g")).toEqual({ x: 10, y: 10, w: 10, h: 8 });
    expect(s.problems).toEqual([]);
  });

  it("lays out a column and a grid", () => {
    const col = solve([shape("g", { x: 0, y: 0 }, [note("a", { w: 4, h: 3 }), note("b", { w: 2, h: 2 })], { layout: "column" })], []);
    expect(col.absolute.get("b")).toMatchObject({ x: 1, y: 5 });
    const kids = ["a", "b", "c", "d", "e"].map((id, i) => note(id, { w: i === 0 ? 5 : 2, h: i === 3 ? 4 : 2 }));
    const grid = solve([shape("g", { x: 0, y: 0 }, kids, { layout: "grid", columns: 2, gap: 1 })], []);
    // columns 5 and 2 wide, rows 2, 4 and 2 high
    expect(grid.local.get("b")).toMatchObject({ x: 7, y: 1 });
    expect(grid.local.get("d")).toMatchObject({ x: 7, y: 4 });
    expect(grid.local.get("e")).toMatchObject({ x: 1, y: 9 });
    expect(grid.absolute.get("g")).toMatchObject({ w: 10, h: 12 });
  });

  it("places free children relative to the content area and warns when a fixed size is too small", () => {
    const s = solve([shape("g", { x: 5, y: 5, w: 6, h: 4 }, [note("a", { x: 0, y: 0, w: 8, h: 2 })], { label: "G" })], []);
    expect(s.absolute.get("a")).toMatchObject({ x: 6, y: 7 });
    expect(codes(s)).toEqual(["overflow:g"]);
  });

  it("reports a child outside the content area and positions ignored by a layout", () => {
    const s = solve([
      shape("free", { x: 0, y: 0 }, [note("a", { x: -2, y: 0, w: 2, h: 2 })]),
      shape("row", { x: 20, y: 0 }, [note("b", { x: 3, y: 3, w: 2, h: 2 })], { layout: "row" }),
    ], []);
    expect(codes(s)).toEqual(expect.arrayContaining(["outside-container:a,free", "position-ignored:b"]));
  });

  it("places a container by an anchor using its fitted size", () => {
    const s = solve([
      note("left", { x: 0, y: 0, w: 4, h: 10 }),
      shape("g", { rightOf: "left", align: "center" }, [note("a", { w: 4, h: 2 })], { layout: "row" }),
    ], []);
    expect(s.absolute.get("g")).toEqual({ x: 5, y: 3, w: 6, h: 4 });
  });
});

describe("edges", () => {
  it("reports unknown ends and edges through other elements, not through the ends' containers", () => {
    const s = solve([
      note("a", { x: 0, y: 0, w: 2, h: 2 }),
      note("wall", { x: 5, y: 0, w: 2, h: 2 }),
      note("b", { x: 10, y: 0, w: 2, h: 2 }),
      shape("g", { x: 0, y: 10 }, [note("in", { x: 0, y: 0, w: 2, h: 2 })]),
    ], [
      { id: "e1", from: "a", to: "b" },
      { id: "e2", from: "a", to: "ghost" },
      { id: "e3", from: "a", to: "in" },
    ]);
    expect(codes(s)).toEqual(["edge-crosses:e1,wall", "edge-unknown:e2,ghost"]);
  });

  it("follows the drawn path: an orthogonal edge around an element is fine, a straight one through it is not", () => {
    // From n's left side to agent's right side, turning at x 12.5: clear of the wall at 14..25, which the straight line
    // between the centres would cross.
    const els = [note("agent", { x: 2, y: 4, w: 8, h: 3 }), note("wall", { x: 14, y: 4, w: 11, h: 9 }), note("n", { x: 15, y: 15, w: 8, h: 4 })];
    expect(codes(solve(els, [{ id: "e", from: "n", to: "agent" }]))).toEqual([]);
    expect(codes(solve(els, [{ id: "e", from: "n", to: "agent", route: "straight" }]))).toEqual([]);
    const blocked = [note("a", { x: 0, y: 0, w: 2, h: 2 }), note("wall", { x: 4, y: 3, w: 2, h: 2 }), note("b", { x: 8, y: 6, w: 2, h: 2 })];
    expect(codes(solve(blocked, [{ id: "e", from: "a", to: "b", route: "straight" }]))).toEqual(["edge-crosses:e,wall"]);
    expect(codes(solve(blocked, [{ id: "e", from: "a", to: "b" }]))).toEqual(["edge-crosses:e,wall"]);
    expect(codes(solve(blocked, [{ id: "e", from: "a", to: "b", route: "curved" }]))).toEqual(["edge-crosses:e,wall"]);
  });

  it("reports a pass through a container and its children once, as the container", () => {
    const els = [note("a", { x: 0, y: 0, w: 2, h: 2 }), shape("g", { x: 5, y: 0, w: 4, h: 4 }, [note("in", { x: 0, y: 0, w: 2, h: 2 })]), note("b", { x: 12, y: 0, w: 2, h: 2 })];
    expect(codes(solve(els, [{ id: "e", from: "a", to: "b", route: "straight" }]))).toEqual(["edge-crosses:e,g"]);
  });

  it("routes between the facing sides' middles", () => {
    const a = { x: 0, y: 0, w: 4, h: 2 };
    expect(routeEdge(a, { x: 10, y: 6, w: 2, h: 2 })).toEqual([{ x: 4, y: 1 }, { x: 7, y: 1 }, { x: 7, y: 7 }, { x: 10, y: 7 }]);
    expect(routeEdge(a, { x: 1, y: 10, w: 2, h: 2 })).toEqual([{ x: 2, y: 2 }, { x: 2, y: 6 }, { x: 2, y: 6 }, { x: 2, y: 10 }]);
    expect(routeEdge(a, { x: -9, y: 0, w: 2, h: 2 }, "straight")).toEqual([{ x: 0, y: 1 }, { x: -7, y: 1 }]);
    const curve = routeEdge(a, { x: 10, y: 6, w: 2, h: 2 }, "curved");
    expect(curve[0]).toEqual({ x: 4, y: 1 });
    expect(curve.at(-1)).toEqual({ x: 10, y: 7 });
    expect(midpoint([{ x: 0, y: 0 }, { x: 4, y: 0 }, { x: 4, y: 4 }])).toEqual({ x: 4, y: 0 });
    expect(midpoint([{ x: 0, y: 0 }, { x: 0, y: 6 }])).toEqual({ x: 0, y: 3 });
  });

  it("segmentCrosses counts the inside only", () => {
    const r = { x: 0, y: 0, w: 4, h: 4 };
    expect(segmentCrosses({ x: -1, y: 2 }, { x: 5, y: 2 }, r)).toBe(true);
    expect(segmentCrosses({ x: -1, y: 0 }, { x: 5, y: 0 }, r)).toBe(false);
    expect(segmentCrosses({ x: -1, y: 5 }, { x: 5, y: 5 }, r)).toBe(false);
    expect(segmentCrosses({ x: 2, y: -3 }, { x: 2, y: -1 }, r)).toBe(false);
  });
});

describe("movedBox", () => {
  const plain = note("n", { rightOf: "a", w: 5 });
  it("turns a top-level drop into grid units, keeping the box's own sizes", () => {
    expect(movedBox({ x: 96, y: 48.4 }, 24, plain, null)).toEqual({ x: 4, y: 2, w: 5, h: null });
  });
  it("takes a container's padding and header row off", () => {
    const parent = shape("g", { x: 0, y: 0 }, [plain], { label: "G" });
    expect(movedBox({ x: 72, y: 72 }, 24, plain, parent)).toEqual({ x: 2, y: 1, w: 5, h: null });
    expect(movedBox({ x: 72, y: 72 }, 24, plain, { ...parent, label: undefined })).toEqual({ x: 2, y: 2, w: 5, h: null });
  });
});

describe("resizedBox", () => {
  it("turns a resize into grid units, the parent's padding and header row off, never smaller than one unit", () => {
    expect(resizedBox({ x: 96, y: 48, width: 170, height: 10 }, 24, null)).toEqual({ x: 4, y: 2, w: 7, h: 1 });
    const parent = shape("g", { x: 0, y: 0 }, [], { label: "G" });
    expect(resizedBox({ x: 72, y: 72, width: 48, height: 72 }, 24, parent)).toEqual({ x: 2, y: 1, w: 2, h: 3 });
  });
});
