// The board's layout solver: element specs in grid units in, rectangles and problems out. Pure, so the agent-facing
// report can be computed and tested without a page; the board view only converts units to pixels.

/** Where an element goes, in grid units: absolute (x, y) or next to a sibling (rightOf, leftOf, below, above). */
export interface Box {
  x?: number;
  y?: number;
  w?: number;
  h?: number;
  rightOf?: string;
  leftOf?: string;
  below?: string;
  above?: string;
  /** Distance to the anchor, default 1. */
  gap?: number;
  /** Alignment with the anchor across the placing axis, default start. */
  align?: "start" | "center" | "end";
  /** The user dragged it there: the solver ignores this, agents leave the element where it is unless asked. */
  pinned?: boolean;
}

export type ElementKind = "shape" | "note" | "frame";
export type ContainerLayout = "free" | "row" | "column" | "grid";

export interface ElementSpec {
  id: string;
  kind: ElementKind;
  box: Box;
  /** A shape's label: drawn in its header row when it has children. */
  label?: string;
  layout?: ContainerLayout;
  /** Space between children in row, column and grid layouts, default 1. */
  gap?: number;
  /** Columns of a grid layout, default ceil(sqrt(children)). */
  columns?: number;
  children: ElementSpec[];
}

export type Route = "orthogonal" | "straight" | "curved";

export interface EdgeSpec {
  id: string;
  from: string;
  to: string;
  /** How the edge is drawn, default orthogonal; the check follows the same path ([routeEdge]). */
  route?: Route;
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Problem {
  severity: "error" | "warning";
  code: string;
  ids: string[];
  message: string;
}

export interface Solved {
  /** Rectangles on the board, in grid units. */
  absolute: Map<string, Rect>;
  /** Rectangles relative to the parent's top-left corner (the board's origin at the top level). */
  local: Map<string, Rect>;
  parent: Map<string, string | null>;
  /** Elements whose height is a guess: no h, and no measured height (a check without a page measures nothing). */
  estimated: Set<string>;
  problems: Problem[];
}

/** Pixels per grid unit when a Board gives no unit. */
export const DEFAULT_UNIT = 24;

/** A container's padding on every side, in grid units. */
export const PADDING = 1;
/** The header row of a container with a label. */
export const HEADER = 1;

const DEFAULT_SIZE: Record<ElementKind, { w: number; h: number }> = {
  shape: { w: 6, h: 3 },
  note: { w: 8, h: 4 },
  frame: { w: 16, h: 8 },
};

/**
 * Where a dragged element lands, as its box's x and y: [px] is its top-left relative to its parent's top-left (the
 * board's origin at the top level), so the parent's padding and header row come off. w and h are the box's own.
 */
export function movedBox(px: { x: number; y: number }, unit: number, el: ElementSpec, parent: ElementSpec | null) {
  const offset = parent ? { x: PADDING, y: PADDING + (parent.label ? HEADER : 0) } : { x: 0, y: 0 };
  return { x: Math.round(px.x / unit) - offset.x, y: Math.round(px.y / unit) - offset.y, w: el.box.w ?? null, h: el.box.h ?? null };
}

/** Elements that size themselves to their content: the height a leaf needs, in grid units, as the page measured it. */
export type Measured = ReadonlyMap<string, number>;

const ANCHORS = ["rightOf", "leftOf", "below", "above"] as const;

export function solve(elements: ElementSpec[], edges: EdgeSpec[], measured: Measured = new Map()): Solved {
  const out: Solved = { absolute: new Map(), local: new Map(), parent: new Map(), estimated: new Set(), problems: [] };
  const problem = (severity: Problem["severity"], code: string, ids: string[], message: string) =>
    out.problems.push({ severity, code, ids, message });

  const byId = new Map<string, ElementSpec>();
  const index = (list: ElementSpec[], parent: string | null) => {
    for (const el of list) {
      if (byId.has(el.id)) problem("error", "duplicate-id", [el.id], `Two elements are named ${el.id}.`);
      byId.set(el.id, el);
      out.parent.set(el.id, parent);
      index(el.children, el.id);
    }
  };
  index(elements, null);

  const size = new Map<string, { w: number; h: number }>();

  /** Lays out [children] inside a content area; returns their rectangles relative to it and the area they need. */
  function arrange(owner: string, children: ElementSpec[], layout: ContainerLayout, gap: number, columns?: number) {
    for (const c of children) measure(c);
    const rects = new Map<string, Rect>();
    const sized = (c: ElementSpec) => size.get(c.id)!;
    if (layout !== "free") {
      for (const c of children) {
        const b = c.box;
        if (b.x != null || b.y != null || ANCHORS.some((a) => b[a] != null))
          problem("warning", "position-ignored", [c.id], `${c.id} lies in ${owner}'s ${layout} layout, which places it; its x, y and anchors are ignored.`);
      }
    }
    if (layout === "row" || layout === "column") {
      let cursor = 0;
      for (const c of children) {
        const s = sized(c);
        rects.set(c.id, layout === "row" ? { x: cursor, y: 0, ...s } : { x: 0, y: cursor, ...s });
        cursor += (layout === "row" ? s.w : s.h) + gap;
      }
    } else if (layout === "grid") {
      const cols = Math.max(1, columns ?? Math.ceil(Math.sqrt(children.length)));
      const colW: number[] = [];
      const rowH: number[] = [];
      children.forEach((c, i) => {
        const s = sized(c);
        colW[i % cols] = Math.max(colW[i % cols] ?? 0, s.w);
        rowH[Math.floor(i / cols)] = Math.max(rowH[Math.floor(i / cols)] ?? 0, s.h);
      });
      const offset = (sizes: number[], n: number) => sizes.slice(0, n).reduce((a, v) => a + v + gap, 0);
      children.forEach((c, i) => rects.set(c.id, { x: offset(colW, i % cols), y: offset(rowH, Math.floor(i / cols)), ...sized(c) }));
    } else {
      const siblings = new Map(children.map((c) => [c.id, c]));
      const visiting = new Set<string>();
      const place = (c: ElementSpec): Rect => {
        const done = rects.get(c.id);
        if (done) return done;
        const s = sized(c);
        const b = c.box;
        const anchors = ANCHORS.filter((a) => b[a] != null);
        let at = { x: 0, y: 0 };
        if (anchors.length > 1) problem("error", "anchor-conflict", [c.id], `${c.id} names ${anchors.join(" and ")}; give one anchor.`);
        if (anchors.length > 0) {
          const kind = anchors[0];
          const target = b[kind]!;
          if (b.x != null || b.y != null) problem("warning", "position-ignored", [c.id], `${c.id} has ${kind} and x/y; the anchor wins.`);
          const anchor = siblings.get(target);
          if (!anchor) {
            problem("error", byId.has(target) ? "anchor-not-sibling" : "anchor-unknown", [c.id, target],
              byId.has(target) ? `${c.id} is placed ${kind} ${target}, which lies in another container; anchors must be siblings.`
                : `${c.id} is placed ${kind} ${target}, which does not exist.`);
          } else if (visiting.has(target)) {
            problem("error", "anchor-cycle", [c.id, target], `${c.id} and ${target} are placed relative to each other in a cycle.`);
          } else {
            visiting.add(c.id);
            const a = place(anchor);
            visiting.delete(c.id);
            const g = b.gap ?? 1;
            const across = (start: number, length: number, own: number) =>
              b.align === "center" ? start + Math.floor((length - own) / 2) : b.align === "end" ? start + length - own : start;
            if (kind === "rightOf") at = { x: a.x + a.w + g, y: across(a.y, a.h, s.h) };
            if (kind === "leftOf") at = { x: a.x - g - s.w, y: across(a.y, a.h, s.h) };
            if (kind === "below") at = { x: across(a.x, a.w, s.w), y: a.y + a.h + g };
            if (kind === "above") at = { x: across(a.x, a.w, s.w), y: a.y - g - s.h };
          }
        } else if (b.x == null || b.y == null) {
          problem("error", "no-position", [c.id], `${c.id} has no x and y and no anchor; it was put at 0, 0.`);
          at = { x: b.x ?? 0, y: b.y ?? 0 };
        } else {
          at = { x: b.x, y: b.y };
        }
        const r = { ...at, ...s };
        rects.set(c.id, r);
        return r;
      };
      for (const c of children) place(c);
    }
    let w = 0;
    let h = 0;
    for (const c of children) {
      const r = rects.get(c.id)!;
      if (owner !== "" && (r.x < 0 || r.y < 0))
        problem("error", "outside-container", [c.id, owner], `${c.id} starts at ${r.x}, ${r.y}, outside ${owner}'s content area.`);
      w = Math.max(w, r.x + r.w);
      h = Math.max(h, r.y + r.h);
    }
    for (let i = 0; i < children.length; i++)
      for (let j = i + 1; j < children.length; j++) {
        const a = rects.get(children[i].id)!;
        const b = rects.get(children[j].id)!;
        if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h)
          problem("warning", "overlap", [children[i].id, children[j].id], `${children[i].id} and ${children[j].id} overlap.`);
      }
    return { rects, w, h };
  }

  const arranged = new Map<string, Map<string, Rect>>();

  function measure(el: ElementSpec) {
    if (size.has(el.id)) return;
    const b = el.box;
    if (el.children.length > 0) {
      const header = el.label ? HEADER : 0;
      const inner = arrange(el.id, el.children, el.layout ?? "free", el.gap ?? 1, el.columns);
      arranged.set(el.id, inner.rects);
      const need = { w: inner.w + 2 * PADDING, h: inner.h + 2 * PADDING + header };
      const s = { w: b.w ?? need.w, h: b.h ?? need.h };
      if (s.w < need.w || s.h < need.h)
        problem("warning", "overflow", [el.id], `${el.id} is ${s.w}x${s.h} but its children need ${need.w}x${need.h}.`);
      if (b.h == null && el.children.some((c) => out.estimated.has(c.id))) out.estimated.add(el.id);
      size.set(el.id, s);
    } else {
      const fallback = DEFAULT_SIZE[el.kind];
      const need = measured.get(el.id);
      const s = { w: b.w ?? fallback.w, h: b.h ?? need ?? fallback.h };
      if (b.h == null && need == null) out.estimated.add(el.id);
      if (need != null && need > s.h)
        problem("warning", "overflow", [el.id], `${el.id}'s content needs a height of ${need} but its box is ${s.h}.`);
      size.set(el.id, s);
    }
  }

  const top = arrange("", elements, "free", 1);
  const toAbsolute = (list: ElementSpec[], rects: Map<string, Rect>, origin: { x: number; y: number }, offset: { x: number; y: number }) => {
    for (const el of list) {
      const r = rects.get(el.id)!;
      const local = { ...r, x: r.x + offset.x, y: r.y + offset.y };
      const abs = { ...local, x: local.x + origin.x, y: local.y + origin.y };
      out.local.set(el.id, local);
      out.absolute.set(el.id, abs);
      if (el.children.length > 0)
        toAbsolute(el.children, arranged.get(el.id)!, { x: abs.x, y: abs.y }, { x: PADDING, y: PADDING + (el.label ? HEADER : 0) });
    }
  };
  toAbsolute(elements, top.rects, { x: 0, y: 0 }, { x: 0, y: 0 });

  checkEdges(edges, out, problem);
  return out;
}

function ancestors(id: string, parent: Map<string, string | null>): Set<string> {
  const seen = new Set<string>();
  for (let p = parent.get(id); p != null; p = parent.get(p)) seen.add(p);
  return seen;
}

function checkEdges(edges: EdgeSpec[], out: Solved, problem: (s: Problem["severity"], c: string, i: string[], m: string) => void) {
  for (const e of edges) {
    const missing = [e.from, e.to].filter((id) => !out.absolute.has(id));
    if (missing.length > 0) {
      problem("error", "edge-unknown", [e.id, ...missing], `Edge ${e.id} connects ${missing.join(" and ")}, which does not exist.`);
      continue;
    }
    if (e.from === e.to) {
      problem("warning", "edge-self", [e.id], `Edge ${e.id} connects ${e.from} to itself.`);
      continue;
    }
    const path = routeEdge(out.absolute.get(e.from)!, out.absolute.get(e.to)!, e.route);
    const skip = new Set([e.from, e.to, ...ancestors(e.from, out.parent), ...ancestors(e.to, out.parent)]);
    for (const [id, r] of out.absolute) {
      if (skip.has(id)) continue;
      // An element inside an endpoint is covered by that endpoint.
      if (ancestors(id, out.parent).has(e.from) || ancestors(id, out.parent).has(e.to)) continue;
      if (path.some((p, i) => i > 0 && segmentCrosses(path[i - 1], p, r)))
        problem("warning", "edge-crosses", [e.id, id], `Edge ${e.id} (${e.from} → ${e.to}) passes through ${id}.`);
    }
  }
}

export interface Point {
  x: number;
  y: number;
}

export type Side = "top" | "right" | "bottom" | "left";

const center = (r: Rect): Point => ({ x: r.x + r.w / 2, y: r.y + r.h / 2 });

/** The sides an edge from rectangle [a] to [b] leaves and enters by: the ones facing each other. */
export function facingSides(a: Rect, b: Rect): [Side, Side] {
  const dx = center(b).x - center(a).x;
  const dy = center(b).y - center(a).y;
  if (Math.abs(dx) >= Math.abs(dy)) return dx >= 0 ? ["right", "left"] : ["left", "right"];
  return dy >= 0 ? ["bottom", "top"] : ["top", "bottom"];
}

const OUTWARD: Record<Side, Point> = { top: { x: 0, y: -1 }, right: { x: 1, y: 0 }, bottom: { x: 0, y: 1 }, left: { x: -1, y: 0 } };

const sideMiddle = (r: Rect, side: Side): Point =>
  side === "top" ? { x: r.x + r.w / 2, y: r.y } : side === "bottom" ? { x: r.x + r.w / 2, y: r.y + r.h }
    : side === "left" ? { x: r.x, y: r.y + r.h / 2 } : { x: r.x + r.w, y: r.y + r.h / 2 };

/** Points a curved edge is drawn and checked with. */
const CURVE_STEPS = 24;

/**
 * The path of an edge from [a] to [b], in the rectangles' units: from the middle of [a]'s facing side to the middle of
 * [b]'s. orthogonal turns twice, halfway between the sides; curved is a cubic curve leaving and entering square to the
 * sides, as a polyline. The board draws exactly these points, so the edge-crosses check sees what the user sees.
 */
export function routeEdge(a: Rect, b: Rect, route: Route = "orthogonal"): Point[] {
  const [from, to] = facingSides(a, b);
  const p = sideMiddle(a, from);
  const q = sideMiddle(b, to);
  if (route === "straight") return [p, q];
  if (route === "curved") {
    const pull = Math.max(Math.abs(q.x - p.x), Math.abs(q.y - p.y)) / 2;
    const c1 = { x: p.x + OUTWARD[from].x * pull, y: p.y + OUTWARD[from].y * pull };
    const c2 = { x: q.x + OUTWARD[to].x * pull, y: q.y + OUTWARD[to].y * pull };
    return Array.from({ length: CURVE_STEPS + 1 }, (_, i) => {
      const t = i / CURVE_STEPS;
      const u = 1 - t;
      const at = (k: "x" | "y") => u * u * u * p[k] + 3 * u * u * t * c1[k] + 3 * u * t * t * c2[k] + t * t * t * q[k];
      return { x: at("x"), y: at("y") };
    });
  }
  if (from === "left" || from === "right") {
    const mx = (p.x + q.x) / 2;
    return [p, { x: mx, y: p.y }, { x: mx, y: q.y }, q];
  }
  const my = (p.y + q.y) / 2;
  return [p, { x: p.x, y: my }, { x: q.x, y: my }, q];
}

/** The point halfway along [path], where an edge's label goes. */
export function midpoint(path: Point[]): Point {
  const lengths = path.slice(1).map((p, i) => Math.hypot(p.x - path[i].x, p.y - path[i].y));
  let left = lengths.reduce((a, l) => a + l, 0) / 2;
  for (let i = 0; i < lengths.length; i++) {
    if (left <= lengths[i] && lengths[i] > 0) {
      const t = left / lengths[i];
      return { x: path[i].x + (path[i + 1].x - path[i].x) * t, y: path[i].y + (path[i + 1].y - path[i].y) * t };
    }
    left -= lengths[i];
  }
  return path[path.length - 1];
}

/** Whether the segment a-b passes through the inside of [r] (Liang-Barsky clipping; touching an edge does not count). */
export function segmentCrosses(a: { x: number; y: number }, b: { x: number; y: number }, r: Rect): boolean {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  let t0 = 0;
  let t1 = 1;
  const clip = (p: number, q: number) => {
    if (p === 0) return q > 0;
    const t = q / p;
    if (p < 0) {
      if (t > t1) return false;
      if (t > t0) t0 = t;
    } else {
      if (t < t0) return false;
      if (t < t1) t1 = t;
    }
    return true;
  };
  const ok = clip(-dx, a.x - r.x) && clip(dx, r.x + r.w - a.x) && clip(-dy, a.y - r.y) && clip(dy, r.y + r.h - a.y);
  return ok && t1 - t0 > 1e-9;
}

/** What the agent reads back: every element's rectangle in grid units, the edges and the problems. */
export interface BoardReport {
  /** The Board's statement name. */
  board: string;
  unit: number;
  /** estimated: the height is a default, not the content's (no h given and nothing measured, as in a parse-only check). */
  elements: { id: string; kind: ElementKind; parent: string | null; x: number; y: number; w: number; h: number; estimated: boolean }[];
  edges: { id: string; from: string; to: string }[];
  problems: Problem[];
}

export function report(board: string, elements: ElementSpec[], edges: EdgeSpec[], solved: Solved, unit: number, extra: Problem[] = []): BoardReport {
  const list: BoardReport["elements"] = [];
  const walk = (els: ElementSpec[]) => {
    for (const el of els) {
      const r = solved.absolute.get(el.id)!;
      list.push({ id: el.id, kind: el.kind, parent: solved.parent.get(el.id) ?? null, ...r, estimated: solved.estimated.has(el.id) });
      walk(el.children);
    }
  };
  walk(elements);
  return { board, unit, elements: list, edges: edges.map(({ id, from, to }) => ({ id, from, to })), problems: [...extra, ...solved.problems] };
}

/** The reports of every Board in a parsed document, from the parse alone: nothing is measured, see `estimated`. */
export function boardReports(boards: { board: string; spec: { elements: ElementSpec[]; edges: EdgeSpec[]; problems: Problem[] }; unit?: number }[]): BoardReport[] {
  return boards.map(({ board, spec, unit }) =>
    report(board, spec.elements, spec.edges, solve(spec.elements, spec.edges), unit ?? DEFAULT_UNIT, spec.problems));
}
